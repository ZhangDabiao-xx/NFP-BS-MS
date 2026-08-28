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
    private static final Path OUTPUT_DIRECTORY = Path.of("data", "NFPresult6");
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
     * 3) 每个搜索层把 A+B、A+C、A+D 等“单次拼接”分别作为后继状态，按填充率保留 w 个状态；
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
     * 搜索状态由当前仍未合并的 Block 集合表示。每个搜索层对每个主块遍历其他单件工件，
     * 生成一次 A+B、A+C、A+D 等后继状态，然后只保留填充率最高的 w 个不同状态进入下一层。
     * 每个后继只应用一次合并，下一层再继续扩展，因此不会把一轮中多个互相竞争的局部决定同时提交。
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
        // 同一搜索层的不同 beam 分支可能重复遇到相同的“固定几何 + 新工件 + 旋转”输入；
        // 缓存贯穿整个案例，而不是每个状态重新建立，以减少复合 NFP 的重复计算。
        Map<String, PolygonStitcher.StitchingResult> nfpCache = new HashMap<>();

        while (!beam.isEmpty()) {
            List<StitchSearchState> successors = new ArrayList<>();

            for (StitchSearchState state : beam) {
                List<MergeCandidate> candidates = buildMergeCandidates(state.blocks, nfpCache);
                if (candidates.isEmpty()) {
                    continue;
                }

                // 每个候选只执行一次合并；例如同一个主块 A 的 AB、AC、AD、AE
                // 都是独立后继，最终由 selectBestSearchStates 保留其中填充率最高的 w 个分支。
                for (MergeCandidate candidate : candidates) {
                    List<Block> nextBlocks = applyMerge(state.blocks, candidate);
                    if (nextBlocks.size() < state.blocks.size()) {
                        successors.add(new StitchSearchState(nextBlocks));
                    }
                }
            }

            if (successors.isEmpty()) {
                break;
            }

            beam = selectBestSearchStates(successors, beamWidth);
        }

        // beam 保留的是最后一个仍能继续扩展或已经无法扩展的搜索层，
        // 返回最终层而不是历史中间层，确保所有“仍能提高填充率”的分支都已尝试完毕。
        return beam.isEmpty() ? initialState.blocks : beam.get(0).blocks;
    }

    /**
     * 为一个搜索状态生成所有合法的“主块 + 单件物品”候选。
     *
     * 功能说明：该方法沿用原有 NFP 候选生成与合法性检查规则；每个“主块 + 单件工件”只返回
     * 一个最优角度/最优位置，集束宽度由外层 beamWidth 控制，不在这里重复保留多个位置。
     * BackFrontPriority、旋转、重叠和板材尺寸约束保持不变。
     */
    private static List<MergeCandidate> buildMergeCandidates(
            List<Block> activeBlocks,
            Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        List<MergeCandidate> candidates = new ArrayList<>();
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
                Block candidateBlock = tryAddItem(baseBlock, item, nfpCache);
                if (candidateBlock != null) {
                    candidates.add(new MergeCandidate(
                            baseBlockIndex,
                            itemBlockIndex,
                            candidateBlock));
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
     * 将一个单次拼接候选应用到当前 Block 集合。
     *
     * 修改理由：本次 beam search 每个后继状态只提交一个 A+B，下一层再继续拼接；
     * 因此不再一次提交多个互不冲突的局部候选。
     */
    private static List<Block> applyMerge(List<Block> activeBlocks, MergeCandidate candidate) {
        List<Block> nextBlocks = new ArrayList<>();
        nextBlocks.add(candidate.block);
        for (int blockIndex = 0; blockIndex < activeBlocks.size(); blockIndex++) {
            if (blockIndex != candidate.baseBlockIndex
                    && blockIndex != candidate.itemBlockIndex) {
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

    /**
     * 生成包含全部并集轮廓坐标的 Block 签名，使 beam search 能区分不同孔洞和放置方案。
     *
     * 修改理由：只签名外轮廓会把两个内部凹槽不同的 Block 错误去重，之后的 NFP
     * 就可能丢失可插入小工件的分支。
     */
    private static String blockGeometrySignature(Block block) {
        StringBuilder signature = new StringBuilder(block.id);
        for (List<Point> contour : block.unionContours) {
            signature.append('|');
            for (Point point : contour) {
                signature.append(':')
                        .append(Math.round(point.x * 1_000.0))
                        .append(',')
                        .append(Math.round(point.y * 1_000.0));
            }
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
     * PolygonStitcher 会对所有允许角度和 NFP 外/孔洞轮廓进行评分，但只返回一个全局最优位置。
     * 这里再执行 Block 级别的旋转、重叠和板材尺寸校验，最终每个“主块 + 单件工件”只产生一个后继。
     */
    private static Block tryAddItem(Block block,
                                    PolygonItem item,
                                    Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        if (!block.canStitchWith(item)) {
            return null;
        }

        List<Integer> relativeRotations = block.relativeRotationsFor(item);
        List<List<Point>> fixedPolygons = block.placedPolygons();
        String cacheKey = stitchInputSignature(fixedPolygons, block.areaSum, block.boxArea,
                item.points, item.area, relativeRotations);
        PolygonStitcher.StitchingResult nfpResult = nfpCache.get(cacheKey);
        if (nfpResult == null) {
            nfpResult = PolygonStitcher.findBestStitchForFixedPolygons(
                    fixedPolygons,
                    block.areaSum,
                    block.boxArea,
                    item.points,
                    item.area,
                    relativeRotations);
            nfpCache.put(cacheKey, nfpResult);
        }

        if (!nfpResult.stitched || nfpResult.bestCandidate == null) {
            return null;
        }

        PolygonStitcher.StitchingCandidate candidate = nfpResult.bestCandidate;
        List<Integer> nextRotations = block.validRotationsAfter(item, candidate.movingRotationDegrees);
        if (nextRotations.isEmpty() || block.hasPositiveOverlapWith(candidate.translatedPolygonB)) {
            return null;
        }

        // 只要求本次拼接真实提高填充率；不设置 0.85 之类的绝对门槛，
        // 避免大凹块第一次只能得到较低填充率时被提前截断，后续小件也就没有机会进入凹槽。
        if (candidate.fillRateGain <= PolygonStitcher.SCORE_EPS
                || candidate.combinedFillRate <= block.fillRate + PolygonStitcher.SCORE_EPS) {
            return null;
        }

        List<Point> nextCoordinates = combinePolygons(block.combinedCoordinates, candidate.translatedPolygonB);
        // 在创建新 Block 前过滤尺寸不合格候选，避免为无效候选执行并集边界计算。
        if (!fitsSecondStagePackingBounds(nextCoordinates, nextRotations)) {
            return null;
        }

        Block nextBlock = block.withAdditionalItem(item, candidate);
        if (nextBlock.unionComponentCount != 1) {
            // 二次校验防止布尔并集的精度差异让不连通块进入后续 NFP。
            return null;
        }
        return nextBlock;
    }

    /** 以全部固定工件的几何坐标构造 NFP 缓存键，确保不同内部孔洞的 Block 不会错误复用结果。 */
    private static String stitchInputSignature(List<List<Point>> basePolygons,
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
        for (List<Point> basePolygon : basePolygons) {
            appendPolygonSignature(signature, basePolygon);
            signature.append('|');
        }
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
     * baseBlockIndex 和 itemBlockIndex 指向所属 StitchSearchState 的 blocks；
     * 每条边只表示一次“主块 + 单件工件”的拼接后继。
     */
    private static final class MergeCandidate {
        private final int baseBlockIndex;
        private final int itemBlockIndex;
        private final Block block;

        private MergeCandidate(int baseBlockIndex,
                               int itemBlockIndex,
                               Block block) {
            this.baseBlockIndex = baseBlockIndex;
            this.itemBlockIndex = itemBlockIndex;
            this.block = block;
        }
    }

}
