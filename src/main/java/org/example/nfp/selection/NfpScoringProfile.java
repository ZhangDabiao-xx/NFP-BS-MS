package org.example.nfp.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A small, validated vocabulary for Agent-produced scoring rather than executable expressions. */
public final class NfpScoringProfile {
    public static final int MAX_TERMS_PER_STAGE = 12;
    public static final double MAX_ABS_WEIGHT = 3.0;
    private static final Set<String> TRANSFORMS = Set.of("identity", "one_minus", "hinge_above", "hinge_below");
    /** Agent may choose only from these pre-reviewed search bounds; it never supplies executable search code. */
    private static final Set<Integer> ROOT_BEAM_WIDTHS = Set.of(3, 5, 8);
    private static final Set<Integer> ROOT_CANDIDATE_LIMITS = Set.of(5, 8, 12);
    private static final Set<Integer> GENERATION_CAPS = Set.of(20, 40, 60);
    private static final Set<Integer> GLOBAL_PLAN_WIDTHS = Set.of(20, 40, 80);
    private static final Map<NfpSelectionStage, Set<String>> FEATURES = allowedFeatures();

    /** Mutually distinct exploration families used by the experiment portfolio. */
    public enum StrategyFamily {
        COMPACT,
        LARGE_BLOCK,
        RESOURCE_PRESERVING,
        ANTI_BASELINE
    }

    public record Term(String feature, String transform, double weight, double threshold) {
        double apply(double value) {
            return switch (transform) {
                case "identity" -> value;
                case "one_minus" -> 1.0 - value;
                case "hinge_above" -> Math.max(0.0, value - threshold);
                case "hinge_below" -> Math.max(0.0, threshold - value);
                default -> throw new IllegalStateException("validated transform missing: " + transform);
            };
        }
    }

    private final Map<NfpSelectionStage, List<Term>> terms;
    private final double diversityWeight;
    private final int explorationSlots;
    private final boolean allowAllSingles;
    private final StrategyFamily strategyFamily;
    private final int rootBeamWidth;
    private final int rootCandidateLimit;
    private final int generationCap;
    private final int globalPlanWidth;
    private final int targetCompositeMembers;
    private final boolean includeNearRectangularRoots;
    private final String reason;
    private final String hypothesis;
    private final String id;

    private NfpScoringProfile(Map<NfpSelectionStage, List<Term>> terms, double diversityWeight,
                              int explorationSlots, boolean allowAllSingles, StrategyFamily strategyFamily,
                              int rootBeamWidth, int rootCandidateLimit, int generationCap, int globalPlanWidth,
                              int targetCompositeMembers, boolean includeNearRectangularRoots,
                              String reason, String hypothesis) {
        this.terms = Map.copyOf(terms);
        this.diversityWeight = diversityWeight;
        this.explorationSlots = explorationSlots;
        this.allowAllSingles = allowAllSingles;
        this.strategyFamily = strategyFamily;
        this.rootBeamWidth = rootBeamWidth;
        this.rootCandidateLimit = rootCandidateLimit;
        this.generationCap = generationCap;
        this.globalPlanWidth = globalPlanWidth;
        this.targetCompositeMembers = targetCompositeMembers;
        this.includeNearRectangularRoots = includeNearRectangularRoots;
        this.reason = limited(reason);
        this.hypothesis = limited(hypothesis);
        // Narrative text must not create a fake "new" profile when executable scoring is unchanged.
        this.id = sha256(scoringFingerprintJson().toString());
    }

