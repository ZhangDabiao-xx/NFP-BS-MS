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
    private static final Path OUTPUT_DIRECTORY = Path.of("data", "NFPresult8");
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
     * 组块过程分为两层：
     * 1) 外层依次选择一个尚未使用的根工件 A；
     * 2) 内层以 A 为固定根节点，逐层生成 AB、AC、ACG 等拼接分支，
     *    每层只保留填充率最高的 beamWidth 个分支；
     * 3) 某个分支无法继续扩展时进入终止集合，所有分支结束后再选择根 A 的最优结果；
     * 4) 选定的最终块消耗其中的工件，外层再为剩余工件选择下一个根节点。
     *
     * 修改理由：原实现把“所有当前 Block 的集合”作为 Beam 节点，并按整体填充率比较。
     * 这样 AB 和 ACG 不会作为同一个根 A 下的候选直接竞争，AB 还可能随着其他无关 Block
     * 的合并继续留在状态中。本实现把 Beam 节点改为单个根 A 的拼接方案，使搜索语义
     * 与“AB、AC、AD 中保留前 w 个，后续 ACG 淘汰 AB”的要求一致。
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
     * 以根工件为单位完成 NFP 组块。
     *
     * 该方法只负责外层的工件分配：一次确定一个根工件的最终组合块，
     * 再把该块中的工件从剩余池中移除。真正的 AB、AC、ACG 分层搜索由
     * searchBestBlockFromRoot(...) 完成。
     *
     * 修改理由：必须先完成同一个根 A 的候选竞争，再把选定块提交到全局结果；
     * 不能把不同根节点的无关合并混在一个 Beam 状态中比较。
     */
    private static List<Block> stitchByBeamSearch(List<Block> initialBlocks, int beamWidth) {
        List<PolygonItem> remainingItems = collectItems(initialBlocks);
        List<Block> result = new ArrayList<>();

        // 不同根节点和不同 Beam 分支可能重复计算相同的 NFP，缓存贯穿整个案例复用。
        Map<String, PolygonStitcher.StitchingResult> nfpCache = new HashMap<>();

        while (!remainingItems.isEmpty()) {
            PolygonItem rootItem = selectNextRootItem(remainingItems);
            Block bestBlock = searchBestBlockFromRoot(rootItem, remainingItems, beamWidth, nfpCache);
            result.add(bestBlock);

            // 选定根 A 的最终块后，块内所有工件均不能再参与其他根节点的搜索。
            Set<String> usedItemIds = collectItemIds(bestBlock);
            remainingItems.removeIf(item -> usedItemIds.contains(item.id));
        }

        return result;
    }

    /**
     * 为根工件 A 生成最终组合块。
     *
     * Beam 的每个节点只表示一个“根 A 当前已经拼接出的 Block”，并记录该分支已经使用的
     * 工件 ID。每层从每个节点遍历所有尚未使用的工件生成后继：
     *
     *     A -> AB、AC、AD -> ACG、ACD ...
     *
     * 没有后继的节点进入 completedStates，不能因为其他分支仍能扩展就被静默丢弃。
     * 最终只从 completedStates 中选择质量最高的节点。
     *
     * 修改理由：旧方法把不同根节点和无关 Block 的合并放进同一个状态，无法保证
     * ACG 与 AB 在同一个根节点下竞争。这里保留原有 tryAddItem 的 NFP、旋转、
     * 重叠、连通性和尺寸检查，只改变 Beam 的状态范围。
     */
    private static Block searchBestBlockFromRoot(
            PolygonItem rootItem,
            List<PolygonItem> remainingItems,
            int beamWidth,
            Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        Block rootBlock = Block.fromSingle(rootItem);
        RootBeamState initialState = RootBeamState.fromRoot(rootBlock);

        // 小矩形只作为被插入物品，不作为根节点继续扩展，保持原有业务规则。
        if (!canActAsBaseBlock(rootBlock)) {
            return rootBlock;
        }

        List<RootBeamState> beam = new ArrayList<>();
        beam.add(initialState);
        List<RootBeamState> completedStates = new ArrayList<>();

        while (!beam.isEmpty()) {
            List<RootBeamState> nextBeamCandidates = new ArrayList<>();

            for (RootBeamState state : beam) {
                if (!canActAsBaseBlock(state.block)) {
                    // 达到 98%、尺寸上限或其他根节点限制后，当前节点成为终止方案。
                    completedStates.add(state);
                    continue;
                }

                List<RootBeamState> children = buildRootChildren(state, remainingItems, nfpCache);
                if (children.isEmpty()) {
                    // 当前根块没有任何合法且能提升填充率的后继，保存它供最终比较。
                    completedStates.add(state);
                    continue;
                }

                nextBeamCandidates.addAll(children);
            }

            if (nextBeamCandidates.isEmpty()) {
                break;
            }

            // 在同一层的全部后继中统一排序，只保留前 beamWidth 个根 A 分支。
            beam = selectBestRootStates(nextBeamCandidates, beamWidth);
        }

        // 正常情况下每条路径最终都会进入 completedStates；这里保留防御性回退，
        // 避免异常几何或未来新增终止条件导致根工件丢失。
        if (completedStates.isEmpty()) {
            completedStates.addAll(beam);
        }
        if (completedStates.isEmpty()) {
            return rootBlock;
        }
        return selectBestRootState(completedStates).block;
    }

    /**
     * 为一个根 Beam 节点生成下一层的全部单工件后继。
     *
     * 功能说明：例如当前节点是 AC，则该方法只尝试把尚未使用的 B、D、G 等单件
     * 继续加入 AC，生成 ACB、ACD、ACG。它不会重新选择根，也不会合并其他无关 Block。
     */
    private static List<RootBeamState> buildRootChildren(
            RootBeamState state,
            List<PolygonItem> remainingItems,
            Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        List<RootBeamState> children = new ArrayList<>();
        for (PolygonItem item : remainingItems) {
            if (state.usedItemIds.contains(item.id)) {
                continue;
            }

            Block childBlock = tryAddItem(state.block, item, nfpCache);
            if (childBlock == null) {
                continue;
            }

            children.add(state.withAddedItem(item, childBlock));
        }
        return children;
    }

    /**
     * 选择下一次处理的根工件。
     *
     * 优先选择非矩形小件中的低填充率工件，以便先处理最需要通过凹槽拼接改善的形状；
     * 只有没有其他可作为根的工件时，才把矩形小件作为单独结果输出。
     * 这样可以保证矩形小件仍然留在候选池中，优先尝试插入其他根工件。
     */
    private static PolygonItem selectNextRootItem(List<PolygonItem> remainingItems) {
        PolygonItem selected = null;
        for (PolygonItem item : remainingItems) {
            if (isSmallRectangleItem(item)) {
                continue;
            }
            if (selected == null || compareRootItems(item, selected) < 0) {
                selected = item;
            }
        }

        if (selected != null) {
            return selected;
        }
        return remainingItems.get(0);
    }

    /** 按初始填充率升序选择根工件；ID 作为稳定的平局规则。 */
    private static int compareRootItems(PolygonItem left, PolygonItem right) {
        if (Math.abs(left.fillRate - right.fillRate) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(left.fillRate, right.fillRate);
        }
        return left.id.compareTo(right.id);
    }

    /** 从输入的单件 Block 中提取仍待处理的原始工件。 */
    private static List<PolygonItem> collectItems(List<Block> blocks) {
        List<PolygonItem> items = new ArrayList<>();
        for (Block block : blocks) {
            for (Block.ItemPlacement placement : block.placements) {
                items.add(placement.item);
            }
        }
        return items;
    }

    /** 收集一个最终组合块中的工件 ID，用于从全局剩余池中移除已使用工件。 */
    private static Set<String> collectItemIds(Block block) {
        Set<String> itemIds = new HashSet<>();
        for (Block.ItemPlacement placement : block.placements) {
            itemIds.add(placement.item.id);
        }
        return itemIds;
    }

    /** 保留当前根 A 搜索层中质量最高的不同拼接方案。 */
    private static List<RootBeamState> selectBestRootStates(List<RootBeamState> states,
                                                            int beamWidth) {
        states.sort(BatchBlockStitcher::compareRootStates);
        List<RootBeamState> selectedStates = new ArrayList<>();
        Set<String> signatures = new HashSet<>();

        for (RootBeamState state : states) {
            String signature = rootStateSignature(state);
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
     * 比较同一个根 A 的拼接方案。
     *
     * 主指标是当前组合块填充率；填充率相同时优先成员更多的方案，
     * 再使用累计 score2 和块 ID 稳定排序。这里不再使用所有 Block 的总体填充率，
     * 避免无关工件的状态影响 A 根分支的竞争结果。
     */
    private static int compareRootStates(RootBeamState left, RootBeamState right) {
        if (Math.abs(left.block.fillRate - right.block.fillRate) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.block.fillRate, left.block.fillRate);
        }
        if (left.block.memberCount() != right.block.memberCount()) {
            return Integer.compare(right.block.memberCount(), left.block.memberCount());
        }
        if (Math.abs(left.block.score2 - right.block.score2) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.block.score2, left.block.score2);
        }
        return left.block.id.compareTo(right.block.id);
    }

    /** 从终止集合中取出当前根 A 的最优方案。 */
    private static RootBeamState selectBestRootState(List<RootBeamState> states) {
        states.sort(BatchBlockStitcher::compareRootStates);
        return states.get(0);
    }

    /** 使用根块几何和已使用工件生成当前 Beam 分支的唯一签名。 */
    private static String rootStateSignature(RootBeamState state) {
        StringBuilder signature = new StringBuilder(blockGeometrySignature(state.block));
        List<String> sortedItemIds = new ArrayList<>(state.usedItemIds);
        sortedItemIds.sort(String::compareTo);
        for (String itemId : sortedItemIds) {
            signature.append('|').append(itemId);
        }
        return signature.toString();
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
     * smallItem 会要求候选完全位于当前 Block 外接框内，优先填补已有凹腔；
     * 非 smallItem 的外边界候选也必须通过 score2 和明显收益检查。
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
                item.points, item.area, relativeRotations, item.smallItem);
        PolygonStitcher.StitchingResult nfpResult = nfpCache.get(cacheKey);
        if (nfpResult == null) {
            nfpResult = PolygonStitcher.findBestStitchForFixedPolygons(
                    fixedPolygons,
                    block.areaSum,
                    block.boxArea,
                    item.points,
                    item.area,
                    relativeRotations,
                    item.smallItem);
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

        if (item.smallItem && !candidate.cavityInsertion) {
            // 这是 NFP 层之外的第二道保护：小件不得通过外边界扩张组合块。
            return null;
        }
        if (!candidate.cavityInsertion
                && (candidate.score2 <= PolygonStitcher.SCORE_EPS
                || candidate.fillRateGain < PolygonStitcher.MIN_OUTER_FILL_RATE_GAIN
                - PolygonStitcher.SCORE_EPS)) {
            // 防止缓存旧结果或未来新增候选路径绕过 PolygonStitcher 的外扩过滤。
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
                                               List<Integer> rotations,
                                               boolean requireCavityInsertion) {
        StringBuilder signature = new StringBuilder();
        signature.append(baseArea).append('|')
                .append(baseBoxArea).append('|')
                .append(itemArea).append('|')
                .append(rotations).append('|');
        // 同一几何可能同时出现在 smallItem 和普通工件中；两者的候选过滤策略不同，
        // 因此必须把该策略写入缓存键，避免复用错误的 NFP 结果。
        signature.append(requireCavityInsertion).append('|');
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
     * 以一个根工件 A 为中心的 Beam 节点。
     *
     * block 保存当前 A 根拼接出的完整几何；usedItemIds 保存该分支已经消耗的工件，
     * 用于防止同一个工件在同一条拼接路径中重复加入。不同根节点之间不会共享该状态，
     * 只有最终选定的 Block 才会回写到外层结果。
     */
    private static final class RootBeamState {
        private final Block block;
        private final Set<String> usedItemIds;

        private RootBeamState(Block block, Set<String> usedItemIds) {
            this.block = block;
            // 每个节点拥有自己的集合副本，避免后续分支互相修改已使用工件集合。
            this.usedItemIds = new HashSet<>(usedItemIds);
        }

        private static RootBeamState fromRoot(Block rootBlock) {
            Set<String> rootItemIds = new HashSet<>();
            for (Block.ItemPlacement placement : rootBlock.placements) {
                rootItemIds.add(placement.item.id);
            }
            return new RootBeamState(rootBlock, rootItemIds);
        }

        /** 创建加入一个新工件后的子节点，并保留父节点的使用集合。 */
        private RootBeamState withAddedItem(PolygonItem item, Block childBlock) {
            Set<String> nextUsedItemIds = new HashSet<>(usedItemIds);
            nextUsedItemIds.add(item.id);
            return new RootBeamState(childBlock, nextUsedItemIds);
        }
    }

}
