package org.example.nfp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.nfp.selection.NfpSelectionModel;
import org.example.nfp.selection.NfpFeatureExtractor;
import org.example.nfp.selection.NfpScoringProfile;
import org.example.nfp.selection.NfpSelectionStage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

/** Agent 专用搜索会话：跨 Profile 复用未按评分截断的合法位置，baseline 不创建会话。 */
public final class NfpSearchSession implements AutoCloseable {
    private static final ThreadLocal<NfpSearchSession> ACTIVE = new ThreadLocal<>();
    private static final int MAX_ENTRIES = 1_000;
    private static final int MAX_CANDIDATES = 30_000;
    private final Map<String, PolygonStitcher.StitchingResult> rawCache = new LinkedHashMap<>(64, 0.75f, true);
    private final long deadlineNanos;
    private long trialDeadlineNanos = Long.MAX_VALUE;
    private int cachedCandidates;
    private long hits;
    private long misses;
    private final List<List<Map<String, Double>>> placementProbes = new ArrayList<>();
    public final double plateLength;
    public final double plateWidth;

    public NfpSearchSession(double plateLength, double plateWidth, long budgetMs) {
        if (ACTIVE.get() != null) throw new IllegalStateException("不能嵌套 NFP 搜索会话");
        if (!(plateLength > 0) || !(plateWidth > 0) || !Double.isFinite(plateLength + plateWidth)) {
            throw new IllegalArgumentException("板材尺寸必须是有限正数");
        }
        this.plateLength = plateLength;
        this.plateWidth = plateWidth;
        deadlineNanos = deadline(budgetMs);
        ACTIVE.set(this);
    }

    /** 与桥接器一致：plates[0].width/height 是当前案例使用的板材尺寸。 */
    public static double[] plate(Path input) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
        if (!root.has("plates") || root.getAsJsonArray("plates").isEmpty()) return new double[]{2440, 1220};
        JsonObject plate = root.getAsJsonArray("plates").get(0).getAsJsonObject();
        return new double[]{plate.get("width").getAsDouble(), plate.get("height").getAsDouble()};
    }

    public static NfpSearchSession current() { return ACTIVE.get(); }

    /** 在几何操作之间协作式停止；不杀线程，也不会把半成品当作有效候选。 */
    public static void checkDeadline() {
        NfpSearchSession session = ACTIVE.get();
        if (session != null && System.nanoTime() >= Math.min(session.deadlineNanos, session.trialDeadlineNanos)) {
            throw new SearchTimeoutException();
        }
    }

    public void startTrial(long budgetMs) { trialDeadlineNanos = deadline(budgetMs); }
    public void finishTrial() { trialDeadlineNanos = Long.MAX_VALUE; }
    public long remainingMs() { return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000); }
    public long hits() { return hits; }
    public long misses() { return misses; }

    /** 缓存键使用精确浮点表示，不含 Profile、排序或 Top-K；每次命中后重新评分。 */
    public List<PolygonStitcher.StitchingCandidate> candidates(Block block, PolygonItem item,
            List<Integer> rotations, NfpSelectionModel model, int limit) {
        checkDeadline();
        List<List<Point>> fixed = block.placedPolygons();
        StringBuilder key = new StringBuilder().append(block.areaSum).append('|').append(block.boxArea)
                .append('|').append(item.area).append('|').append(item.smallItem).append('|').append(rotations);
        for (List<Point> polygon : fixed) appendPolygon(key, polygon);
        key.append("moving");
        appendPolygon(key, item.points);
        String cacheKey = key.toString();
        PolygonStitcher.StitchingResult raw = rawCache.get(cacheKey);
        if (raw == null) {
            misses++;
            // Integer.MAX_VALUE 只表示不按旧分数截断；仍保留原有硬几何安全检查。
            raw = PolygonStitcher.findTopStitchesForFixedPolygons(fixed, block.areaSum, block.boxArea,
                    item.points, item.area, rotations, item.smallItem, Integer.MAX_VALUE);
            int size = raw.validCandidates.size();
            // 大单项不入缓存，避免为了缓存而截断候选空间。
            if (size <= MAX_CANDIDATES) {
                while (!rawCache.isEmpty() && (rawCache.size() >= MAX_ENTRIES || cachedCandidates + size > MAX_CANDIDATES)) {
                    String oldest = rawCache.keySet().iterator().next();
                    cachedCandidates -= rawCache.remove(oldest).validCandidates.size();
                }
                rawCache.put(cacheKey, raw);
                cachedCandidates += size;
            }
        } else hits++;
        if (placementProbes.size() < 8 && raw.validCandidates.size() >= 2) {
            placementProbes.add(raw.validCandidates.stream().limit(24)
                    .map(candidate -> NfpFeatureExtractor.placement(candidate, item, 0)).toList());
        }
        return PolygonStitcher.rankAgentCandidates(raw, item, model, limit);
    }

    /** 对同一实际候选探针比较排序，而非只比较 JSON 权重；这是预算筛选启发式，不宣称策略等价。 */
    public String behaviorSignature(NfpScoringProfile profile, List<Block> roots) {
        if (placementProbes.isEmpty() || roots.size() < 2) return "";
        StringBuilder signature = new StringBuilder().append(profile.rootBeamWidth()).append('|')
                .append(profile.rootCandidateLimit()).append('|').append(profile.generationCap()).append('|')
                .append(profile.globalPlanWidth()).append('|').append(profile.includeNearRectangularRoots()).append('|')
                .append(profile.diversityWeight()).append('|').append(profile.explorationSlots()).append('|')
                .append(profile.allowAllSingles()).append('|').append(profile.targetCompositeMembers());
        for (var probe : placementProbes) appendOrder(signature, profile, NfpSelectionStage.PLACEMENT, probe);
        appendOrder(signature, profile, NfpSelectionStage.ROOT_BEAM,
                roots.stream().map(block -> NfpFeatureExtractor.root(block, roots.size() - block.memberCount(), roots.size())).toList());
        // 全局分数尚无完整冲突集合探针，保留实际评分项，避免把不同全局策略误判为同一行为。
        signature.append(profile.toJson().get("globalPlanScore")).append(profile.strategyFamily());
        return signature.toString();
    }

    private static void appendOrder(StringBuilder signature, NfpScoringProfile profile, NfpSelectionStage stage,
                                    List<Map<String, Double>> probe) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < probe.size(); i++) order.add(i);
        order.sort(Comparator.<Integer>comparingDouble(index -> -profile.score(stage, probe.get(index))).thenComparingInt(index -> index));
        signature.append('|').append(order);
    }

    private static void appendPolygon(StringBuilder key, List<Point> polygon) {
        key.append('[');
        for (Point point : polygon) key.append(Double.toHexString(point.x)).append(',')
                .append(Double.toHexString(point.y)).append(';');
        key.append(']');
    }

    private static long deadline(long budgetMs) {
        return System.nanoTime() + Math.min(Math.max(1, budgetMs), 86_400_000L) * 1_000_000;
    }

    @Override public void close() {
        rawCache.clear();
        ACTIVE.remove();
    }

    /** 明确区分预算用尽与非法几何，调用方据此记录 skipped_timeout。 */
    public static final class SearchTimeoutException extends RuntimeException {
        public SearchTimeoutException() { super("NFP Agent 搜索预算已用尽"); }
    }
}