    public static NfpScoringProfile fromJson(JsonObject value, int beamWidth) {
        if (value == null) throw new IllegalArgumentException("评分配置不能为空。");
        Map<NfpSelectionStage, List<Term>> parsed = new EnumMap<>(NfpSelectionStage.class);
        parsed.put(NfpSelectionStage.PLACEMENT, parseTerms(value, "placementScore", NfpSelectionStage.PLACEMENT));
        parsed.put(NfpSelectionStage.ROOT_BEAM, parseTerms(value, "rootBeamScore", NfpSelectionStage.ROOT_BEAM));
        parsed.put(NfpSelectionStage.GLOBAL_PLAN, parseTerms(value, "globalPlanScore", NfpSelectionStage.GLOBAL_PLAN));
        double diversity = finiteRange(number(value, "diversityWeight", 0.0), -1.0, 1.0, "diversityWeight");
        StrategyFamily family = strategyFamily(value);
        int rootWidth = allowedInteger(value, "rootBeamWidth", beamWidth, ROOT_BEAM_WIDTHS);
        int slots = integerRange(value, "explorationSlots", 0, Math.max(0, rootWidth - 1));
        boolean allSingles = booleanValue(value, "allowAllSingles", false);
        int candidateLimit = allowedInteger(value, "rootCandidateLimit", Math.max(5, rootWidth), ROOT_CANDIDATE_LIMITS);
        if (candidateLimit < rootWidth) throw new IllegalArgumentException("rootCandidateLimit 不能小于 rootBeamWidth。");
        int generationCap = allowedInteger(value, "generationCap", 20, GENERATION_CAPS);
        int globalWidth = allowedInteger(value, "globalPlanWidth", 20, GLOBAL_PLAN_WIDTHS);
        int targetMembers = allowedInteger(value, "targetCompositeMembers", defaultTarget(family), Set.of(2, 3, 4));
        boolean includeNearRectangles = booleanValue(value, "includeNearRectangularRoots", false);
        if (includeNearRectangles && family != StrategyFamily.LARGE_BLOCK && family != StrategyFamily.ANTI_BASELINE) {
            throw new IllegalArgumentException("includeNearRectangularRoots 仅允许 LARGE_BLOCK 或 ANTI_BASELINE。");
        }
        return new NfpScoringProfile(parsed, diversity, slots, allSingles, family, rootWidth, candidateLimit,
                generationCap, globalWidth, targetMembers, includeNearRectangles,
                string(value, "reason"), string(value, "hypothesis"));
    }

    public double score(NfpSelectionStage stage, Map<String, Double> features) {
        double result = 0.0;
        for (Term term : terms.get(stage)) {
            double value = features.getOrDefault(term.feature(), 0.0);
            result += term.weight() * term.apply(clamp(value));
        }
        return result + strategyBonus(stage, features);
    }

    public String id() { return id; }
    public double diversityWeight() { return diversityWeight; }
    public int explorationSlots() { return explorationSlots; }
    public boolean allowAllSingles() { return allowAllSingles; }
    public StrategyFamily strategyFamily() { return strategyFamily; }
    public int rootBeamWidth() { return rootBeamWidth; }
    public int rootCandidateLimit() { return rootCandidateLimit; }
    public int generationCap() { return generationCap; }
    public int globalPlanWidth() { return globalPlanWidth; }
    public int targetCompositeMembers() { return targetCompositeMembers; }
    public boolean includeNearRectangularRoots() { return includeNearRectangularRoots; }
    public String reason() { return reason; }
    public String hypothesis() { return hypothesis; }

    public JsonObject toJson() {
        JsonObject value = canonicalJson();
        value.addProperty("profileId", id);
        return value;
    }

    /**
     * Produces a bounded deterministic alternative when a remote model repeats a known profile.
     * It changes only allowed numeric weights and preserves the schema/geometry boundary.
     */
    public NfpScoringProfile explorationVariant(int ordinal, int beamWidth) {
        JsonObject variant = toJson();
        int stageIndex = Math.floorMod(ordinal, 3);
        String stage = stageIndex == 0 ? "placementScore" : stageIndex == 1 ? "rootBeamScore" : "globalPlanScore";
        JsonArray values = variant.getAsJsonArray(stage);
        JsonObject first = values.get(0).getAsJsonObject();
        double current = first.get("weight").getAsDouble();
        double delta = (ordinal & 1) == 0 ? 0.25 : -0.25;
        double adjusted = Math.max(-MAX_ABS_WEIGHT, Math.min(MAX_ABS_WEIGHT, current + delta));
        // At a bound, move inward instead of accidentally recreating the same profile hash.
        if (Double.compare(adjusted, current) == 0) adjusted = Math.max(-MAX_ABS_WEIGHT,
                Math.min(MAX_ABS_WEIGHT, current - delta));
        first.addProperty("weight", adjusted);
        variant.addProperty("diversityWeight", Math.max(-1.0, Math.min(1.0, diversityWeight + 0.1 * ((ordinal & 1) == 0 ? 1 : -1))));
        variant.addProperty("explorationSlots", Math.min(Math.max(0, rootBeamWidth - 1), Math.max(0, explorationSlots + 1)));
        variant.addProperty("reason", limited(reason + "；重复配置触发受限探索变体。"));
        variant.addProperty("hypothesis", limited(hypothesis + "；测试受限权重扰动是否改变候选排序。"));
        return fromJson(variant, beamWidth);
    }

