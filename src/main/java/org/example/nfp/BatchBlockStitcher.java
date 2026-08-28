package org.example.nfp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BatchBlockStitcher {

    private static final Path INPUT_DIRECTORY = Path.of("data", "inputData");
    private static final Path OUTPUT_DIRECTORY = Path.of("data", "NFPresult5");
    private static final Gson GSON = new Gson();

    // 组合块外接矩形长边超过板材长度时无法进入第二阶段排样，因此这类候选不保留。
    private static final double MAX_PACKABLE_BLOCK_LENGTH = 2440.0;
    // 不允许旋转的组合块会按当前方向进入第二阶段，bbox 的 Y 向宽度不能超过板材宽度。
    private static final double MAX_FIXED_ORIENTATION_BLOCK_WIDTH = 1220.0;

    // NFP 集束搜索默认保留的状态数量。命令行第三个参数仍然可以覆盖该值。
    // 修改理由：第三个参数原来控制每轮贪心合并数量，现在改为控制搜索宽度；保留参数位置可以兼容原有启动方式。
    private static final int DEFAULT_BEAM_WIDTH = 5;
    // 每个主块和单件工件保留多个合法位置，避免 NFP 的单个局部最优位置提前截断 beam search。
    private static final int TOP_STITCH_CANDIDATE_COUNT = 2;

    public static void main(String[] args) throws IOException {
        Path inputDirectory = args.length > 0 ? Path.of(args[0]) : INPUT_DIRECTORY;
        Path outputDirectory = args.length > 1 ? Path.of(args[1]) : OUTPUT_DIRECTORY;
        int beamWidth = args.length > 2
                ? parsePositiveInt(args[2], DEFAULT_BEAM_WIDTH)
                : DEFAULT_BEAM_WIDTH;
        processDirectory(inputDirectory, outputDirectory, beamWidth);
    }

    public static void processDirectory(Path inputDirectory, Path outputDirectory) throws IOException {
        processDirectory(inputDirectory, outputDirectory, DEFAULT_BEAM_WIDTH);
    }

    public static void processDirectory(Path inputDirectory, Path outputDirectory, int beamWidth) throws IOException {
        Files.createDirectories(outputDirectory);
        List<Path> inputFiles;
        try (var stream = Files.list(inputDirectory)) {
            inputFiles = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        for (Path inputFile : inputFiles) {
            List<PolygonItem> items = readItems(inputFile);
            // 仅统计第一阶段组块搜索耗时，避免文件写入时间干扰每个案例的求解时间判断。
            long solveStartNanos = System.nanoTime();
            List<Block> blocks = buildBlocks(items, beamWidth);
            long solveElapsedNanos = System.nanoTime() - solveStartNanos;
            Path outputFile = outputDirectory.resolve(replaceExtension(inputFile.getFileName().toString(), ".txt"));
            writeBlocks(outputFile, blocks);
            System.out.printf(Locale.ROOT,
                    "%s -> %s, blocks=%d, solveTime=%.3f ms%n",
                    inputFile.getFileName(),
                    outputFile,
                    blocks.size(),
                    nanosToMillis(solveElapsedNanos));
        }
    }

    public static List<PolygonItem> readItems(Path inputFile) throws IOException {
        String json = Files.readString(inputFile, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray items = root.getAsJsonArray("items");
        List<PolygonItem> result = new ArrayList<>();
        if (items == null) {
            return result;
        }

        for (JsonElement itemElement : items) {
            JsonObject itemObject = itemElement.getAsJsonObject();
            String id = itemObject.get("id").getAsString();
            boolean backFrontPriority = itemObject.get("BackFrontPriority").getAsBoolean();
            Point centerPoint = readPoint(itemObject.getAsJsonArray("centPt"));
            List<Point> points = readPoints(itemObject.getAsJsonArray("points"));
            List<Integer> rotate = readRotations(itemObject.getAsJsonArray("rotate"));
            boolean smallItem = itemObject.get("smallItem").getAsBoolean();
            result.add(new PolygonItem(id, backFrontPriority, centerPoint, points, rotate, smallItem));
        }
        return result;
    }

    /**
     * 第一阶段组块生成入口。
     *
     * 新策略把所有需要拼接的非近矩形物品放入 NFP 集束搜索：
     * 1) 每个搜索状态遍历当前块与所有仍为单件的候选物品，且只允许 BackFrontPriority、旋转约束兼容的组合进入 NFP；
     * 2) smallItem=true 且几何上确认为矩形的物品只能作为被选择的单件候选，不能作为主拼接块；
     *    smallItem=true 的四角梯形等多边形仍可作为主拼接块参与搜索；
     * 3) 每轮对互不冲突的候选集合进行小规模集束搜索，并保留多个拼接后的状态；
     * 4) 只有拼接后填充率提高且组合块长边不超过 2440 的候选才保留；达到 98% 的块不再继续扩展。
     *
     * 修改理由：旧策略每轮只提交一组按当前局部指标排序得到的贪心结果；
     * 新策略保留多个候选状态，使后续轮次仍有机会回到当前轮次的次优组合，从而降低局部最优风险。
     */
    public static List<Block> buildBlocks(List<PolygonItem> items) {
        return buildBlocks(items, DEFAULT_BEAM_WIDTH);
    }

    public static List<Block> buildBlocks(List<PolygonItem> items, int beamWidth) {
        int normalizedBeamWidth = Math.max(1, beamWidth);
        List<Block> finalBlocks = new ArrayList<>();
        List<Block> activeBlocks = new ArrayList<>();
        List<PolygonItem> orderedItems = orderItemsByFillRateDescending(items);

        // 近矩形的大件已经适合第二阶段矩形排样，不进入第一阶段遍历池，避免制造无收益的大块。
        for (PolygonItem item : orderedItems) {
            if (shouldKeepAsSingleBlock(item)) {
                finalBlocks.add(Block.fromSingle(item));
            } else {
                activeBlocks.add(Block.fromSingle(item));
            }
        }

        finalBlocks.addAll(stitchByBeamSearch(activeBlocks, normalizedBeamWidth));
        return finalBlocks;
    }

    /**
     * 按初始化阶段得到的填充率降序排列工件。
     *
     * 功能说明：保持整体工件顺序稳定，同时让填充率信息在组块入口处集中完成；
     * NFP 候选生成时会再按升序选择主块，以优先处理填充率较低的工件。
     */
    private static List<PolygonItem> orderItemsByFillRateDescending(List<PolygonItem> items) {
        List<PolygonItem> orderedItems = new ArrayList<>(items);
        orderedItems.sort((left, right) -> {
            if (Math.abs(left.fillRate - right.fillRate) > PolygonStitcher.SCORE_EPS) {
                return Double.compare(right.fillRate, left.fillRate);
            }
            return left.id.compareTo(right.id);
        });
        return orderedItems;
    }

    /**
     * 使用 NFP 专用集束搜索完成组块。
     *
     * 搜索状态由当前仍未合并的 Block 集合表示。每个搜索层先生成当前状态的全部合法候选，
     * 再对互不冲突的候选组合进行一次小规模集束搜索，最后保留多个状态进入下一层。
     * 这样仍然保持原来“一轮可以合并多个互不冲突候选”的行为，但不会在当前轮只提交一个贪心结果。
     *
     * 修改理由：原来的 selectTopNonOverlappingCandidates 只根据当前轮的局部收益做一次排序并立即提交，
     * 当两个候选竞争同一个物品时，较高的当前收益可能导致后续整体收益更差。集束搜索保留多个分支，
     * 让后续拼接结果参与当前选择，从而降低局部最优风险。
     */
    private static List<Block> stitchByBeamSearch(List<Block> initialBlocks, int beamWidth) {
        if (initialBlocks.size() < 2) {
            return new ArrayList<>(initialBlocks);
        }

        StitchSearchState initialState = new StitchSearchState(initialBlocks);
        List<StitchSearchState> beam = new ArrayList<>();
        beam.add(initialState);
        StitchSearchState bestState = initialState;

        while (!beam.isEmpty()) {
            List<StitchSearchState> successors = new ArrayList<>();

            for (StitchSearchState state : beam) {
                List<MergeCandidate> candidates = buildMergeCandidates(state.blocks);
                if (candidates.isEmpty()) {
                    continue;
                }

                // 每轮允许提交的互不冲突候选数量沿用原有 count 的上限含义，
                // 但不再把该数量当作唯一结果，而是由 beamWidth 决定保留多少搜索分支。
                int maxMergesPerRound = Math.max(1, Math.min(beamWidth, state.blocks.size() / 2));
                List<MatchingState> matchingStates = buildMatchingBeam(
                        candidates,
                        state.blocks.size(),
                        maxMergesPerRound,
                        beamWidth);

                for (MatchingState matchingState : matchingStates) {
                    List<Block> nextBlocks = applyMerges(state.blocks, matchingState.selectedCandidates);
                    if (nextBlocks.size() >= state.blocks.size()) {
                        // 防御性检查：没有实际合并的状态不能进入下一搜索层。
                        continue;
                    }

                    successors.add(new StitchSearchState(nextBlocks));
                }
            }

            if (successors.isEmpty()) {
                break;
            }

            beam = selectBestSearchStates(successors, beamWidth);
            for (StitchSearchState state : beam) {
                if (compareSearchStates(state, bestState) < 0) {
                    bestState = state;
                }
            }
        }

        return bestState.blocks;
    }

    /**
     * 为一个搜索状态生成所有合法的“主块 + 单件物品”候选。
     *
     * 功能说明：该方法沿用原有 NFP 候选生成与合法性检查规则；本次修改只改变候选如何被搜索和提交，
     * 不改变 BackFrontPriority、旋转、重叠和板材尺寸约束。
     */
    private static List<MergeCandidate> buildMergeCandidates(List<Block> activeBlocks) {
        List<MergeCandidate> candidates = new ArrayList<>();
        // 同一状态内常有几何完全相同但 ID 不同的工件；缓存相同几何输入的 NFP，避免重复求解。
        Map<String, PolygonStitcher.StitchingResult> nfpCache = new HashMap<>();
        // 基准块按填充率升序处理，低填充率块优先寻找可以改善自身填充率的拼接对象。
        List<Integer> baseBlockIndexes = orderedBaseBlockIndexes(activeBlocks);
        for (Integer baseBlockIndex : baseBlockIndexes) {
            Block baseBlock = activeBlocks.get(baseBlockIndex);

            for (int itemBlockIndex = 0; itemBlockIndex < activeBlocks.size(); itemBlockIndex++) {
                if (baseBlockIndex == itemBlockIndex) {
                    continue;
                }

                Block itemBlock = activeBlocks.get(itemBlockIndex);
                if (itemBlock.memberCount() != 1) {
                    continue;
                }

                PolygonItem item = itemBlock.placements.get(0).item;
                List<CandidateBlock> candidateBlocks = tryAddItem(baseBlock, item, nfpCache);
                for (CandidateBlock candidateBlock : candidateBlocks) {
                    candidates.add(new MergeCandidate(
                            baseBlockIndex,
                            itemBlockIndex,
                            candidateBlock.block,
                            candidateBlock.fillRateGain,
                            candidateBlock.combinedFillRate,
                            candidateBlock.contactLength));
                }
            }
        }
        return candidates;
    }

    /** 返回可以继续扩展的基准块索引，并按填充率从低到高排列。 */
    private static List<Integer> orderedBaseBlockIndexes(List<Block> activeBlocks) {
        List<Integer> indexes = new ArrayList<>();
        for (int blockIndex = 0; blockIndex < activeBlocks.size(); blockIndex++) {
            if (canActAsBaseBlock(activeBlocks.get(blockIndex))) {
                indexes.add(blockIndex);
            }
        }

        indexes.sort((leftIndex, rightIndex) -> {
            Block left = activeBlocks.get(leftIndex);
            Block right = activeBlocks.get(rightIndex);
            if (Math.abs(left.fillRate - right.fillRate) > PolygonStitcher.SCORE_EPS) {
                return Double.compare(left.fillRate, right.fillRate);
            }
            if (left.memberCount() != right.memberCount()) {
                return Integer.compare(right.memberCount(), left.memberCount());
            }
            return left.id.compareTo(right.id);
        });
        return indexes;
    }

    /**
     * 对当前轮的候选进行内层集束搜索。
     *
     * 一个 MatchingState 表示一组互不冲突的候选。每处理一个候选时，同时保留“跳过”和“选中”
     * 两种可能，并把中间结果截断到 beamWidth，避免枚举全部组合导致组合数指数增长。
     */
    private static List<MatchingState> buildMatchingBeam(List<MergeCandidate> candidates,
                                                          int activeBlockCount,
                                                          int maxMergesPerRound,
                                                          int beamWidth) {
        List<MergeCandidate> sortedCandidates = new ArrayList<>(candidates);
        sortedCandidates.sort(BatchBlockStitcher::compareMergeCandidates);

        List<MatchingState> partialStates = new ArrayList<>();
        partialStates.add(MatchingState.empty(activeBlockCount));

        for (MergeCandidate candidate : sortedCandidates) {
            // 复制当前状态代表“跳过该候选”，保留原状态不会被后续选中分支修改。
            List<MatchingState> expandedStates = new ArrayList<>(partialStates);
            for (MatchingState partialState : partialStates) {
                if (partialState.selectedCandidates.size() >= maxMergesPerRound
                        || partialState.conflictsWith(candidate)) {
                    continue;
                }
                expandedStates.add(partialState.add(candidate));
            }
            partialStates = selectBestMatchingStates(expandedStates, beamWidth);
        }

        List<MatchingState> result = new ArrayList<>();
        for (MatchingState state : partialStates) {
            if (!state.selectedCandidates.isEmpty()) {
                result.add(state);
            }
        }
        return result;
    }

    /** 保留外层集束搜索中评分最好的不同状态。 */
    private static List<StitchSearchState> selectBestSearchStates(List<StitchSearchState> states,
                                                                  int beamWidth) {
        states.sort(BatchBlockStitcher::compareSearchStates);
        List<StitchSearchState> selectedStates = new ArrayList<>();
        Set<String> signatures = new HashSet<>();

        for (StitchSearchState state : states) {
            String signature = searchStateSignature(state);
            if (!signatures.add(signature)) {
                continue;
            }
            selectedStates.add(state);
            if (selectedStates.size() >= beamWidth) {
                break;
            }
        }
        return selectedStates;
    }

    /** 保留内层候选组合搜索中评分最好的不同匹配。 */
    private static List<MatchingState> selectBestMatchingStates(List<MatchingState> states,
                                                                int beamWidth) {
        states.sort(BatchBlockStitcher::compareMatchingStates);
        List<MatchingState> selectedStates = new ArrayList<>();
        Set<String> signatures = new HashSet<>();

        for (MatchingState state : states) {
            String signature = matchingStateSignature(state);
            if (!signatures.add(signature)) {
                continue;
            }
            selectedStates.add(state);
            if (selectedStates.size() >= beamWidth) {
                break;
            }
        }
        return selectedStates;
    }

    /**
     * 比较外层搜索状态：优先整体填充率，其次优先当前 Block 数更少的状态。
     * 整体填充率使用所有当前 Block 的面积和除以外接矩形面积和，能够直接反映当前状态的紧凑程度。
     */
    private static int compareSearchStates(StitchSearchState left, StitchSearchState right) {
        if (Math.abs(left.overallFillRate - right.overallFillRate) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.overallFillRate, left.overallFillRate);
        }
        if (left.blocks.size() != right.blocks.size()) {
            return Integer.compare(left.blocks.size(), right.blocks.size());
        }
        return searchStateSignature(left).compareTo(searchStateSignature(right));
    }

    /** 比较一轮内的候选组合，优先保留填充率提升大且合并数量多的组合。 */
    private static int compareMatchingStates(MatchingState left, MatchingState right) {
        if (Math.abs(left.fillRateGain - right.fillRateGain) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.fillRateGain, left.fillRateGain);
        }
        if (left.selectedCandidates.size() != right.selectedCandidates.size()) {
            return Integer.compare(right.selectedCandidates.size(), left.selectedCandidates.size());
        }
        return matchingStateSignature(left).compareTo(matchingStateSignature(right));
    }

    /** 比较单个拼接候选，供内层集束搜索按高收益优先扩展。 */
    private static int compareMergeCandidates(MergeCandidate left, MergeCandidate right) {
        if (Math.abs(left.combinedFillRate - right.combinedFillRate) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.combinedFillRate, left.combinedFillRate);
        }
        if (Math.abs(left.contactLength - right.contactLength) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.contactLength, left.contactLength);
        }
        if (Math.abs(left.fillRateGain - right.fillRateGain) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.fillRateGain, left.fillRateGain);
        }
        if (left.block.memberCount() != right.block.memberCount()) {
            return Integer.compare(right.block.memberCount(), left.block.memberCount());
        }
        int idComparison = left.block.id.compareTo(right.block.id);
        if (idComparison != 0) {
            return idComparison;
        }
        if (left.baseBlockIndex != right.baseBlockIndex) {
            return Integer.compare(left.baseBlockIndex, right.baseBlockIndex);
        }
        int itemIndexComparison = Integer.compare(left.itemBlockIndex, right.itemBlockIndex);
        if (itemIndexComparison != 0) {
            return itemIndexComparison;
        }
        return blockGeometrySignature(left.block).compareTo(blockGeometrySignature(right.block));
    }

    /** 计算当前搜索状态所有 Block 的总体填充率。 */
    private static double calculateOverallFillRate(List<Block> blocks) {
        double totalArea = 0.0;
        double totalBoxArea = 0.0;
        for (Block block : blocks) {
            totalArea += block.areaSum;
            totalBoxArea += block.boxArea;
        }
        if (totalBoxArea <= PolygonStitcher.SCORE_EPS) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, totalArea / totalBoxArea));
    }

    /**
     * 将一轮中互不冲突的候选应用到当前 Block 集合。
     * 候选使用当前状态中的索引，因此先标记被消耗的旧块，再加入新的组合块。
     */
    private static List<Block> applyMerges(List<Block> activeBlocks, List<MergeCandidate> selectedCandidates) {
        boolean[] consumed = new boolean[activeBlocks.size()];
        List<Block> nextBlocks = new ArrayList<>();

        for (MergeCandidate candidate : selectedCandidates) {
            consumed[candidate.baseBlockIndex] = true;
            consumed[candidate.itemBlockIndex] = true;
            nextBlocks.add(candidate.block);
        }

        for (int blockIndex = 0; blockIndex < activeBlocks.size(); blockIndex++) {
            if (!consumed[blockIndex]) {
                nextBlocks.add(activeBlocks.get(blockIndex));
            }
        }
        return nextBlocks;
    }

    /** 通过排序后的 Block id 生成状态签名，用于去除相同 Block 集合的重复分支。 */
    private static String searchStateSignature(StitchSearchState state) {
        List<String> blockIds = new ArrayList<>();
        for (Block block : state.blocks) {
            // 仅使用工件 ID 会把同一组工件的不同几何放置误判为同一状态，导致 beam 分支被错误删除。
            blockIds.add(blockGeometrySignature(block));
        }
        blockIds.sort(String::compareTo);
        return String.join("|", blockIds);
    }

    /** 通过候选的当前状态索引生成匹配签名，用于内层集束搜索去重。 */
    private static String matchingStateSignature(MatchingState state) {
        StringBuilder signature = new StringBuilder();
        for (MergeCandidate candidate : state.selectedCandidates) {
            signature.append(candidate.baseBlockIndex)
                    .append('-')
                    .append(candidate.itemBlockIndex)
                    .append('-')
                    .append(blockGeometrySignature(candidate.block))
                    .append('|');
        }
        return signature.toString();
    }

    /** 生成包含轮廓坐标的 Block 签名，使 beam search 能区分同一组工件的不同放置方案。 */
    private static String blockGeometrySignature(Block block) {
        StringBuilder signature = new StringBuilder(block.id);
        for (Point point : block.outline) {
            signature.append(':')
                    .append(Math.round(point.x * 1_000.0))
                    .append(',')
                    .append(Math.round(point.y * 1_000.0));
        }
        return signature.toString();
    }

    /** 合并已有 Block 坐标和新工件坐标，仅用于创建 Block 前的尺寸预检查。 */
    private static List<Point> combinePolygons(List<Point> first, List<Point> second) {
        List<Point> combined = new ArrayList<>(first.size() + second.size());
        for (Point point : first) {
            combined.add(new Point(point.x, point.y));
        }
        for (Point point : second) {
            combined.add(new Point(point.x, point.y));
        }
        return combined;
    }

    private static boolean shouldKeepAsSingleBlock(PolygonItem item) {
        // 保留原职责：非 smallItem 且接近矩形的物品交给第二阶段，第一阶段不为它们额外制造复杂组合。
        return item.shouldStaySingle();
    }

    private static boolean canActAsBaseBlock(Block block) {
        // 主拼接块必须还能容纳新物品，并且不能是 smallItem 矩形-only；
        // 同时必须满足第二阶段可排样尺寸和填充率上限，避免继续扩展已经足够紧凑的块。
        return block.memberCount() < Block.MAX_MEMBER_COUNT
                && !isSmallRectangleOnlyBlock(block)
                && block.unionComponentCount == 1
                && block.fillRate < PolygonStitcher.TARGET_FILL_RATE - PolygonStitcher.SCORE_EPS
                && fitsSecondStagePackingBounds(block);
    }

    private static boolean isSmallRectangleOnlyBlock(Block block) {
        // 只有块内所有成员都是 smallItem=true 且几何上为矩形时，才禁止其作为主拼接块；
        // 任意非矩形多边形成员都会让该块重新具备 baseBlock 资格。
        for (Block.ItemPlacement placement : block.placements) {
            if (!isSmallRectangleItem(placement.item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSmallRectangleItem(PolygonItem item) {
        // smallItem 只是业务标记，必须叠加几何矩形判定，避免四角梯形被误当作小矩形排除。
        return item.smallItem && item.rectangular;
    }

    private static boolean fitsSecondStagePackingBounds(Block block) {
        return fitsSecondStagePackingBounds(block.combinedCoordinates, block.rotate);
    }

    /**
     * 在创建新 Block 之前检查候选的板材尺寸。
     *
     * 修改理由：原逻辑先构造 Block，再检查尺寸；Block 构造会重新计算完整并集边界，
     * 对最终必然被尺寸过滤的候选造成不必要的几何开销。
     */
    private static boolean fitsSecondStagePackingBounds(List<Point> combinedCoordinates,
                                                         List<Integer> rotations) {
        if (combinedCoordinates.isEmpty()) {
            return true;
        }
        BBox box = Geometry.polygonBBox(combinedCoordinates);
        double length = box.maxX - box.minX;
        double width = box.maxY - box.minY;
        double longSide = Math.max(length, width);
        if (longSide > MAX_PACKABLE_BLOCK_LENGTH + PolygonStitcher.SCORE_EPS) {
            return false;
        }
        // 不允许 90°/270° 的块在 BeamSearch 中不能交换长宽；输出时 Width 对应 bbox 的 Y 向宽度。
        // 因此这类块必须额外满足 width<=1220，否则即使长边<=2440 也无法放入 2440×1220 板材。
        return canRotateInSecondStage(rotations)
                || width <= MAX_FIXED_ORIENTATION_BLOCK_WIDTH + PolygonStitcher.SCORE_EPS;
    }

    private static boolean canRotateInSecondStage(List<Integer> rotations) {
        // 第二阶段矩形排样只区分原方向和长宽交换；允许 90° 或 270° 都表示该组合块可旋转。
        return rotations.contains(90) || rotations.contains(270);
    }

    /**
     * NFP 拼接的唯一入口。
     *
     * 现在只保留常规 NFP 路径：PolygonStitcher 内部生成候选并统一使用填充率提升评分，
     * 不再使用旧 score2 作为拼接接受条件，避免多个评分体系互相影响。
     */
    private static List<CandidateBlock> tryAddItem(Block block,
                                                    PolygonItem item,
                                                    Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        List<CandidateBlock> candidateBlocks = new ArrayList<>();
        if (!block.canStitchWith(item)) {
            return candidateBlocks;
        }
        if (block.unionComponentCount != 1) {
            // 多连通分量没有单一外轮廓，不能继续调用只接受单多边形的 NFP 接口。
            return candidateBlocks;
        }

        List<Integer> relativeRotations = block.relativeRotationsFor(item);
        String cacheKey = stitchInputSignature(block.outline, block.areaSum, block.boxArea,
                item.points, item.area, relativeRotations);
        PolygonStitcher.StitchingResult nfpResult = nfpCache.get(cacheKey);
        if (nfpResult == null) {
            nfpResult = PolygonStitcher.findBestStitch(
                    block.outline,
                    block.areaSum,
                    block.boxArea,
                    item.points,
                    item.area,
                    relativeRotations);
            nfpCache.put(cacheKey, nfpResult);
        }

        if (!nfpResult.stitched || nfpResult.bestCandidate == null) {
            return candidateBlocks;
        }

        // 同一对工件保留多个不同位置；这些位置已经在 PolygonStitcher 中通过 NFP、接触和并集校验。
        for (PolygonStitcher.StitchingCandidate candidate : nfpResult.topCandidates(TOP_STITCH_CANDIDATE_COUNT)) {
            List<Integer> nextRotations = block.validRotationsAfter(item, candidate.movingRotationDegrees);
            if (nextRotations.isEmpty()) {
                continue;
            }
            if (block.hasPositiveOverlapWith(candidate.translatedPolygonB)) {
                continue;
            }

            // 新评分要求：拼接后填充率必须高于当前主块，且达到最低绝对质量。
            if (candidate.fillRateGain <= PolygonStitcher.SCORE_EPS
                    || candidate.combinedFillRate <= block.fillRate + PolygonStitcher.SCORE_EPS
                    || candidate.combinedFillRate < PolygonStitcher.MIN_COMBINED_FILL_RATE
                    - PolygonStitcher.SCORE_EPS) {
                continue;
            }

            List<Point> nextCoordinates = combinePolygons(block.combinedCoordinates, candidate.translatedPolygonB);
            // 在创建新 Block 前过滤尺寸不合格候选，避免为无效候选执行并集边界计算。
            if (!fitsSecondStagePackingBounds(nextCoordinates, nextRotations)) {
                continue;
            }

            Block nextBlock = block.withAdditionalItem(item, candidate);
            if (nextBlock.unionComponentCount != 1) {
                // 二次校验防止布尔并集的精度差异让不连通块进入后续 NFP。
                continue;
            }
            candidateBlocks.add(new CandidateBlock(nextBlock, candidate.fillRateGain,
                    candidate.combinedFillRate, candidate.contactLength));
        }
        return candidateBlocks;
    }

    /** 以几何坐标而非工件 ID 构造 NFP 缓存键，重复形状可以复用同一组候选位置。 */
    private static String stitchInputSignature(List<Point> basePolygon,
                                               double baseArea,
                                               double baseBoxArea,
                                               List<Point> itemPolygon,
                                               double itemArea,
                                               List<Integer> rotations) {
        StringBuilder signature = new StringBuilder();
        signature.append(baseArea).append('|')
                .append(baseBoxArea).append('|')
                .append(itemArea).append('|')
                .append(rotations).append('|');
        appendPolygonSignature(signature, basePolygon);
        signature.append('|');
        appendPolygonSignature(signature, itemPolygon);
        return signature.toString();
    }

    private static void appendPolygonSignature(StringBuilder signature, List<Point> polygon) {
        for (Point point : polygon) {
            signature.append(Math.round(point.x * 1_000.0))
                    .append(',')
                    .append(Math.round(point.y * 1_000.0))
                    .append(';');
        }
    }

    private static void writeBlocks(Path outputFile, List<Block> blocks) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write("blockCount=" + blocks.size());
            writer.newLine();
            for (int i = 0; i < blocks.size(); i++) {
                writeBlock(writer, i + 1, blocks.get(i));
            }
        }
    }

    private static void writeBlock(BufferedWriter writer, int index, Block block) throws IOException {
        writer.newLine();
        writer.write("block " + index);
        writer.newLine();
        writer.write("id=" + block.id);
        writer.newLine();
        writer.write("BackFrontPriority=" + block.backFrontPriority);
        writer.newLine();
        writer.write("rotate=" + GSON.toJson(block.rotate));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "score2=%.6f", block.score2));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "fillRate=%.6f", block.fillRate));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "boxArea=%.6f", block.boxArea));
        writer.newLine();
        writer.write("unionComponentCount=" + block.unionComponentCount);
        writer.newLine();
        writer.write("outline=" + pointsToJson(block.outline));
        writer.newLine();
        writer.write("unionContours=" + polygonsToJson(block.unionContours));
        writer.newLine();
        writer.write("combinedCoordinates=" + pointsToJson(block.combinedCoordinates));
        writer.newLine();
        writer.write("items=");
        writer.newLine();
        for (Block.ItemPlacement placement : block.placements) {
            writePlacement(writer, placement);
        }
    }

    private static void writePlacement(BufferedWriter writer, Block.ItemPlacement placement) throws IOException {
        PolygonItem item = placement.item;
        writer.write("  - id=" + item.id);
        writer.newLine();
        writer.write("    BackFrontPriority=" + item.backFrontPriority);
        writer.newLine();
        writer.write("    smallItem=" + item.smallItem);
        writer.newLine();
        writer.write("    rotate=" + GSON.toJson(item.rotate));
        writer.newLine();
        writer.write("    selectedRelativeRotation=" + placement.selectedRelativeRotation);
        writer.newLine();
        writer.write("    sourceType=" + placement.sourceType);
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "    candidateScore2=%.6f", placement.candidateScore2));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "    candidateContactLength=%.6f", placement.candidateContactLength));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "    candidateMinBoundaryDistance=%.6f",
                placement.candidateMinBoundaryDistance));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "    candidateCombinedFillRate=%.6f",
                placement.candidateCombinedFillRate));
        writer.newLine();
        writer.write("    centPt=" + pointToJson(item.centerPoint));
        writer.newLine();
        writer.write("    translation=" + pointToJson(placement.translation));
        writer.newLine();
        writer.write("    originalPoints=" + pointsToJson(item.points));
        writer.newLine();
        writer.write("    placedPoints=" + pointsToJson(placement.placedPoints));
        writer.newLine();
    }

    private static Point readPoint(JsonArray pointArray) {
        if (pointArray == null || pointArray.size() < 2) {
            return new Point(0, 0);
        }
        return new Point(pointArray.get(0).getAsDouble(), pointArray.get(1).getAsDouble());
    }

    private static List<Point> readPoints(JsonArray pointsArray) {
        List<Point> points = new ArrayList<>();
        if (pointsArray == null) {
            return points;
        }
        for (JsonElement pointElement : pointsArray) {
            points.add(readPoint(pointElement.getAsJsonArray()));
        }
        return points;
    }

    private static List<Integer> readRotations(JsonArray rotationsArray) {
        List<Integer> rotations = new ArrayList<>();
        if (rotationsArray == null) {
            rotations.add(0);
            return rotations;
        }
        for (JsonElement rotationElement : rotationsArray) {
            rotations.add(rotationElement.getAsInt());
        }
        return rotations;
    }

    private static String pointsToJson(List<Point> points) {
        List<List<Double>> values = new ArrayList<>(points.size());
        for (Point point : points) {
            values.add(List.of(round(point.x), round(point.y)));
        }
        return GSON.toJson(values);
    }

    /** 将多个轮廓序列化为二维点数组，供可视化完整恢复 Block 的并集边界。 */
    private static String polygonsToJson(List<List<Point>> polygons) {
        List<List<List<Double>>> values = new ArrayList<>(polygons.size());
        for (List<Point> polygon : polygons) {
            List<List<Double>> polygonValues = new ArrayList<>(polygon.size());
            for (Point point : polygon) {
                polygonValues.add(List.of(round(point.x), round(point.y)));
            }
            values.add(polygonValues);
        }
        return GSON.toJson(values);
    }

    private static String pointToJson(Point point) {
        return GSON.toJson(List.of(round(point.x), round(point.y)));
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private static double nanosToMillis(long nanos) {
        // 求解耗时使用毫秒展示，保留 3 位小数便于比较不同案例的第一阶段搜索成本。
        return nanos / 1_000_000.0;
    }

    private static String replaceExtension(String fileName, String extension) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return fileName + extension;
        }
        return fileName.substring(0, dotIndex) + extension;
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsedValue = Integer.parseInt(value);
            return parsedValue > 0 ? parsedValue : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class CandidateBlock {
        private final Block block;
        // 本次拼接相对于主块的填充率提升量。
        private final double fillRateGain;
        // 拼接后的组合块填充率，用于判断是否达到终止阈值。
        private final double combinedFillRate;
        // 接触长度作为评分平局时的几何质量指标。
        private final double contactLength;

        private CandidateBlock(Block block,
                               double fillRateGain,
                               double combinedFillRate,
                               double contactLength) {
            this.block = block;
            this.fillRateGain = fillRateGain;
            this.combinedFillRate = combinedFillRate;
            this.contactLength = contactLength;
        }
    }

    /**
     * 外层 NFP 搜索状态。
     *
     * blocks 完整描述了当前状态中所有尚未继续合并的 Block；
     * overallFillRate 用于比较不同拼接路径的总体紧凑程度。
     */
    private static final class StitchSearchState {
        private final List<Block> blocks;
        private final double overallFillRate;

        private StitchSearchState(List<Block> blocks) {
            this.blocks = new ArrayList<>(blocks);
            this.overallFillRate = calculateOverallFillRate(this.blocks);
        }
    }

    /**
     * 当前搜索状态中的一条合法合并边。
     *
     * baseBlockIndex 和 itemBlockIndex 指向所属 StitchSearchState 的 blocks，
     * 只有索引不冲突的 MergeCandidate 才能在同一轮一起应用。
     */
    private static final class MergeCandidate {
        private final int baseBlockIndex;
        private final int itemBlockIndex;
        private final Block block;
        // 当前合并动作带来的填充率提升量，不包含此前搜索层的收益。
        private final double fillRateGain;
        private final double combinedFillRate;
        private final double contactLength;

        private MergeCandidate(int baseBlockIndex,
                               int itemBlockIndex,
                               Block block,
                               double fillRateGain,
                               double combinedFillRate,
                               double contactLength) {
            this.baseBlockIndex = baseBlockIndex;
            this.itemBlockIndex = itemBlockIndex;
            this.block = block;
            this.fillRateGain = fillRateGain;
            this.combinedFillRate = combinedFillRate;
            this.contactLength = contactLength;
        }
    }

    /**
     * 一轮内互不冲突候选的中间状态。
     *
     * occupied 记录当前轮已经使用的 Block 索引，避免同一个物品同时出现在两个拼接结果中。
     */
    private static final class MatchingState {
        private final List<MergeCandidate> selectedCandidates;
        private final boolean[] occupied;
        private final double fillRateGain;

        private MatchingState(List<MergeCandidate> selectedCandidates,
                              boolean[] occupied,
                              double fillRateGain) {
            this.selectedCandidates = selectedCandidates;
            this.occupied = occupied;
            this.fillRateGain = fillRateGain;
        }

        private static MatchingState empty(int activeBlockCount) {
            return new MatchingState(new ArrayList<>(), new boolean[activeBlockCount], 0.0);
        }

        private boolean conflictsWith(MergeCandidate candidate) {
            return occupied[candidate.baseBlockIndex] || occupied[candidate.itemBlockIndex];
        }

        private MatchingState add(MergeCandidate candidate) {
            boolean[] nextOccupied = occupied.clone();
            nextOccupied[candidate.baseBlockIndex] = true;
            nextOccupied[candidate.itemBlockIndex] = true;

            List<MergeCandidate> nextCandidates = new ArrayList<>(selectedCandidates);
            nextCandidates.add(candidate);
            return new MatchingState(nextCandidates, nextOccupied, fillRateGain + candidate.fillRateGain);
        }
    }
}
