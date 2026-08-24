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
import java.util.List;
import java.util.Locale;

public class BatchBlockStitcher {

    private static final Path INPUT_DIRECTORY = Path.of("data", "inputData");
    private static final Path OUTPUT_DIRECTORY = Path.of("data", "NFPresult2");
    private static final Gson GSON = new Gson();

    // 组合块外接矩形长边超过板材长度时无法进入第二阶段排样，因此这类候选不保留。
    private static final double MAX_PACKABLE_BLOCK_LENGTH = 2440.0;
    // 不允许旋转的组合块会按当前方向进入第二阶段，bbox 的 Y 向宽度不能超过板材宽度。
    private static final double MAX_FIXED_ORIENTATION_BLOCK_WIDTH = 1220.0;

    // 每轮最多保留的非冲突拼接结果。该值作为默认 count，命令行第三个参数可覆盖。
    // 修改理由：新策略需要一个明确的轮次宽度，避免全量遍历后一次性合并过多低质量结果。
    private static final int DEFAULT_ROUND_RESULT_COUNT = 5;

    public static void main(String[] args) throws IOException {
        Path inputDirectory = args.length > 0 ? Path.of(args[0]) : INPUT_DIRECTORY;
        Path outputDirectory = args.length > 1 ? Path.of(args[1]) : OUTPUT_DIRECTORY;
        int roundResultCount = args.length > 2
                ? parsePositiveInt(args[2], DEFAULT_ROUND_RESULT_COUNT)
                : DEFAULT_ROUND_RESULT_COUNT;
        processDirectory(inputDirectory, outputDirectory, roundResultCount);
    }

    public static void processDirectory(Path inputDirectory, Path outputDirectory) throws IOException {
        processDirectory(inputDirectory, outputDirectory, DEFAULT_ROUND_RESULT_COUNT);
    }