    /** Applies the experiment portfolio family after validation, so one LLM response cannot collapse every trial into one mode. */
    public NfpScoringProfile withRequiredStrategyFamily(String requiredFamily, int fallbackBeamWidth) {
        if (requiredFamily == null || requiredFamily.isBlank()) return this;
        StrategyFamily required;
        try { required = StrategyFamily.valueOf(requiredFamily); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("未知 requiredStrategyFamily: " + requiredFamily); }
        if (required == strategyFamily) return this;
        JsonObject variant = toJson();
        variant.addProperty("strategyFamily", required.name());
        variant.addProperty("targetCompositeMembers", defaultTarget(required));
        variant.addProperty("includeNearRectangularRoots", required == StrategyFamily.LARGE_BLOCK || required == StrategyFamily.ANTI_BASELINE);
        variant.addProperty("reason", limited(reason + "；实验组合强制使用 " + required + " 策略族。"));
        variant.addProperty("hypothesis", limited(hypothesis + "；该轮必须探索 " + required + " 的不同结构。"));
        return fromJson(variant, fallbackBeamWidth);
    }

    private JsonObject canonicalJson() {
        JsonObject value = scoringFingerprintJson();
        value.addProperty("reason", reason);
        value.addProperty("hypothesis", hypothesis);
        return value;
    }

    /** Contains exactly the fields that can affect deterministic candidate selection. */
    private JsonObject scoringFingerprintJson() {
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", "2.0");
        value.add("placementScore", termsJson(NfpSelectionStage.PLACEMENT));
        value.add("rootBeamScore", termsJson(NfpSelectionStage.ROOT_BEAM));
        value.add("globalPlanScore", termsJson(NfpSelectionStage.GLOBAL_PLAN));
        value.addProperty("diversityWeight", diversityWeight);
        value.addProperty("explorationSlots", explorationSlots);
        value.addProperty("allowAllSingles", allowAllSingles);
        value.addProperty("strategyFamily", strategyFamily.name());
        value.addProperty("rootBeamWidth", rootBeamWidth);
        value.addProperty("rootCandidateLimit", rootCandidateLimit);
        value.addProperty("generationCap", generationCap);
        value.addProperty("globalPlanWidth", globalPlanWidth);
        value.addProperty("targetCompositeMembers", targetCompositeMembers);
        value.addProperty("includeNearRectangularRoots", includeNearRectangularRoots);
        return value;
    }

    /** Adds a small, explicit family bias on top of LLM-provided terms; geometry constraints remain unchanged. */
    private double strategyBonus(NfpSelectionStage stage, Map<String, Double> features) {
        if (stage == NfpSelectionStage.PLACEMENT) return 0.0;
        return switch (strategyFamily) {
            case COMPACT -> 0.50 * feature(features, "fillRate", "meanBlockFillRate")
                    + 0.35 * feature(features, "sourceBoxSavingRatio", "totalBoxSavingRatio");
            case LARGE_BLOCK -> stage == NfpSelectionStage.ROOT_BEAM
                    ? 1.10 * targetMemberBonus(features.getOrDefault("memberCountRatio", 0.0))
                    : 0.70 * feature(features, "coveredItemRatio", "selectedBlockCountRatio");
            case RESOURCE_PRESERVING -> stage == NfpSelectionStage.ROOT_BEAM
                    ? 0.90 * (1.0 - features.getOrDefault("smallRectangleUsageRatio", 0.0))
                    : 0.90 * features.getOrDefault("remainingSmallRectangleRatio", 0.0);
            case ANTI_BASELINE -> stage == NfpSelectionStage.ROOT_BEAM
                    ? 1.30 * targetMemberBonus(features.getOrDefault("memberCountRatio", 0.0))
                    : 0.65 * (1.0 - features.getOrDefault("predictedSingleItemRatio", 1.0));
        };
    }

    private double targetMemberBonus(double memberRatio) {
        // 平滑奖励中间状态，目标 4 件时 2/3 件分支也能获得正梯度；目标不再是验收硬门槛。
        return Math.max(0.0, 1.0 - Math.abs(memberRatio * 20.0 - targetCompositeMembers) / targetCompositeMembers);
    }

    private static double feature(Map<String, Double> values, String rootName, String globalName) {
        return clamp(values.getOrDefault(rootName, values.getOrDefault(globalName, 0.0)));
    }

    private static int defaultTarget(StrategyFamily family) {
        return switch (family) {
            case COMPACT -> 3;
            case LARGE_BLOCK, ANTI_BASELINE -> 4;
            case RESOURCE_PRESERVING -> 2;
        };
    }

    private static StrategyFamily strategyFamily(JsonObject value) {
        String name = string(value, "strategyFamily");
        if (name.isBlank()) return StrategyFamily.COMPACT;
        try { return StrategyFamily.valueOf(name); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("未知 strategyFamily: " + name); }
    }

