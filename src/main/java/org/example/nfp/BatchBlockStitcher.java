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
    private static final Path OUTPUT_DIRECTORY = Path.of("data", "NFPresult16");
    private static final Gson GSON = new Gson();

    // 组合块外接矩形长边超过板材长度时无法进入第二阶段排样，因此这类候选不保留。
    private static final double MAX_PACKABLE_BLOCK_LENGTH = 2440.0;
    // 不允许旋转的组合块会按当前方向进入第二阶段，bbox 的 Y 向宽度不能超过板材宽度。
    private static final double MAX_FIXED_ORIENTATION_BLOCK_WIDTH = 1220.0;

    // NFP 集束搜索默认保留的状态数量。命令行第三个参数仍然可以覆盖该值。
    // 修改理由：第三个参数原来控制每轮贪心合并数量，现在改为控制搜索宽度；保留参数位置可以兼容原有启动方式。
    private static final int DEFAULT_BEAM_WIDTH = 5;
    // 每个根工件至少保留多个最终候选，避免唯一候选被资源冲突淘汰后无法重新尝试其他方案。
    private static final int MIN_ROOT_CANDIDATE_COUNT = 5;
    // 外层候选集合使用比根内 Beam 更宽的搜索，允许多个根工件的方案一起竞争。
    private static final int GLOBAL_PLAN_BEAM_MULTIPLIER = 4;
    // 非 smallItem 且初始填充率不高的非矩形工件被视为关键凹腔根工件。
    // 修改理由：原阈值 0.75 会漏掉填充率约 0.83、但仍有明显大凹腔的根工件，
    // 使它们无法和小件高填充组合进行全局竞争。提高到 0.90 只影响候选优先级，
    // 不改变工件几何和最终排样约束。
    private static final double CRITICAL_CAVITY_ROOT_FILL_RATE = 0.90;
    // 两个非 smallItem 工件进行普通外边界扩张时，最终终止组合低于该填充率不保留。
    // 修改理由：Cabinet1 中多个填充率约 0.858 的大件组合虽然几何相接，
    // 但会消耗大件并扩大包络框，既没有填凹腔，也会降低后续矩形排样的可用性。
    // 新规则只在分支无法继续扩展时执行；中间的 A+B 即使只有 0.80，
    // 也必须先保留给下一层尝试 A+B+C。最终达到 0.90 的互补组合可以保留。
    // 纯真实凹腔插入不受此限制；如果同一块同时发生大件外扩，仍需满足该阈值。
    private static final double MIN_LARGE_OUTER_COMBINED_FILL_RATE = 0.90;
    // 机会成本只作为普通候选的调节项，不能抵消关键凹腔的直接填充收益。
    private static final double OPPORTUNITY_COST_WEIGHT = 0.35;

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
     * 反复执行“所有根工件生成多候选—全局资源感知竞争—未使用工件回池”。
     *
     * 每一轮都会让当前所有非矩形工件分别作为根工件，独立执行
     * searchTopBlocksFromRoot(...)，每个根工件保留多个最终候选。因此即使某个工件
     * 已经出现在另一个候选块中，仍然可以继续作为本轮其他根工件的拼接候选。
     * 生成完所有根候选后，再使用全局 Beam Search 选择互不冲突的候选集合；未被选中
     * 候选块消耗的工件不会丢失，而是在下一轮重新参与搜索。
     *
     * 修改理由：原实现处理一个根工件后立即从全局池删除其成员，导致后处理的根工件
     * 无法再尝试已经被前一个 Block 占用的工件；即使允许回池，每个根只保留一个候选
     * 也会让大凹腔方案在局部填充率竞争中被小件高填充组合压掉。新的外层策略同时
     * 保留根级替代方案，并在全局状态中优先保护关键凹腔的填充收益。
     */
    private static List<Block> stitchByBeamSearch(List<Block> initialBlocks, int beamWidth) {
        List<PolygonItem> availableItems = collectItems(initialBlocks);
        List<Block> result = new ArrayList<>();

        // 所有轮次共享 NFP 缓存；候选块冲突淘汰后，下一轮只改变可用工件集合，
        // 相同的“固定几何 + 待插入工件 + 旋转策略”仍可直接复用。
        Map<String, PolygonStitcher.StitchingResult> nfpCache = new HashMap<>();

        while (!availableItems.isEmpty()) {
            // 本轮先让所有不规则工件作为根工件生成各自的多个候选块，不能在生成阶段
            // 因为候选工件已出现在其他候选块中就提前排除它。
            int rootCandidateCount = Math.max(MIN_ROOT_CANDIDATE_COUNT, beamWidth);
            List<CandidateBlock> candidateBlocks = buildAllRootCandidates(
                    availableItems, beamWidth, rootCandidateCount, nfpCache);
            List<CandidateBlock> scoredCandidates = calculateOpportunityCosts(candidateBlocks);

            // 先从关键凹腔候选中预留小件，再过滤会抢占这些小件的普通候选。
            // 修改理由：机会成本只能软性调整排序，不能阻止普通高填充块提前消耗
            // 后续大凹腔真正需要的小件；这里增加显式资源约束，但不改变候选几何。
            Set<String> reservedSmallItemIds = reserveCriticalSmallItems(scoredCandidates);
            List<CandidateBlock> selectableCandidates = filterReservedSmallItemConsumers(
                    scoredCandidates, reservedSmallItemIds);
            int globalPlanWidth = Math.max(rootCandidateCount,
                    beamWidth * GLOBAL_PLAN_BEAM_MULTIPLIER);
            GlobalPlanState bestPlan = selectGlobalCandidatePlan(selectableCandidates, globalPlanWidth);

            if (bestPlan == null || bestPlan.selectedCandidates.isEmpty()) {
                // 当前剩余工件已经无法形成新的有效拼接块，剩余工件保持单件输出，
                // 避免为了继续循环而生成无效 Block。
                appendSingleBlocks(result, availableItems);
                break;
            }

            Set<String> committedItemIds = new HashSet<>();
            for (CandidateBlock selectedCandidate : bestPlan.selectedCandidates) {
                result.add(selectedCandidate.block);
                committedItemIds.addAll(selectedCandidate.itemIds);
            }

            // 只从全局池移除本轮真正提交的 Block 成员；被冲突淘汰的候选块成员仍留在池中，
            // 下一轮会在更小的资源集合上重新搜索，从而得到例如 AC 的替代组合。
            availableItems.removeIf(item -> committedItemIds.contains(item.id));
        }

        return result;
    }

    /**
     * 为当前可用工件集合中的每个非矩形根工件生成多个最终候选 Block。
     *
     * 功能说明：这是外层的“遍历全部根工件”步骤；真正的多层 AB、AC、ACG 分支
     * 仍由每个根内部的 Beam Search 完成。这里不提前占用工件，因此不同根之间可以
     * 观察到同一件候选工件，交由后续全局资源竞争统一处理。
     *
     * 修改理由：原方法每个根只返回一个最佳块。若该块消耗了本应填入大凹腔的小件，
     * 全局冲突处理就没有该根的替代方案可选；现在保留 Top-K，后续全局 Beam 可以
     * 同时比较“凹腔 + 多个小件”和“小件互补高填充块”。
     */
    private static List<CandidateBlock> buildAllRootCandidates(
            List<PolygonItem> availableItems,
            int beamWidth,
            int candidateLimit,
            Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        List<CandidateBlock> candidateBlocks = new ArrayList<>();
        for (PolygonItem rootItem : availableItems) {
            if (isSmallRectangleItem(rootItem)) {
                // 规则小矩形继续只作为被插入工件，不单独发起第一阶段 NFP 搜索。
                continue;
            }

            List<Block> rootCandidates = searchTopBlocksFromRoot(
                    rootItem, availableItems, beamWidth, candidateLimit, nfpCache);
            for (Block rootCandidate : rootCandidates) {
                if (rootCandidate.memberCount() > 1) {
                    // 单件结果不是竞争候选；它会在没有可行拼接时由外层统一输出。
                    candidateBlocks.add(CandidateBlock.from(rootItem, rootCandidate));
                }
            }
        }
        return candidateBlocks;
    }

    /**
     * 计算候选块对关键凹腔资源的机会成本。
     *
     * 功能说明：先统计每个小件被关键凹腔候选使用时的最大收益，再给普通候选块
     * 计算资源占用代价。机会成本仍作为软评分使用，小件是否允许被普通候选使用，
     * 由随后执行的 reserveCriticalSmallItems() 硬约束负责。
     *
     * 修改理由：仅比较候选块自身的 fillRate 会让 1.0 的小件互补块无条件压过
     * 大凹腔方案；同时把机会成本和小件预留分开，避免用一个权重同时承担两种职责。
     */
    private static List<CandidateBlock> calculateOpportunityCosts(List<CandidateBlock> candidates) {
        Map<String, Double> criticalItemValues = new HashMap<>();
        Map<String, Integer> criticalItemDemand = new HashMap<>();
        for (CandidateBlock candidate : candidates) {
            if (!candidate.criticalCavity) {
                continue;
            }
            // 机会成本只针对实际新增的小件；大件资源由候选块之间的 item conflict 处理。
            for (String itemId : candidate.addedSmallItemIds()) {
                criticalItemValues.merge(itemId, candidate.criticalGain, Math::max);
                criticalItemDemand.merge(itemId, 1, Integer::sum);
            }
        }

        List<CandidateBlock> result = new ArrayList<>(candidates.size());
        for (CandidateBlock candidate : candidates) {
            double opportunityCost = 0.0;
            if (!candidate.criticalCavity) {
                for (String itemId : candidate.addedSmallItemIds()) {
                    Double criticalValue = criticalItemValues.get(itemId);
                    Integer demand = criticalItemDemand.get(itemId);
                    if (criticalValue != null && demand != null) {
                        // 同一小件存在多个关键凹腔替代方案时，按需求数量分摊代价，
                        // 避免因为静态统计过度惩罚普通候选。
                        opportunityCost += criticalValue / Math.max(1, demand);
                    }
                }
            }
            result.add(candidate.withOpportunityCost(opportunityCost));
        }
        return result;
    }

    /**
     * 为当前轮次选择需要优先保护的小件资源。
     *
     * 功能说明：关键凹腔候选按收益从高到低尝试选择；互相冲突的候选不能同时
     * 预留，避免把同一个大件或同一个小件重复计算。被选中候选中的 smallItem
     * 会暂时保留给关键凹腔，直到本轮全局方案确定。
     *
     * 修改理由：原有机会成本只改变排序，无法阻止普通高填充组合消耗关键小件。
     * 这里采用轻量级的贪心资源预留，不引入新的整数规划求解器，也不改变 NFP 几何计算。
     */
    private static Set<String> reserveCriticalSmallItems(List<CandidateBlock> candidates) {
        List<CandidateBlock> criticalCandidates = new ArrayList<>();
        for (CandidateBlock candidate : candidates) {
            if (candidate.criticalCavity && !candidate.smallItemIds().isEmpty()) {
                criticalCandidates.add(candidate);
            }
        }

        criticalCandidates.sort(BatchBlockStitcher::compareCriticalReservationCandidates);

        Set<String> reservedSmallItemIds = new HashSet<>();
        Set<String> reservedCandidateItemIds = new HashSet<>();
        for (CandidateBlock candidate : criticalCandidates) {
            // 只预留互不冲突的关键凹腔方案，保证预留资源至少对应一组可同时尝试的方案。
            if (hasItemConflict(candidate.itemIds, reservedCandidateItemIds)) {
                continue;
            }

            reservedCandidateItemIds.addAll(candidate.itemIds);
            reservedSmallItemIds.addAll(candidate.smallItemIds());
        }
        return reservedSmallItemIds;
    }

    /** 比较关键凹腔预留方案，优先保护凹腔收益高且小件消耗少的候选。 */
    private static int compareCriticalReservationCandidates(CandidateBlock left,
                                                             CandidateBlock right) {
        if (Math.abs(left.criticalGain - right.criticalGain) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.criticalGain, left.criticalGain);
        }

        if (left.smallItemIds().size() != right.smallItemIds().size()) {
            return Integer.compare(left.smallItemIds().size(), right.smallItemIds().size());
        }

        if (Math.abs(left.block.fillRate - right.block.fillRate) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.block.fillRate, left.block.fillRate);
        }
        return left.block.id.compareTo(right.block.id);
    }

    /**
     * 过滤会抢占关键凹腔预留小件的普通候选。
     *
     * 关键凹腔候选本身允许使用预留小件；其它候选若不使用预留资源则正常参与全局
     * Beam。预留集合为空时直接返回原候选列表，保持没有关键凹腔时的原有行为。
     */
    private static List<CandidateBlock> filterReservedSmallItemConsumers(
            List<CandidateBlock> candidates,
            Set<String> reservedSmallItemIds) {
        if (reservedSmallItemIds.isEmpty()) {
            return candidates;
        }

        List<CandidateBlock> result = new ArrayList<>();
        for (CandidateBlock candidate : candidates) {
            if (candidate.criticalCavity
                    || !containsAny(candidate.smallItemIds(), reservedSmallItemIds)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** 判断两个小件 ID 集合是否有交集，避免使用复杂的集合链式表达式。 */
    private static boolean containsAny(Set<String> first, Set<String> second) {
        for (String itemId : first) {
            if (second.contains(itemId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 使用全局 Beam Search 选择本轮互不冲突的候选块集合。
     *
     * 功能说明：每个状态表示一组已经选择的候选块和已消耗的工件。候选块可以是
     * fillRate 大于 0.98 的高填充组合，也可以是为了填充关键凹腔而使用多个小件的组合；
     * 只有提交状态后才真正从 availableItems 删除工件。
     *
     * 修改理由：原来的贪心方法只按单个 Block 排序，无法比较不同候选集合的整体收益。
     * 现在用有限宽度的集合搜索保留多种资源分配方案，避免小件互补块过早锁死凹腔方案。
     */
    private static GlobalPlanState selectGlobalCandidatePlan(
            List<CandidateBlock> candidates,
            int planBeamWidth) {
        if (candidates.isEmpty()) {
            return null;
        }

        List<CandidateBlock> orderedCandidates = new ArrayList<>(candidates);
        orderedCandidates.sort(BatchBlockStitcher::compareCandidateGenerationOrder);

        List<GlobalPlanState> beam = new ArrayList<>();
        beam.add(GlobalPlanState.empty());
        for (CandidateBlock candidate : orderedCandidates) {
            List<GlobalPlanState> nextStates = new ArrayList<>(beam);
            for (GlobalPlanState state : beam) {
                if (!hasItemConflict(candidate.itemIds, state.usedItemIds)) {
                    nextStates.add(state.withCandidate(candidate));
                }
            }
            beam = selectBestGlobalPlans(nextStates, planBeamWidth);
        }

        GlobalPlanState bestPlan = GlobalPlanState.empty();
        for (GlobalPlanState state : beam) {
            if (compareGlobalPlans(state, bestPlan) < 0) {
                bestPlan = state;
            }
        }
        return bestPlan.selectedCandidates.isEmpty() ? null : bestPlan;
    }

    /** 优先将关键凹腔候选送入全局 Beam，但最终优劣仍由完整状态评分决定。 */
    private static int compareCandidateGenerationOrder(CandidateBlock left, CandidateBlock right) {
        if (left.criticalCavity != right.criticalCavity) {
            return left.criticalCavity ? -1 : 1;
        }
        return compareCandidateBlocks(left.block, right.block);
    }

    /** 保留全局候选集合中质量最高且签名不同的状态。 */
    private static List<GlobalPlanState> selectBestGlobalPlans(List<GlobalPlanState> states,
                                                               int planBeamWidth) {
        states.sort(BatchBlockStitcher::compareGlobalPlans);
        List<GlobalPlanState> selectedStates = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (GlobalPlanState state : states) {
            if (!signatures.add(state.signature())) {
                continue;
            }
            selectedStates.add(state);
            if (selectedStates.size() >= planBeamWidth) {
                break;
            }
        }
        return selectedStates;
    }

    /**
     * 比较两组全局候选方案。
     *
     * 关键凹腔收益是第一优先级；随后比较被改善的关键凹腔数量、扣除资源机会成本
     * 后的总体收益，最后才使用普通填充率和 score2 作为平局规则。
     */
    private static int compareGlobalPlans(GlobalPlanState left, GlobalPlanState right) {
        if (Math.abs(left.criticalGain - right.criticalGain) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.criticalGain, left.criticalGain);
        }
        if (left.criticalRootCount != right.criticalRootCount) {
            return Integer.compare(right.criticalRootCount, left.criticalRootCount);
        }
        if (left.totalCavityInsertionCount != right.totalCavityInsertionCount) {
            // 修改理由：两个方案的关键收益接近时，优先保留真实进入凹腔的拼接次数，
            // 使全局资源分配继续偏向“凹腔 + 小件”，而不是只追求外接框填充率。
            return Integer.compare(right.totalCavityInsertionCount, left.totalCavityInsertionCount);
        }
        if (left.nonCriticalSmallItemCount != right.nonCriticalSmallItemCount) {
            // 修改理由：关键凹腔收益相同或接近时，优先保留消耗普通小件更少的方案，
            // 给尚未进入本轮候选的凹腔留下更多可用资源。关键凹腔候选使用的小件
            // 不计入该指标，因为它们已经属于被保护的目标资源。
            return Integer.compare(left.nonCriticalSmallItemCount, right.nonCriticalSmallItemCount);
        }
        if (Math.abs(left.effectiveGain - right.effectiveGain) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.effectiveGain, left.effectiveGain);
        }
        if (Math.abs(left.opportunityCost - right.opportunityCost) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(left.opportunityCost, right.opportunityCost);
        }
        if (Math.abs(left.totalFillRateGain - right.totalFillRateGain) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.totalFillRateGain, left.totalFillRateGain);
        }
        if (Math.abs(left.totalScore2 - right.totalScore2) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.totalScore2, left.totalScore2);
        }
        if (left.selectedCandidates.size() != right.selectedCandidates.size()) {
            return Integer.compare(right.selectedCandidates.size(), left.selectedCandidates.size());
        }
        return left.signature().compareTo(right.signature());
    }

    /** 判断候选块是否与全局方案已经提交的工件共享 ID。 */
    private static boolean hasItemConflict(Set<String> candidateItemIds,
                                           Set<String> committedItemIds) {
        for (String itemId : candidateItemIds) {
            if (committedItemIds.contains(itemId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 比较不同根工件生成的候选块。
     *
     * 最近一次 NFP 拼接的综合 Score 是主要质量指标；Score 接近时再比较凹腔插入、
     * 最终填充率、score2、成员数和外接框面积，保证新评分优先且结果稳定可复现。
     */
    private static int compareCandidateBlocks(Block left, Block right) {
        if (Math.abs(left.lastStitchScore() - right.lastStitchScore()) > PolygonStitcher.SCORE_EPS) {
            // 修改理由：NFP 候选层已经以 A-B 综合 Score 排序；根级候选再次竞争时也要
            // 优先延续该评分，否则上层只按最终 fillRate 排序会抵消 NFP 层的新评分。
            return Double.compare(right.lastStitchScore(), left.lastStitchScore());
        }
        int leftCavityInsertionCount = countCavityInsertions(left);
        int rightCavityInsertionCount = countCavityInsertions(right);
        if (leftCavityInsertionCount != rightCavityInsertionCount) {
            // 修改理由：候选生成顺序也要体现凹腔结构质量，避免相同关键等级下，
            // 普通外扩块先进入全局 Beam 并挤占有限的状态名额。
            return Integer.compare(rightCavityInsertionCount, leftCavityInsertionCount);
        }
        if (Math.abs(left.fillRate - right.fillRate) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.fillRate, left.fillRate);
        }
        if (Math.abs(left.score2 - right.score2) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(right.score2, left.score2);
        }
        if (left.memberCount() != right.memberCount()) {
            return Integer.compare(right.memberCount(), left.memberCount());
        }
        if (Math.abs(left.boxArea - right.boxArea) > PolygonStitcher.SCORE_EPS) {
            return Double.compare(left.boxArea, right.boxArea);
        }
        return left.id.compareTo(right.id);
    }

    /** 将无法继续组块的剩余工件以单件 Block 形式追加到结果。 */
    private static void appendSingleBlocks(List<Block> result, List<PolygonItem> availableItems) {
        for (PolygonItem item : availableItems) {
            result.add(Block.fromSingle(item));
        }
    }

    /**
     * 为根工件 A 生成多个最终组合块。
     *
     * Beam 的每个节点只表示一个“根 A 当前已经拼接出的 Block”，并记录该分支已经使用的
     * 工件 ID。每层从每个节点遍历所有尚未使用的工件生成后继：
     *
     *     A -> AB、AC、AD -> ACG、ACD ...
     *
     * 没有后继的节点进入 completedStates，不能因为其他分支仍能扩展就被静默丢弃。
     * 最终从 completedStates 中保留前 candidateLimit 个不同方案，而不是只返回一个。
     *
     * 修改理由：旧方法把不同根节点和无关 Block 的合并放进同一个状态，无法保证
     * ACG 与 AB 在同一个根节点下竞争。这里保留原有 NFP、旋转、重叠、连通性和
     * 尺寸检查，同时让同一工件对的多个位置也进入根级 Beam 竞争。
     */
    private static List<Block> searchTopBlocksFromRoot(
            PolygonItem rootItem,
            List<PolygonItem> remainingItems,
            int beamWidth,
            int candidateLimit,
            Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        Block rootBlock = Block.fromSingle(rootItem);

        // 小矩形只作为被插入物品，不作为根节点继续扩展，保持原有业务规则。
        if (!canActAsBaseBlock(rootBlock)) {
            return List.of(rootBlock);
        }

        // 记录已经确认“与 A 组合后没有有效终止块，也不能继续向下扩展”的首层物品。
        // 该集合是本次根搜索的局部惩罚表，不会影响其他根工件，也不会永久修改全局物品池。
        Set<String> penalizedFirstItemIds = new HashSet<>();

        while (true) {
            RootSearchResult searchResult = runRootBeamSearch(
                    rootItem,
                    remainingItems,
                    beamWidth,
                    candidateLimit,
                    penalizedFirstItemIds,
                    nfpCache);

            if (!searchResult.validCompletedStates.isEmpty()) {
                List<RootBeamState> topStates = selectBestRootStates(
                        searchResult.validCompletedStates,
                        candidateLimit);
                List<Block> result = new ArrayList<>(topStates.size());
                for (RootBeamState state : topStates) {
                    result.add(state.block);
                }
                return result;
            }

            // 本轮没有得到可输出的多件终止块时，只惩罚本轮真正进入 Beam 的首层分支。
            // 未进入 Beam 的候选不会被误判，下一轮会从剩余候选中重新选出新的前 beamWidth 个分支。
            Set<String> newlyFailedFirstItemIds = new HashSet<>(searchResult.exploredFirstItemIds);
            newlyFailedFirstItemIds.removeAll(penalizedFirstItemIds);
            if (newlyFailedFirstItemIds.isEmpty()) {
                // 所有候选都已尝试，或根节点根本没有可行的首层拼接；此时 A 才能作为单件块输出。
                return List.of(rootBlock);
            }

            // 修改理由：原逻辑在首层 Beam 全部变成低质量终止节点时直接回退到 A，
            // 没有给下一批候选机会。现在把失败首层物品暂时排除，再从剩余物品重新搜索。
            penalizedFirstItemIds.addAll(newlyFailedFirstItemIds);
        }
    }

    /**
     * 执行一次以 A 为根的 Beam 搜索，并返回终止节点及本轮实际探索到的首层物品。
     *
     * 功能说明：将“搜索”和“失败首层分支收集”拆开，便于外层在一批 AB、AC、AD
     * 等分支全部无效时施加局部惩罚，然后继续尝试下一批候选。只有进入当前 Beam
     * 的首层物品才会记录为失败，避免把尚未搜索的候选提前淘汰。
     */
    private static RootSearchResult runRootBeamSearch(
            PolygonItem rootItem,
            List<PolygonItem> remainingItems,
            int beamWidth,
            int candidateLimit,
            Set<String> penalizedFirstItemIds,
            Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        Block rootBlock = Block.fromSingle(rootItem);
        RootBeamState initialState = RootBeamState.fromRoot(rootBlock);

        List<RootBeamState> beam = new ArrayList<>();
        beam.add(initialState);
        List<RootBeamState> completedStates = new ArrayList<>();
        Set<String> exploredFirstItemIds = new HashSet<>();

        while (!beam.isEmpty()) {
            List<RootBeamState> nextBeamCandidates = new ArrayList<>();

            for (RootBeamState state : beam) {
                if (state.firstAddedItemId != null) {
                    // 该状态已经实际进入搜索，若整轮没有任何有效终止结果，
                    // 它的首层物品才有资格接受惩罚并在下一轮暂时排除。
                    exploredFirstItemIds.add(state.firstAddedItemId);
                }

                if (!canActAsBaseBlock(state.block)) {
                    // 达到 98%、尺寸上限或其他根节点限制后，当前节点成为终止方案。
                    completedStates.add(state);
                    continue;
                }

                List<RootBeamState> children = buildRootChildren(
                        state,
                        remainingItems,
                        Math.max(beamWidth, candidateLimit),
                        penalizedFirstItemIds,
                        nfpCache);
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

            // 在同一层的全部后继中统一排序，只保留足够产生 Top-K 终止方案的根 A 分支。
            // 修改理由：若根内只保留一个分支，外层即使使用全局 Beam 也看不到该根的替代方案。
            beam = selectBestRootStates(nextBeamCandidates, Math.max(beamWidth, candidateLimit));
        }

        // 正常情况下每条路径最终都会进入 completedStates；这里保留防御性回退，
        // 避免异常几何或未来新增终止条件导致根工件丢失。
        if (completedStates.isEmpty()) {
            completedStates.addAll(beam);
        }
        if (completedStates.isEmpty()) {
            return new RootSearchResult(List.of(), exploredFirstItemIds);
        }

        // 修改原因：大件外扩质量必须在“无法继续扩展”的终止节点上判断，
        // 不能在 tryBuildChildBlock() 创建 A+B 时提前过滤；否则 A+B 无法继续
        // 生成后续的 A+B+C，即使最终可以达到 0.96 也会被错误丢失。
        List<RootBeamState> validCompletedStates = new ArrayList<>();
        for (RootBeamState state : completedStates) {
            if (!isLowQualityLargeOuterBlock(state.block)) {
                validCompletedStates.add(state);
            }
        }
        return new RootSearchResult(validCompletedStates, exploredFirstItemIds);
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
            int candidateLimit,
            Set<String> penalizedFirstItemIds,
            Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        List<RootBeamState> children = new ArrayList<>();
        for (PolygonItem item : remainingItems) {
            if (state.usedItemIds.contains(item.id)) {
                continue;
            }

            // 只在 A 的第一层应用失败惩罚。后续层仍允许使用普通候选，
            // 否则会把“B 作为其他分支中的深层工件”错误地全局禁止。
            if (isPenalizedFirstBranch(state, item, penalizedFirstItemIds)) {
                continue;
            }

            // 同一个待插入工件可能对应多个合法位置；全部作为独立后继交给本层 Beam，
            // 避免 NFP 层的单一最优位置提前淘汰能够继续填凹腔的方案。
            List<Block> childBlocks = tryAddItems(state.block, item, candidateLimit, nfpCache);
            for (Block childBlock : childBlocks) {
                children.add(state.withAddedItem(item, childBlock));
            }
        }
        return children;
    }

    /**
     * 判断当前首层候选是否已经被本根搜索判定为失败分支。
     *
     * 惩罚只对“根 A 的直接后继”生效；如果当前状态已经包含首层物品，
     * 则该物品只能通过 usedItemIds 规则判断，不能套用 A 的失败记录。
     */
    private static boolean isPenalizedFirstBranch(
            RootBeamState state,
            PolygonItem item,
            Set<String> penalizedFirstItemIds) {
        return state.firstAddedItemId == null
                && penalizedFirstItemIds.contains(item.id);
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

    /**
     * 收集组合块中实际使用的 smallItem ID。
     *
     * 功能说明：资源预留需要同时识别“新增小件”和“以小件作为根节点”的候选，
     * 因此这里遍历整个 Block，而不是只从根工件之外收集 ID。
     */
    private static Set<String> collectSmallItemIds(Block block) {
        Set<String> smallItemIds = new HashSet<>();
        for (Block.ItemPlacement placement : block.placements) {
            if (placement.item.smallItem) {
                smallItemIds.add(placement.item.id);
            }
        }
        return smallItemIds;
    }

    /**
     * 保留当前根 A 搜索层中质量最高的不同拼接方案。
     *
     * 修改理由：若完全按当前填充率排序，A+B 这种暂时填充率较低、但仍有后续
     * 扩展机会的大件方案，可能被“当前填充率较高但已经消耗小件”的方案挤出 Beam。
     * 因此为可继续扩展的大件外扩状态保留少量固定名额，避免提前丢失 A+B+C 路径。
     */
    private static List<RootBeamState> selectBestRootStates(List<RootBeamState> states,
                                                            int beamWidth) {
        int normalizedBeamWidth = Math.max(1, beamWidth);
        List<RootBeamState> orderedStates = new ArrayList<>(states);
        orderedStates.sort(BatchBlockStitcher::compareRootStates);
        List<RootBeamState> selectedStates = new ArrayList<>();
        Set<String> signatures = new HashSet<>();

        // 只为宽度大于 1 的 Beam 预留名额；宽度为 1 时保持原有单分支行为。
        int pendingQuota = normalizedBeamWidth <= 1
                ? 0
                : Math.max(1, normalizedBeamWidth / 3);
        int pendingCount = 0;

        for (RootBeamState state : orderedStates) {
            if (!isLargeOuterPendingState(state) || pendingCount >= pendingQuota) {
                continue;
            }

            String signature = rootStateSignature(state);
            if (!signatures.add(signature)) {
                continue;
            }
            selectedStates.add(state);
            pendingCount++;
        }

        for (RootBeamState state : orderedStates) {
            String signature = rootStateSignature(state);
            if (!signatures.add(signature)) {
                continue;
            }
            selectedStates.add(state);
            if (selectedStates.size() >= normalizedBeamWidth) {
                break;
            }
        }
        return selectedStates;
    }

    /**
     * 判断根 Beam 状态是否属于“低填充但仍可继续扩展”的大件外扩分支。
     *
     * 功能说明：该状态不代表最终可输出的组合块，只代表一个需要继续向下搜索的
     * 中间节点。这里不要求当前填充率达到 0.90，避免把未来可能形成高质量闭合的
     * 大件组合提前截断。
     */
    private static boolean isLargeOuterPendingState(RootBeamState state) {
        if (state == null
                || state.block.memberCount() < 2
                || state.block.fillRate >= MIN_LARGE_OUTER_COMBINED_FILL_RATE) {
            return false;
        }

        for (int i = 1; i < state.block.placements.size(); i++) {
            Block.ItemPlacement placement = state.block.placements.get(i);
            if (!placement.item.smallItem && !placement.candidateCavityInsertion) {
                return true;
            }
        }
        return false;
    }

    /**
     * 比较同一个根 A 的拼接方案。
     *
     * 最近一步 A-B 拼接的综合 Score 是主指标；Score 接近时再比较凹腔插入次数、
     * 当前组合块填充率、成员数和累计 score2。这里不再使用所有 Block 的总体填充率，
     * 避免无关工件的状态影响 A 根分支的竞争结果。
     */
    private static int compareRootStates(RootBeamState left, RootBeamState right) {
        if (Math.abs(left.block.lastStitchScore() - right.block.lastStitchScore())
                > PolygonStitcher.SCORE_EPS) {
            // 修改理由：同一层根 Beam 中的状态都是由最近一次 A-B 拼接产生的，
            // 先比较最近一步综合 Score，才能真正实现“每层优先保留高质量拼接”。
            return Double.compare(right.block.lastStitchScore(), left.block.lastStitchScore());
        }
        int leftCavityInsertionCount = countCavityInsertions(left.block);
        int rightCavityInsertionCount = countCavityInsertions(right.block);
        if (leftCavityInsertionCount != rightCavityInsertionCount) {
            // 修改理由：仅按当前填充率排序会让“两个大件外扩”压过“进入大凹腔的较小件”。
            // 先比较真实凹腔插入次数，才能让有后续排样价值的分支留在 Beam 中。
            return Integer.compare(rightCavityInsertionCount, leftCavityInsertionCount);
        }
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

    /** 统计 Block 中真实进入已有外接框的拼接次数，作为根 Beam 的结构质量指标。 */
    private static int countCavityInsertions(Block block) {
        int count = 0;
        for (int i = 1; i < block.placements.size(); i++) {
            if (block.placements.get(i).candidateCavityInsertion) {
                count++;
            }
        }
        return count;
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

    /**
     * 判断一个候选是否确实在改善关键凹腔，而不是普通外边界扩张。
     *
     * 低填充率、非 smallItem、非矩形根工件通常代表需要优先处理的主体凹腔；
     * 同时要求候选中存在真实 cavityInsertion，避免仅凭低填充率误保护普通异形件。
     */
    private static boolean isCriticalCavityCandidate(PolygonItem rootItem, Block block) {
        if (rootItem.smallItem
                || rootItem.rectangular
                || rootItem.fillRate >= CRITICAL_CAVITY_ROOT_FILL_RATE) {
            return false;
        }
        for (int i = 1; i < block.placements.size(); i++) {
            if (block.placements.get(i).candidateCavityInsertion) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算关键凹腔候选的优先收益。
     *
     * 凹腔初始填充率越低，后续可排样风险越大，因此在相同填充提升下给予更高权重。
     * 该值只用于候选集合之间的排序，不改变 Block 自身的几何填充率。
     */
    private static double calculateCriticalCavityGain(PolygonItem rootItem, double fillRateGain) {
        double cavityUrgency = Math.max(0.0, 1.0 - rootItem.fillRate);
        return fillRateGain * (1.0 + cavityUrgency);
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
     * PolygonStitcher 会对所有允许角度和 NFP 外/孔洞轮廓进行评分，并返回有限个
     * 最优合法位置。这里把每个位置分别构造成根 Beam 的后继；smallItem 默认优先
     * 填补已有凹腔，非 smallItem 的普通外边界候选仍必须通过 score2 和明显收益检查。
     * Block 级别的旋转、重叠、低质量大件外扩和板材尺寸校验全部在创建 Block 前完成。
     */
    private static List<Block> tryAddItems(Block block,
                                           PolygonItem item,
                                           int candidateLimit,
                                           Map<String, PolygonStitcher.StitchingResult> nfpCache) {
        List<Block> childBlocks = new ArrayList<>();
        if (!block.canStitchWith(item)) {
            return childBlocks;
        }

        List<Integer> relativeRotations = block.relativeRotationsFor(item);
        List<List<Point>> fixedPolygons = block.placedPolygons();
        String cacheKey = stitchInputSignature(fixedPolygons, block.areaSum, block.boxArea,
                item.points, item.area, relativeRotations, item.smallItem, candidateLimit);
        PolygonStitcher.StitchingResult nfpResult = nfpCache.get(cacheKey);
        if (nfpResult == null) {
            nfpResult = PolygonStitcher.findTopStitchesForFixedPolygons(
                    fixedPolygons,
                    block.areaSum,
                    block.boxArea,
                    item.points,
                    item.area,
                    relativeRotations,
                    item.smallItem,
                    candidateLimit);
            nfpCache.put(cacheKey, nfpResult);
        }

        if (!nfpResult.stitched) {
            return childBlocks;
        }

        // 每个候选位置都是独立的根 Beam 后继；某个位置因尺寸或旋转约束失败时，
        // 不能连带丢弃同一工件对的其他合法位置。
        for (PolygonStitcher.StitchingCandidate candidate : nfpResult.topCandidates(candidateLimit)) {
            Block childBlock = tryBuildChildBlock(block, item, candidate);
            if (childBlock != null) {
                childBlocks.add(childBlock);
            }
        }
        return childBlocks;
    }

    /**
     * 对一个 NFP 候选执行 Block 级别的最终校验并创建子 Block。
     *
     * 功能说明：NFP 层负责候选轮廓、接触和并集连通性；本方法负责外层搜索特有的
     * 工件旋转、板材尺寸和单步几何收益校验。低质量大件外扩属于“最终组合”规则，
     * 统一在 searchTopBlocksFromRoot() 的终止节点阶段判断，避免中间分支被提前截断。
     */
    private static Block tryBuildChildBlock(Block block,
                                             PolygonItem item,
                                             PolygonStitcher.StitchingCandidate candidate) {
        if (candidate == null) {
            return null;
        }

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

        if (item.smallItem
                && !candidate.cavityInsertion
                && !PolygonStitcher.isHighQualityOuterClosure(candidate)) {
            // 修改理由：解除 smallItem 对高质量互补闭合的绝对禁止，但继续阻止普通小件外扩。
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

    /**
     * 判断最终终止块是否为低质量的大件外边界组合。
     *
     * 功能说明：该方法只在根 Beam 的终止节点上调用。这样中间的 A+B 即使填充率较低，
     * 仍可继续尝试 A+B+C；只有整条分支确实无法继续扩展且最终填充率仍低于阈值时，
     * 才将其作为无效大件外扩过滤掉。
     */
    private static boolean isLowQualityLargeOuterBlock(Block block) {
        if (block == null
                || block.memberCount() < 2
                || block.fillRate >= MIN_LARGE_OUTER_COMBINED_FILL_RATE) {
            return false;
        }

        boolean hasLargeOuterExpansion = false;
        for (int i = 1; i < block.placements.size(); i++) {
            Block.ItemPlacement placement = block.placements.get(i);
            if (!placement.item.smallItem && !placement.candidateCavityInsertion) {
                hasLargeOuterExpansion = true;
            }
        }

        // 纯凹腔插入块可以保留较低填充率，因为它没有扩大大件组合的外接框；
        // 但只要存在大件普通外扩，终止块就必须达到 0.90，否则不能借助一个小件
        // 绕过低质量大件过滤。中间节点不会调用此方法，仍可继续尝试 A+B+C。
        return hasLargeOuterExpansion;
    }

    /** 以全部固定工件的几何坐标构造 NFP 缓存键，确保不同内部孔洞的 Block 不会错误复用结果。 */
    private static String stitchInputSignature(List<List<Point>> basePolygons,
                                               double baseArea,
                                               double baseBoxArea,
                                               List<Point> itemPolygon,
                                               double itemArea,
                                               List<Integer> rotations,
                                               boolean requireCavityInsertion,
                                               int candidateLimit) {
        StringBuilder signature = new StringBuilder();
        signature.append(baseArea).append('|')
                .append(baseBoxArea).append('|')
                .append(itemArea).append('|')
                .append(rotations).append('|')
                // 修改理由：缓存结果现在包含多个候选位置；不同 Top-K 上限不能混用，
                // 否则较小上限生成的结果会错误地限制后续更宽的 Beam。
                .append(candidateLimit).append('|');
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
        // 显式输出 Sbox 别名；candidateScore2 保留用于兼容历史结果读取程序。
        writer.write(String.format(Locale.ROOT, "    candidateSBox=%.6f", placement.candidateScore2));
        writer.newLine();
        // 输出 Sarea 和综合 Score，便于检查权重变化是否真正影响了最终候选位置。
        writer.write(String.format(Locale.ROOT, "    candidateSArea=%.6f", placement.candidateSArea));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "    candidateCombinedScore=%.6f",
                placement.candidateCombinedScore));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "    candidateContactLength=%.6f", placement.candidateContactLength));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "    candidateMinBoundaryDistance=%.6f",
                placement.candidateMinBoundaryDistance));
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "    candidateCombinedFillRate=%.6f",
                placement.candidateCombinedFillRate));
        writer.newLine();
        // 输出凹腔标记，便于诊断全局资源分配是否优先使用了真正的内部插入位置。
        writer.write("    candidateCavityInsertion=" + placement.candidateCavityInsertion);
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
     * 一个根工件生成的候选 Block 及其全局资源评价信息。
     *
     * 该对象只描述候选，不表示已经提交到最终结果；itemIds 中的工件只有在全局方案
     * 确认后才会从 availableItems 删除。这样可以把“局部填充率”和“凹腔资源价值”
     * 一起交给外层 Beam Search 比较。
     */
    private static final class CandidateBlock {
        private final PolygonItem rootItem;
        private final Block block;
        private final Set<String> itemIds;
        private final boolean criticalCavity;
        private final double criticalGain;
        private final double fillRateGain;
        private final int cavityInsertionCount;
        private final Set<String> smallItemIds;
        private final double opportunityCost;

        private CandidateBlock(PolygonItem rootItem,
                               Block block,
                               Set<String> itemIds,
                               boolean criticalCavity,
                               double criticalGain,
                               double fillRateGain,
                               int cavityInsertionCount,
                               double opportunityCost) {
            this.rootItem = rootItem;
            this.block = block;
            this.itemIds = Set.copyOf(itemIds);
            this.criticalCavity = criticalCavity;
            this.criticalGain = criticalGain;
            this.fillRateGain = fillRateGain;
            this.cavityInsertionCount = cavityInsertionCount;
            // 候选创建时缓存 smallItem 集合，避免资源预留和过滤阶段重复遍历 Block。
            this.smallItemIds = Set.copyOf(collectSmallItemIds(block));
            this.opportunityCost = opportunityCost;
        }

        /** 根据根工件和最终 Block 创建候选评价对象。 */
        private static CandidateBlock from(PolygonItem rootItem, Block block) {
            Set<String> itemIds = collectItemIds(block);
            boolean criticalCavity = isCriticalCavityCandidate(rootItem, block);
            double fillRateGain = Math.max(0.0, block.fillRate - rootItem.fillRate);
            double criticalGain = criticalCavity
                    ? calculateCriticalCavityGain(rootItem, fillRateGain)
                    : 0.0;
            int cavityInsertionCount = countCavityInsertions(block);
            return new CandidateBlock(rootItem, block, itemIds, criticalCavity,
                    criticalGain, fillRateGain, cavityInsertionCount, 0.0);
        }

        /** 添加机会成本后创建不可变的新候选对象。 */
        private CandidateBlock withOpportunityCost(double value) {
            return new CandidateBlock(rootItem, block, itemIds, criticalCavity,
                    criticalGain, fillRateGain, cavityInsertionCount, value);
        }

        /** 返回候选块中所有 smallItem 的 ID，用于判断是否抢占预留小件。 */
        private Set<String> smallItemIds() {
            return smallItemIds;
        }

        /**
         * 返回候选块中除根工件之外新增的 smallItem ID。
         *
         * 机会成本只针对新增小件计算，避免把根工件本身重复计入资源消耗；
         * 预留过滤则使用 smallItemIds()，因此仍能识别 smallItem 根节点候选。
         */
        private Set<String> addedSmallItemIds() {
            Set<String> addedSmallItemIds = new HashSet<>();
            for (Block.ItemPlacement placement : block.placements) {
                if (placement.item.smallItem && !placement.item.id.equals(rootItem.id)) {
                    addedSmallItemIds.add(placement.item.id);
                }
            }
            return addedSmallItemIds;
        }

        /** 全局方案比较使用的普通收益，机会成本只调节普通候选，不否决关键凹腔。 */
        private double effectiveGain() {
            return fillRateGain - OPPORTUNITY_COST_WEIGHT * opportunityCost;
        }
    }

    /**
     * 外层全局候选集合 Beam 的状态。
     *
     * 状态中的 usedItemIds 保证同一件工件不会同时进入两个 Block；关键凹腔收益、
     * 机会成本、普通小件消耗和普通填充收益分别保存，避免把不同含义的指标压成
     * 一个难以解释的分数。
     */
    private static final class GlobalPlanState {
        private final List<CandidateBlock> selectedCandidates;
        private final Set<String> usedItemIds;
        private final double criticalGain;
        private final int criticalRootCount;
        private final int totalCavityInsertionCount;
        private final int nonCriticalSmallItemCount;
        private final double effectiveGain;
        private final double opportunityCost;
        private final double totalFillRateGain;
        private final double totalScore2;

        private GlobalPlanState(List<CandidateBlock> selectedCandidates,
                                Set<String> usedItemIds,
                                double criticalGain,
                                int criticalRootCount,
                                int totalCavityInsertionCount,
                                int nonCriticalSmallItemCount,
                                double effectiveGain,
                                double opportunityCost,
                                double totalFillRateGain,
                                double totalScore2) {
            this.selectedCandidates = List.copyOf(selectedCandidates);
            this.usedItemIds = Set.copyOf(usedItemIds);
            this.criticalGain = criticalGain;
            this.criticalRootCount = criticalRootCount;
            this.totalCavityInsertionCount = totalCavityInsertionCount;
            this.nonCriticalSmallItemCount = nonCriticalSmallItemCount;
            this.effectiveGain = effectiveGain;
            this.opportunityCost = opportunityCost;
            this.totalFillRateGain = totalFillRateGain;
            this.totalScore2 = totalScore2;
        }

        private static GlobalPlanState empty() {
            return new GlobalPlanState(new ArrayList<>(), new HashSet<>(),
                    0.0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0);
        }

        /** 将一个不冲突的候选块加入当前全局方案。 */
        private GlobalPlanState withCandidate(CandidateBlock candidate) {
            List<CandidateBlock> nextCandidates = new ArrayList<>(selectedCandidates);
            nextCandidates.add(candidate);
            Set<String> nextUsedItemIds = new HashSet<>(usedItemIds);
            nextUsedItemIds.addAll(candidate.itemIds);
            return new GlobalPlanState(
                    nextCandidates,
                    nextUsedItemIds,
                    criticalGain + candidate.criticalGain,
                    criticalRootCount + (candidate.criticalCavity ? 1 : 0),
                    totalCavityInsertionCount + candidate.cavityInsertionCount,
                    nonCriticalSmallItemCount + (candidate.criticalCavity
                            ? 0
                            : candidate.smallItemIds().size()),
                    effectiveGain + candidate.effectiveGain(),
                    opportunityCost + candidate.opportunityCost,
                    totalFillRateGain + candidate.fillRateGain,
                    totalScore2 + candidate.block.score2);
        }

        /** 由已选 Block ID 组成稳定签名，避免全局 Beam 保留完全相同的方案。 */
        private String signature() {
            List<String> blockIds = new ArrayList<>();
            for (CandidateBlock candidate : selectedCandidates) {
                blockIds.add(candidate.block.id);
            }
            blockIds.sort(String::compareTo);
            return String.join("|", blockIds);
        }
    }

    /**
     * 一次根 Beam 搜索的结果。
     *
     * validCompletedStates 是经过终止质量检查、可作为组合块输出的状态；
     * exploredFirstItemIds 是本轮实际进入 Beam 的 A+B 首层物品。
     * 当 validCompletedStates 为空时，调用方只惩罚 exploredFirstItemIds，
     * 让尚未进入 Beam 的候选在下一轮继续竞争。
     */
    private static final class RootSearchResult {
        private final List<RootBeamState> validCompletedStates;
        private final Set<String> exploredFirstItemIds;

        private RootSearchResult(List<RootBeamState> validCompletedStates,
                                 Set<String> exploredFirstItemIds) {
            this.validCompletedStates = List.copyOf(validCompletedStates);
            this.exploredFirstItemIds = Set.copyOf(exploredFirstItemIds);
        }
    }

    /**
     * 以一个根工件 A 为中心的 Beam 节点。
     *
     * block 保存当前 A 根拼接出的完整几何；usedItemIds 保存该分支已经消耗的工件，
     * 用于防止同一个工件在同一条拼接路径中重复加入。firstAddedItemId 记录 A 的直接
     * 后继工件，用于在一批首层分支全部失败时只惩罚对应的 B、C、D，而不影响更深层搜索。
     * 不同根节点之间不会共享该状态；根级候选即使最终因工件冲突被淘汰，也不会修改其它
     * 根的状态，外层会让未提交成员回池。
     */
    private static final class RootBeamState {
        private final Block block;
        private final Set<String> usedItemIds;
        private final String firstAddedItemId;

        private RootBeamState(Block block,
                              Set<String> usedItemIds,
                              String firstAddedItemId) {
            this.block = block;
            // 每个节点拥有自己的集合副本，避免后续分支互相修改已使用工件集合。
            this.usedItemIds = new HashSet<>(usedItemIds);
            this.firstAddedItemId = firstAddedItemId;
        }

        private static RootBeamState fromRoot(Block rootBlock) {
            Set<String> rootItemIds = new HashSet<>();
            for (Block.ItemPlacement placement : rootBlock.placements) {
                rootItemIds.add(placement.item.id);
            }
            // 根节点还没有首层后继，使用 null 表示当前节点正处于 A 本身。
            return new RootBeamState(rootBlock, rootItemIds, null);
        }

        /** 创建加入一个新工件后的子节点，并保留父节点的使用集合。 */
        private RootBeamState withAddedItem(PolygonItem item, Block childBlock) {
            Set<String> nextUsedItemIds = new HashSet<>(usedItemIds);
            nextUsedItemIds.add(item.id);
            // 首次加入的物品是该分支的 A+B 中的 B；后续加入物品继续继承该值。
            String nextFirstAddedItemId = firstAddedItemId == null
                    ? item.id
                    : firstAddedItemId;
            return new RootBeamState(childBlock, nextUsedItemIds, nextFirstAddedItemId);
        }
    }

}