    public static void processDirectory(Path inputDirectory, Path outputDirectory, int roundResultCount) throws IOException {
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
            List<Block> blocks = buildBlocks(items, roundResultCount);
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
     * 新策略把所有需要拼接的非近矩形物品放入同一轮次搜索：
     * 1) 每轮遍历当前块与所有仍为单件的候选物品，且只允许 BackFrontPriority、旋转约束兼容的组合进入 NFP；
     * 2) smallItem=true 且几何上确认为矩形的物品只能作为被选择的单件候选，不能作为主拼接块；
     *    smallItem=true 的四角梯形等多边形仍可作为主拼接块参与搜索；
     * 3) 按 score2 从高到低选择最多 count 个结果，同一轮内任意物品只能出现一次；
     * 4) 只有 score2 > 0 且组合块长边不超过 2440 的候选才保留，剩余无法被选中的物品自然作为单独块输出。
     *
     * 修改理由：旧策略由多个启发式阶段串联，容易让局部锚点或输入顺序提前锁死候选；
     * 新策略把“谁与谁拼”收敛为统一的轮次匹配，保证同优先级物品都有机会互相尝试。
     */
    public static List<Block> buildBlocks(List<PolygonItem> items) {
        return buildBlocks(items, DEFAULT_ROUND_RESULT_COUNT);
    }

    public static List<Block> buildBlocks(List<PolygonItem> items, int roundResultCount) {
        int normalizedRoundResultCount = Math.max(1, roundResultCount);
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

        finalBlocks.addAll(stitchByExhaustiveRounds(activeBlocks, normalizedRoundResultCount));
        return finalBlocks;
    }

    /**
     * 轮次式遍历拼接。
     *
     * 修改理由：每一轮都基于当前仍可用的块重新生成全量候选，而不是沿输入顺序贪心推进；
     * 这样 A-B、B-C 这类竞争关系会在同一个候选池里比较，选中一个后另一个因物品重复自动舍弃。
     */
    private static List<Block> stitchByExhaustiveRounds(List<Block> initialBlocks, int roundResultCount) {
        List<Block> activeBlocks = new ArrayList<>(initialBlocks);

        while (true) {
            List<RoundStitchCandidate> roundCandidates = buildRoundStitchCandidates(activeBlocks);
            List<RoundStitchCandidate> selectedCandidates = selectTopNonOverlappingCandidates(
                    roundCandidates,
                    activeBlocks.size(),
                    roundResultCount);
            if (selectedCandidates.isEmpty()) {
                break;
            }
            activeBlocks = applyRoundCandidates(activeBlocks, selectedCandidates);
        }
        return activeBlocks;
    }

    /**
     * 为当前轮生成候选：每个可作为主块的当前块都尝试拼接每个单件块。
     *
     * 功能说明：smallItem=true 且几何上为矩形的单件块不作为主拼接块，避免小矩形主动吞并其他物品；
     * 四角梯形等非矩形多边形即使 smallItem=true，也仍可作为 baseBlock 继续选择候选物品。
     */
    private static List<RoundStitchCandidate> buildRoundStitchCandidates(List<Block> activeBlocks) {
        List<RoundStitchCandidate> candidates = new ArrayList<>();
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
                    candidates.add(new RoundStitchCandidate(
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
     * 选择每轮 Top count，且同一轮内块/物品不能重复。
     *
     * 修改理由：当 A-B 与 B-C 分数相同或接近时，先按排序规则保留一个，另一个因 B 已被占用舍弃，
     * 这正是“count 个结果中的物品不能重复”的约束。
     */
    private static List<RoundStitchCandidate> selectTopNonOverlappingCandidates(List<RoundStitchCandidate> candidates,
                                                                                int activeBlockCount,
                                                                                int roundResultCount) {
        candidates.sort(BatchBlockStitcher::compareRoundCandidates);
        boolean[] occupied = new boolean[activeBlockCount];
        List<RoundStitchCandidate> selectedCandidates = new ArrayList<>();

        for (RoundStitchCandidate candidate : candidates) {
            if (occupied[candidate.baseBlockIndex] || occupied[candidate.itemBlockIndex]) {
                continue;
            }
            selectedCandidates.add(candidate);
            occupied[candidate.baseBlockIndex] = true;
            occupied[candidate.itemBlockIndex] = true;
            if (selectedCandidates.size() >= roundResultCount) {
                break;
            }
        }
        return selectedCandidates;
    }

    private static int compareRoundCandidates(RoundStitchCandidate left, RoundStitchCandidate right) {
        if (Math.abs(left.score2 - right.score2) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.score2, left.score2);
        }
        if (left.block.memberCount() != right.block.memberCount()) {
            return Integer.compare(right.block.memberCount(), left.block.memberCount());
        }
        return left.block.id.compareTo(right.block.id);
    }

    private static List<Block> applyRoundCandidates(List<Block> activeBlocks, List<RoundStitchCandidate> selectedCandidates) {
        boolean[] consumed = new boolean[activeBlocks.size()];
        List<Block> nextBlocks = new ArrayList<>();

        for (RoundStitchCandidate candidate : selectedCandidates) {
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
     * 单轮遍历拼接候选。
     *
     * 修改理由：需要同时记录“被扩展的当前块”和“被吸收的单件块”，
     * 这样 Top count 选择时才能准确执行同一轮物品不重复约束。
     */
    private static final class RoundStitchCandidate {
        private final int baseBlockIndex;
        private final int itemBlockIndex;
        private final Block block;
        // 用于本轮 Top count 排序的 score2，不使用块的累计 score2，避免历史收益影响当前轮选择。
        private final double score2;

        private RoundStitchCandidate(int baseBlockIndex, int itemBlockIndex, Block block, double score2) {
            this.baseBlockIndex = baseBlockIndex;
            this.itemBlockIndex = itemBlockIndex;
            this.block = block;
            this.score2 = score2;
        }
    }
}