    private JsonArray termsJson(NfpSelectionStage stage) {
        List<Term> ordered = new ArrayList<>(terms.get(stage));
        ordered.sort(Comparator.comparing(Term::feature).thenComparing(Term::transform)
                .thenComparingDouble(Term::weight).thenComparingDouble(Term::threshold));
        JsonArray array = new JsonArray();
        for (Term term : ordered) {
            JsonObject value = new JsonObject();
            value.addProperty("feature", term.feature()); value.addProperty("transform", term.transform());
            value.addProperty("weight", term.weight()); value.addProperty("threshold", term.threshold());
            array.add(value);
        }
        return array;
    }

    private static List<Term> parseTerms(JsonObject root, String field, NfpSelectionStage stage) {
        JsonElement element = root.get(field);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty())
            throw new IllegalArgumentException(field + " 必须包含至少一个评分项。");
        JsonArray values = element.getAsJsonArray();
        if (values.size() > MAX_TERMS_PER_STAGE) throw new IllegalArgumentException(field + " 评分项过多。");
        List<Term> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (JsonElement entry : values) {
            if (!entry.isJsonObject()) throw new IllegalArgumentException(field + " 包含非对象评分项。");
            JsonObject term = entry.getAsJsonObject(); String feature = string(term, "feature"); String transform = string(term, "transform");
            if (!FEATURES.get(stage).contains(feature)) throw new IllegalArgumentException("不允许的 " + stage + " 特征: " + feature);
            if (!TRANSFORMS.contains(transform)) throw new IllegalArgumentException("不允许的变换: " + transform);
            if (!seen.add(feature + "|" + transform)) throw new IllegalArgumentException("重复评分项: " + feature);
            result.add(new Term(feature, transform, finiteRange(number(term, "weight", Double.NaN), -MAX_ABS_WEIGHT, MAX_ABS_WEIGHT, "weight"),
                    finiteRange(number(term, "threshold", 0.0), 0.0, 1.0, "threshold")));
        }
        return List.copyOf(result);
    }

    private static Map<NfpSelectionStage, Set<String>> allowedFeatures() {
        Map<NfpSelectionStage, Set<String>> result = new EnumMap<>(NfpSelectionStage.class);
        result.put(NfpSelectionStage.PLACEMENT, Set.of("normalizedSBox", "sArea", "combinedFillRate", "cavityInsertion", "boxExpansionRatio", "contactRatio", "minBoundaryDistanceRatio", "movingItemSmall", "nextRotationCountRatio"));
        result.put(NfpSelectionStage.ROOT_BEAM, Set.of("normalizedSBox", "sArea", "combinedFillRate", "cavityInsertion", "boxExpansionRatio", "contactRatio", "minBoundaryDistanceRatio", "movingItemSmall", "nextRotationCountRatio", "fillRate", "fillRateGainFromRoot", "memberCountRatio", "cavityInsertionRatio", "smallItemUsageRatio", "smallRectangleUsageRatio", "boxAreaToPlateArea", "longSideToPlateLongSide", "shortSideToPlateShortSide", "sourceBoxSavingRatio", "remainingItemRatio", "canSwapSides", "aspectRatio"));
        result.put(NfpSelectionStage.GLOBAL_PLAN, Set.of("coveredItemRatio", "coveredActualAreaRatio", "selectedBlockCountRatio", "meanBlockFillRate", "minimumBlockFillRate", "totalBoxSavingRatio", "totalCavityInsertionRatio", "remainingSmallRectangleRatio", "predictedSingleItemRatio", "predictedSingleAreaRatio", "criticalRootCoverageRatio"));
        return result;
    }
    private static String string(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString().strip() : ""; }
    private static boolean booleanValue(JsonObject value, String key, boolean fallback) { return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsBoolean() : fallback; }
    private static double number(JsonObject value, String key, double fallback) { return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsDouble() : fallback; }
    private static int integerRange(JsonObject value, String key, int min, int max) { double raw = number(value,key,0); if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < min || raw > max) throw new IllegalArgumentException(key + " 超出范围。"); return (int) raw; }
    private static int allowedInteger(JsonObject value, String key, int fallback, Set<Integer> allowed) {
        if (!value.has(key)) return fallback;
        int parsed = integerRange(value, key, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (!allowed.contains(parsed)) throw new IllegalArgumentException(key + " 必须属于 " + allowed + "。");
        return parsed;
    }
    private static double finiteRange(double value, double min, double max, String name) { if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(name + " 必须是范围内的有限数。"); return value; }
    private static double clamp(double value) { return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0; }
    private static String limited(String text) { String value = text == null ? "" : text.replaceAll("\\s+", " ").strip(); return value.length() > 500 ? value.substring(0,500) : value; }
    private static String sha256(String input) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value)); return result.toString(); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
}
