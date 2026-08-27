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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BatchBlockStitcher {

    private static final Path INPUT_DIRECTORY = Path.of("data", "inputData");
    private static final Path OUTPUT_DIRECTORY = Path.of("data", "NFPresult3");
    private static final Gson GSON = new Gson();

    // 组合块外接矩形长边超过板材长度时无法进入第二阶段排样，因此这类候选不保留。
    private static final double MAX_PACKABLE_BLOCK_LENGTH = 2440.0;
    // 不允许旋转的组合块会按当前方向进入第二阶段，bbox 的 Y 向宽度不能超过板材宽度。
    private static final double MAX_FIXED_ORIENTATION_BLOCK_WIDTH = 1220.0;

    // NFP 集束搜索默认保留的状态数量。命令行第三个参数仍然可以覆盖该值。
    // 修改理由：第三个参数原来控制每轮贪心合并数量，现在改为控制搜索宽度；保留参数位置可以兼容原有启动方式。
    private static final int DEFAULT_BEAM_WIDTH = 5;

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
     * 4) 只有 score2 > 0 且组合块长边不超过 2440 的候选才保留，剩余无法被选中的物品自然作为单独块输出。
     *
     * 修改理由：旧策略每轮只提交一组按当前 score2 排序得到的贪心结果；
     * 新策略保留多个候选状态，使后续轮次仍有机会回到当前轮次的次优组合，从而降低局部最优风险。
     */
    public static List<Block> buildBlocks(List<PolygonItem> items) {
        return buildBlocks(items, DEFAULT_BEAM_WIDTH);
    }

    public static List<Block> buildBlocks(List<PolygonItem> items, int beamWidth) {
        int normalizedBeamWidth = Math.max(1, beamWidth);
        List<Block> finalBlocks = new ArrayList<>();
        List<Block> activeBlocks = new ArrayList<>();

        // 近矩形的大件已经适合第二阶段矩形排样，不进入第一阶段遍历池，避免制造无收益的大块。
        for (PolygonItem item : items) {
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
     * 使用 NFP 专用集束搜索完成组块。
     *
     * 搜索状态由当前仍未合并的 Block 集合表示。每个搜索层先生成当前状态的全部合法候选，
     * 再对互不冲突的候选组合进行一次小规模集束搜索，最后保留多个状态进入下一层。
     * 这样仍然保持原来“一轮可以合并多个互不冲突候选”的行为，但不会在当前轮只提交一个贪心结果。
     *
     * 修改理由：原来的 selectTopNonOverlappingCandidates 只根据当前轮的 score2 做一次排序并立即提交，
     * 当两个候选竞争同一个物品时，较高的当前收益可能导致后续整体收益更差。集束搜索保留多个分支，
     * 让后续拼接结果参与当前选择，从而降低局部最优风险。
     */
    private static List<Block> stitchByBeamSearch(List<Block> initialBlocks, int beamWidth) {
        if (initialBlocks.size() < 2) {
            return new ArrayList<>(initialBlocks);
        }

        StitchSearchState initialState = new StitchSearchState(initialBlocks, 0.0);
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

                    double cumulativeScore2 = state.cumulativeScore2 + matchingState.score2;
                    successors.add(new StitchSearchState(nextBlocks, cumulativeScore2));
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
     * 不改变 BackFrontPriority、旋转、重叠、score2 和板材尺寸约束。
     */
    private static List<MergeCandidate> buildMergeCandidates(List<Block> activeBlocks) {
        List<MergeCandidate> candidates = new ArrayList<>();
        for (int baseBlockIndex = 0; baseBlockIndex < activeBlocks.size(); baseBlockIndex++) {
            Block baseBlock = activeBlocks.get(baseBlockIndex);
            if (!canActAsBaseBlock(baseBlock)) {
                continue;
            }

            for (int itemBlockIndex = 0; itemBlockIndex < activeBlocks.size(); itemBlockIndex++) {
                if (baseBlockIndex == itemBlockIndex) {
                    continue;
                }

                Block itemBlock = activeBlocks.get(itemBlockIndex);
                if (itemBlock.memberCount() != 1) {
                    continue;
                }

                PolygonItem item = itemBlock.placements.get(0).item;
                CandidateBlock candidateBlock = tryAddItem(baseBlock, item);
                if (candidateBlock != null) {
                    candidates.add(new MergeCandidate(
                            baseBlockIndex,
                            itemBlockIndex,
                            candidateBlock.block,
                            candidateBlock.score2));
                }
            }
        }
        return candidates;
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
     * 比较外层搜索状态：优先累计 score2，其次优先当前 Block 数更少的状态。
     * 累计 score2 等价于初始各 Block 外接矩形面积之和减去当前面积之和，因此可以反映整体紧凑程度。
     */
    private static int compareSearchStates(StitchSearchState left, StitchSearchState right) {
        if (Math.abs(left.cumulativeScore2 - right.cumulativeScore2) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.cumulativeScore2, left.cumulativeScore2);
        }
        if (left.blocks.size() != right.blocks.size()) {
            return Integer.compare(left.blocks.size(), right.blocks.size());
        }
        return searchStateSignature(left).compareTo(searchStateSignature(right));
    }

    /** 比较一轮内的候选组合，优先保留累计收益高且合并数量多的组合。 */
    private static int compareMatchingStates(MatchingState left, MatchingState right) {
        if (Math.abs(left.score2 - right.score2) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.score2, left.score2);
        }
        if (left.selectedCandidates.size() != right.selectedCandidates.size()) {
            return Integer.compare(right.selectedCandidates.size(), left.selectedCandidates.size());
        }
        return matchingStateSignature(left).compareTo(matchingStateSignature(right));
    }

    /** 比较单个拼接候选，供内层集束搜索按高收益优先扩展。 */
    private static int compareMergeCandidates(MergeCandidate left, MergeCandidate right) {
        if (Math.abs(left.score2 - right.score2) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.score2, left.score2);
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
        return Integer.compare(left.itemBlockIndex, right.itemBlockIndex);
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
            blockIds.add(block.id);
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
                    .append('|');
        }
        return signature.toString();
    }

    private static boolean shouldKeepAsSingleBlock(PolygonItem item) {
        // 保留原职责：非 smallItem 且接近矩形的物品交给第二阶段，第一阶段不为它们额外制造复杂组合。
        return item.shouldStaySingle();
    }

    private static boolean canActAsBaseBlock(Block block) {
        // 主拼接块必须还能容纳新物品，并且不能是 smallItem 矩形-only；
        // 同时必须满足第二阶段可排样尺寸，避免继续扩展已经无法落入 2440×1220 板材的块。
        return block.memberCount() < Block.MAX_MEMBER_COUNT
                && !isSmallRectangleOnlyBlock(block)
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
        return item.smallItem && Geometry.isRectangle(item.points);
    }

    private static boolean fitsSecondStagePackingBounds(Block block) {
        if (block.combinedCoordinates.isEmpty()) {
            return true;
        }
        BBox box = Geometry.polygonBBox(block.combinedCoordinates);
        double length = box.maxX - box.minX;
        double width = box.maxY - box.minY;
        double longSide = Math.max(length, width);
        if (longSide > MAX_PACKABLE_BLOCK_LENGTH + PolygonStitcher.SCORE_EPS) {
            return false;
        }
        // 不允许 90°/270° 的块在 BeamSearch 中不能交换长宽；输出时 Width 对应 bbox 的 Y 向宽度。
        // 因此这类块必须额外满足 width<=1220，否则即使长边<=2440 也无法放入 2440×1220 板材。
        return canRotateInSecondStage(block) || width <= MAX_FIXED_ORIENTATION_BLOCK_WIDTH + PolygonStitcher.SCORE_EPS;
    }

    private static boolean canRotateInSecondStage(Block block) {
        // 第二阶段矩形排样只区分原方向和长宽交换；允许 90° 或 270° 都表示该组合块可旋转。
        return block.rotate.contains(90) || block.rotate.contains(270);
    }

    /**
     * NFP 拼接的唯一入口。
     *
     * 现在只保留常规 NFP 路径：PolygonStitcher 内部生成候选并统一使用 score2 评分，
     * 不再混入其他拼接候选，避免多个评分体系互相污染。
     */
    private static CandidateBlock tryAddItem(Block block, PolygonItem item) {
        if (!block.canStitchWith(item)) {
            return null;
        }

        PolygonStitcher.StitchingResult nfpResult = PolygonStitcher.findBestStitch(
                block.outline,
                block.areaSum,
                block.boxArea,
                item.points,
                item.area,
                block.relativeRotationsFor(item));

        if (!nfpResult.stitched || nfpResult.bestCandidate == null) {
            return null;
        }

        PolygonStitcher.StitchingCandidate bestCandidate = nfpResult.bestCandidate;
        if (block.validRotationsAfter(item, bestCandidate.movingRotationDegrees).isEmpty()) {
            return null;
        }
        if (block.hasPositiveOverlapWith(bestCandidate.translatedPolygonB)) {
            return null;
        }

        Block nextBlock = block.withAdditionalItem(item, bestCandidate);
        // 功能说明：第一阶段只保留 score2 为正的拼接，score2<=0 表示合并后外接矩形没有面积收益。
        if (nextBlock.score2 <= PolygonStitcher.SCORE_EPS) {
            return null;
        }
        // 功能说明：过滤无法进入第二阶段板材的组合块；不可旋转块还必须满足 bbox 的 Y 向宽度不超过 1220。
        if (!fitsSecondStagePackingBounds(nextBlock)) {
            return null;
        }
        return new CandidateBlock(nextBlock, bestCandidate.score2);
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
        writer.write(String.format(Locale.ROOT, "boxArea=%.6f", block.boxArea));
        writer.newLine();
        writer.write("outline=" + pointsToJson(block.outline));
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
        // 本次拼接收益：主块外接矩形面积 + 被选物品外接矩形面积 - 拼接后外接矩形面积。
        private final double score2;

        private CandidateBlock(Block block, double score2) {
            this.block = block;
            this.score2 = score2;
        }
    }

    /**
     * 外层 NFP 搜索状态。
     *
     * blocks 完整描述了当前状态中所有尚未继续合并的 Block；
     * cumulativeScore2 用于比较不同拼接路径的累计外接矩形收益。
     */
    private static final class StitchSearchState {
        private final List<Block> blocks;
        private final double cumulativeScore2;

        private StitchSearchState(List<Block> blocks, double cumulativeScore2) {
            this.blocks = new ArrayList<>(blocks);
            this.cumulativeScore2 = cumulativeScore2;
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
        // 当前合并动作带来的增量收益，不包含此前搜索层已经获得的累计收益。
        private final double score2;

        private MergeCandidate(int baseBlockIndex, int itemBlockIndex, Block block, double score2) {
            this.baseBlockIndex = baseBlockIndex;
            this.itemBlockIndex = itemBlockIndex;
            this.block = block;
            this.score2 = score2;
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
        private final double score2;

        private MatchingState(List<MergeCandidate> selectedCandidates,
                              boolean[] occupied,
                              double score2) {
            this.selectedCandidates = selectedCandidates;
            this.occupied = occupied;
            this.score2 = score2;
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
            return new MatchingState(nextCandidates, nextOccupied, score2 + candidate.score2);
        }
    }
}
