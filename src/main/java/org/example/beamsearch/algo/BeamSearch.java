package org.example.beamsearch.algo;

import org.example.beamsearch.blockgenerator.BlockGenerator;
import org.example.beamsearch.blockgenerator.GeneralBlock;
import org.example.beamsearch.common.*;
import org.example.beamsearch.spacemanager.SpaceManager;
import org.example.beamsearch.state.State;

import java.util.*;

public class BeamSearch {
    private final SpaceManager spaceManager;
    final Instance inst;

    /**
     * 连续完成多少轮“全部低利用率板材均未带来改进”后停止全局重排。
     * 该值只作为无改进保护，真正的主停止条件仍然是 maxTime。
     */
    private static final int MAX_NO_IMPROVEMENT_SWEEPS = 3;

    private long finishTime = 0;

    public BeamSearch(SpaceManager spaceManager, Instance inst) {
        this.inst = inst;
        this.spaceManager = spaceManager;
    }

    /**
     * 兼容旧调用方的相对时间入口。
     *
     * <p>新流程应优先使用 {@link #solveUntil(long, int)}，这样多个阶段
     * 可以共享同一个全局截止时间。这里保留原方法，避免其他入口的调用
     * 方式被本次时间调度修改破坏。</p>
     *
     * @param timeLimit 本次求解允许使用的时间，单位为毫秒
     * @param minCon 原有的最低装载约束参数
     * @return 本次求解结果
     */
    public ExecutionResult solve(int timeLimit, int minCon) {
        long deadlineMillis = System.currentTimeMillis() + Math.max(1, timeLimit);
        return solveUntil(deadlineMillis, minCon);
    }

    /**
     * 在指定的绝对截止时间之前逐板求解普通新板排样。
     *
     * <p>原实现会给每一张板材重复分配同样的 timeLimit，板材数量增加时
     * 总耗时也会线性增加。本方法把截止时间传入逐板循环，每张板材只使用
     * 当前全局剩余时间，从而让优先件求解或普通件求解不会越过总预算。</p>
     *
     * @param deadlineMillis 全局绝对截止时间，使用 System.currentTimeMillis() 的时间基准
     * @param minCon 原有的最低装载约束参数
     * @return 截止时间前得到的本次求解结果
     */
    public ExecutionResult solveUntil(long deadlineMillis, int minCon) {
        ExecutionResult exeResult = new ExecutionResult();
        int containerNum = 1;
        State endState = null;
        long start = System.currentTimeMillis();

        // 后续阶段可能在进入本方法前已经耗尽全局预算。此时直接返回
        // 初始未排数量，避免为了生成候选块又额外消耗无效时间。
        if (System.currentTimeMillis() >= deadlineMillis) {
            int[] initialFreeBoxes = new int[inst.boxes.length];
            for (int i = 0; i < inst.boxes.length; i++) {
                initialFreeBoxes[i] = inst.boxes[i].count;
            }
            fillUnplacedBoxes(exeResult, initialFreeBoxes, null);
            return exeResult;
        }

        GeneralBlock[] allBlocks = new BlockGenerator(inst).generateSingleBlock(true);
        while (allBlocks.length > 0 && System.currentTimeMillis() < deadlineMillis) {
            System.out.println("Start nesting on the " + containerNum + "th large board, remaining blocks count: " + allBlocks.length);
            State initialState;
            if (containerNum == 1) {
                initialState = State.createInitState(inst, spaceManager, allBlocks);
            } else {
                initialState = State.initState(endState, spaceManager);
            }

            // 当前板材只使用全局截止时间前的剩余时间，避免逐板重复计时。
            long remainingTime = deadlineMillis - System.currentTimeMillis();
            int boardTime = (int) Math.min(Integer.MAX_VALUE, Math.max(1, remainingTime));

            // 把单张板材的搜索抽成独立方法，后续阶段可以从已有空间继续搜索。
            endState = searchOneBoard(initialState, boardTime, minCon, 0);

            allBlocks = endState.availableBlocks;

            exeResult.solution = endState.toSolution();

            exeResult.solutions.add(exeResult.solution);
            exeResult.boardStates.add(createBoardStateSnapshot(exeResult.solution));
            exeResult.unplacedBoxes = new ArrayList<Box>();
            System.out.println("Utilization of the " + containerNum + "th large board: " + exeResult.solution.getUtilization() + ", Time taken for this board: "
                    + ((System.currentTimeMillis() - start) * 0.001) + "s");
            System.out.println("-------------------------------------------------------------------");
            containerNum++;
        }

        // 原有 solve() 只返回 solutions，没有保存最后一张板材结束后的
        // freeBoxes。统一回填后，调用方可以判断是否仍有工件未排入新板。
        if (endState != null) {
            fillUnplacedBoxes(exeResult, endState.getFreeBoxes(), null);
        } else {
            int[] initialFreeBoxes = new int[inst.boxes.length];
            for (int i = 0; i < inst.boxes.length; i++) {
                initialFreeBoxes[i] = inst.boxes[i].count;
            }
            fillUnplacedBoxes(exeResult, initialFreeBoxes, null);
        }

        return exeResult;
    }

    /**
     * 在已经存在的板材剩余空间中继续排样。
     *
     * <p>该方法专门服务于优先先排的流程。seedBoards 中的矩形会被转成
     * 固定的初始放置块；allowedTypes 决定后续候选，只允许普通件类型进入
     * 搜索。每张优先件板材依次尝试插入普通件，普通件数量在板材之间共享。</p>
     *
     * <p>这是一个阶段性实现：优先件布局本身只保留 BeamSearch 找到的一个
     * 结果。后续若需要比较多个优先件布局，可以让调用方传入多组 seedBoards
     * 并在外层选择最终结果。</p>
     *
     * @param seedBoards 优先件阶段产生的板材快照
     * @param allowedTypes 与 inst.boxes 对应的可继续排样类型
     * @param timeLimit 本次插入允许使用的相对时间，单位为毫秒
     */
    public ExecutionResult packIntoExistingBoards(List<BoardStateSnapshot> seedBoards,
                                                   boolean[] allowedTypes,
                                                   int timeLimit) {
        long deadlineMillis = System.currentTimeMillis() + Math.max(1, timeLimit);
        return packIntoExistingBoardsUntil(seedBoards, allowedTypes, deadlineMillis);
    }

    /**
     * 在已有优先件板材中插入普通件，并共享一个全局绝对截止时间。
     *
     * <p>普通件插入必须保持优先件板材数量不变，因此即使时间已经耗尽，
     * 也要把尚未处理的 seedBoard 原样复制到结果中。这样第三阶段可以
     * 正确继承 Sp，而不会因为超时丢失优先件板材。</p>
     *
     * @param seedBoards 优先件阶段产生的板材快照
     * @param allowedTypes 与 inst.boxes 对应的可继续排样类型
     * @param deadlineMillis 全局绝对截止时间，使用 System.currentTimeMillis() 的时间基准
     * @return 插入普通件后的板材结果
     */
    public ExecutionResult packIntoExistingBoardsUntil(List<BoardStateSnapshot> seedBoards,
                                                        boolean[] allowedTypes,
                                                        long deadlineMillis) {
        ExecutionResult result = new ExecutionResult();
        if (seedBoards == null || seedBoards.isEmpty()) {
            // 没有优先件 seedBoard 时，普通件仍然没有开始插入，必须把
            // 允许类型的完整剩余数量传给第三阶段，不能误报为已排完。
            fillUnplacedBoxes(result, initialFreeBoxes(allowedTypes), allowedTypes);
            return result;
        }

        int[] freeBoxes = initialFreeBoxes(allowedTypes);

        // 如果前一阶段已经耗尽总预算，不再生成普通件候选块，但仍然需要
        // 原样复制所有优先件 seedBoard，保证 Sp 的结构和数量不丢失。
        if (System.currentTimeMillis() >= deadlineMillis) {
            for (BoardStateSnapshot seedBoard : seedBoards) {
                Solution unchangedSolution = seedBoard.toSolution(inst);
                result.solutions.add(unchangedSolution);
                result.boardStates.add(createBoardStateSnapshot(unchangedSolution));
            }
            fillUnplacedBoxes(result, freeBoxes, allowedTypes);
            result.setAvgUtilization();
            return result;
        }

        GeneralBlock[] availableBlocks = new BlockGenerator(inst)
                .generateSingleBlock(true, allowedTypes);

        for (int boardIndex = 0; boardIndex < seedBoards.size(); boardIndex++) {
            BoardStateSnapshot seedBoard = seedBoards.get(boardIndex);

            // 普通件已经全部放完时，后面的优先件板材仍然要保留在结果中。
            // 如果全局时间已经耗尽，也必须原样保留当前和后续优先件板材，
            // 不能用“至少 1 毫秒”的旧逻辑继续突破总预算。
            if (availableBlocks.length == 0
                    || hasNoAllowedBoxes(freeBoxes, allowedTypes)
                    || System.currentTimeMillis() >= deadlineMillis) {
                Solution unchangedSolution = seedBoard.toSolution(inst);
                result.solutions.add(unchangedSolution);
                result.boardStates.add(createBoardStateSnapshot(unchangedSolution));
                continue;
            }

            SpaceManager residualSpaceManager = createResidualSpaceManager(seedBoard);
            PlacedBlock[] fixedPriorityBlocks = createPlacedBlocks(seedBoard);
            State initialState = State.createSeededState(
                    inst,
                    residualSpaceManager,
                    freeBoxes,
                    availableBlocks,
                    fixedPriorityBlocks);

            int remainingBoards = seedBoards.size() - boardIndex;
            long remainingTime = deadlineMillis - System.currentTimeMillis();
            int boardTime = (int) Math.max(1, remainingTime / Math.max(1, remainingBoards));

            // volumeType=1 使用实际矩形面积作为评分，目标是尽可能多地
            // 把普通件放入已有的优先件板材。
            State endState = searchOneBoard(initialState, boardTime, 0, 1);
            Solution solution = endState.toSolution();
            result.solutions.add(solution);
            result.boardStates.add(createBoardStateSnapshot(solution));

            freeBoxes = endState.getFreeBoxes().clone();
            availableBlocks = BlockGenerator.retainFeasibleBlocks(freeBoxes, availableBlocks);
        }

        fillUnplacedBoxes(result, freeBoxes, allowedTypes);
        result.setAvgUtilization();
        return result;
    }

    /**
     * 对一个初始 State 执行单张板材的集束搜索。
     */
    private State searchOneBoard(State initialState,
                                 int timeLimit,
                                 int minCon,
                                 int volumeType) {
        Node bestNode = new Node();
        long startTime = System.currentTimeMillis();
        finishTime = startTime + Math.max(1, timeLimit);

        int availableBlockCount = initialState.availableBlocks.length;
        for (int width = 4; System.currentTimeMillis() < finishTime; width <<= 1) {
            if (width > availableBlockCount / 3 && width != 4 && minCon > 330) {
                break;
            }

            Queue<Node> newNodes = new LinkedList<>();
            Node root = new Node();
            root.state = initialState;
            newNodes.add(root);

            if (System.currentTimeMillis() >= finishTime && width > 4) {
                break;
            }

            while (!newNodes.isEmpty()) {
                int limit = newNodes.size();
                TreeSet<Node> offspring = new TreeSet<>();

                if (System.currentTimeMillis() >= finishTime && width > 4) {
                    break;
                }

                for (int i = 0; i < limit; i++) {
                    if (System.currentTimeMillis() >= finishTime && width > 4) {
                        break;
                    }

                    Node currentNode = newNodes.poll();
                    ArrayList<Node> children = new ArrayList<>();
                    int childCount = currentNode.state.countPlacedBlock() == 0
                            ? width * width
                            : width;

                    blockSearch(currentNode.state, childCount, children, volumeType);

                    if (children.isEmpty()) {
                        if (currentNode.score >= bestNode.score) {
                            bestNode = currentNode;
                        }
                    } else {
                        for (Node child : children) {
                            update(offspring, width, child);
                        }
                    }
                }

                for (Node node : offspring) {
                    newNodes.add(node);
                }

                if (System.currentTimeMillis() >= finishTime && width > 4) {
                    break;
                }
            }

            if (width >= availableBlockCount) {
                break;
            }
        }

        // 没有可扩展候选时，root 仍然是一个有效状态。
        return bestNode.state == null ? initialState : bestNode.state;
    }

    private BoardStateSnapshot createBoardStateSnapshot(Solution solution) {
        ArrayList<Space> residualSpaces = SpaceManager.calculateResidualSpaces(
                inst.length,
                inst.width,
                solution.getPlacedCuboid());
        return new BoardStateSnapshot(solution.getPlacedCuboid(), residualSpaces);
    }

    private SpaceManager createResidualSpaceManager(BoardStateSnapshot snapshot) {
        Comparator<Space> comparator = SpaceComparator.getSpaceComparator(inst, 1);
        SpaceManager manager = new SpaceManager(comparator);
        manager.insert(new ArrayList<>(snapshot.getRemainingSpaces()));
        return manager;
    }

    private PlacedBlock[] createPlacedBlocks(BoardStateSnapshot snapshot) {
        List<PlacedCuboid> placedCuboids = snapshot.getPlacedCuboids();
        PlacedBlock[] placedBlocks = new PlacedBlock[placedCuboids.size()];

        for (int i = 0; i < placedCuboids.size(); i++) {
            PlacedCuboid placedCuboid = placedCuboids.get(i);
            int orientationIndex = findOrientationIndex(placedCuboid);
            GeneralBlock block = new GeneralBlock(
                    1,
                    1,
                    placedCuboid.box,
                    orientationIndex,
                    inst.boxes.length);
            placedBlocks[i] = new PlacedBlock(placedCuboid.x, placedCuboid.y, block);
        }
        return placedBlocks;
    }

    private int findOrientationIndex(PlacedCuboid placedCuboid) {
        if (placedCuboid.box.variation != null) {
            for (int i = 0; i < placedCuboid.box.variation.length; i++) {
                int length = placedCuboid.box.variation[i][1];
                int width = placedCuboid.box.variation[i][0];
                if (length == placedCuboid.length && width == placedCuboid.width) {
                    return i;
                }
            }
        }

        if (placedCuboid.ortIdx >= 0
                && placedCuboid.box.variation != null
                && placedCuboid.ortIdx < placedCuboid.box.variation.length) {
            return placedCuboid.ortIdx;
        }
        return 0;
    }

    private int[] initialFreeBoxes(boolean[] allowedTypes) {
        int[] freeBoxes = new int[inst.boxes.length];
        for (int i = 0; i < inst.boxes.length; i++) {
            if (allowedTypes == null || (i < allowedTypes.length && allowedTypes[i])) {
                freeBoxes[i] = inst.boxes[i].count;
            }
        }
        return freeBoxes;
    }

    private boolean hasNoAllowedBoxes(int[] freeBoxes, boolean[] allowedTypes) {
        for (int i = 0; i < freeBoxes.length; i++) {
            boolean allowed = allowedTypes == null
                    || (i < allowedTypes.length && allowedTypes[i]);
            if (allowed && freeBoxes[i] > 0) {
                return false;
            }
        }
        return true;
    }

    private void fillUnplacedBoxes(ExecutionResult result,
                                   int[] freeBoxes,
                                   boolean[] allowedTypes) {
        result.unplacedBoxes = new ArrayList<>();
        result.unplacedCounts = freeBoxes.clone();
        result.unplacedBoxesVol = 0;

        for (int i = 0; i < freeBoxes.length; i++) {
            boolean allowed = allowedTypes == null
                    || (i < allowedTypes.length && allowedTypes[i]);
            if (!allowed || freeBoxes[i] <= 0) {
                continue;
            }

            result.unplacedBoxes.add(inst.boxes[i]);
            result.unplacedBoxesVol += inst.boxes[i].volume * freeBoxes[i];
        }
    }

    public ExecutionResult multipSolve(Instance newInst, int containerNum, boolean decNode) {
        ExecutionResult exeResult = new ExecutionResult();

        GeneralBlock[] allBlocks = new BlockGenerator(newInst).generateSingleBlock(false);

        Arrays.sort(allBlocks, (o1, o2) -> {
            double diff = o1.boxVolume - o2.boxVolume;
            if (diff < 0) {
                return 1;
            } else if (diff > 0) {
                return -1;
            } else {
                double d = o1.length - o2.length;
                if (d < 0) {
                    return 1;
                } else if (d > 0) {
                    return -1;
                } else {
                    return 0;
                }
            }
        });

        Node bestNode = new Node();

        int WIDTH = 10;
        if (decNode) {
            WIDTH = allBlocks.length / 3;
        }
        State state;
        state = State.createMultipleInitState(newInst, spaceManager, allBlocks, containerNum);

        Queue<Node> newNodes = new LinkedList<>();
        Node root = new Node();
        root.state = state;
        newNodes.add(root);
        while (!newNodes.isEmpty() && newNodes != null) {
            int limit = newNodes.size();
            TreeSet<Node> offspring = new TreeSet<Node>();
            for (int i = 0; i < limit; i++) {
                Node curNode = newNodes.poll();
                ArrayList<Node> curChild = new ArrayList<Node>();
                int childCount = (curNode.state.countPlacedBlock() == 0 ? WIDTH * WIDTH : WIDTH);
                blockSearch(curNode.state, childCount, curChild, 1);
                if (curChild.isEmpty()) {
                    if (curNode.score >= bestNode.score) {
                        bestNode = curNode;
                    }
                } else {
                    for (int j = 0; j < curChild.size(); j++) {
                        update(offspring, WIDTH, curChild.get(j));
                    }
                }
            }
            for (Node node : offspring) {
                newNodes.add(node);
            }

        }
        for (int i = 0; i < containerNum; i++) {

                exeResult.solutions.add(bestNode.state.toSolution(i));

        }


        exeResult.unplacedBoxes = new ArrayList<Box>();
        exeResult.unplacedBoxesVol = 0;

        for (int i = 0; i < bestNode.state.getFreeBoxes().length; i++) {
            if (bestNode.state.getFreeBoxes()[i] > 0) {
                exeResult.unplacedBoxes.add(newInst.boxes[i]);
                exeResult.unplacedBoxesVol += newInst.boxes[i].volume;
            }
        }

        return exeResult;
    }

    /**
     * 通过反复移出低利用率板材并尝试重排，优化跨板材的整体装载结果。
     *
     * <p>修改原因：原实现一旦成功减少一张板材，就直接退出整个方法；
     * 同时一次候选遍历没有改进时也容易被误认为全局优化已经结束。
     * 现在只结束当前板材的局部尝试，成功减板后会基于最新结果继续寻找
     * 下一张可删除的板材，直到达到时间上限或连续多轮完整扫描都没有改进。</p>
     */
    public ExecutionResult ImproveByRepack(ExecutionResult executionResult, double maxTime, Random random) {
        System.out.println("Start improving solution by repacking.");
        long startTime = System.currentTimeMillis();
        int containerNum = executionResult.solutions.size();
        List<Integer> locations = new ArrayList<>();

        int location;
        // 修改原因：flag 只记录了若干次“没有可选板材”，不能明确表示
        // 是否已经完整扫描过所有低利用率板材。这里改为记录完整无改进轮数。
        int noImprovementSweeps = 0;

        while (true) {
            // 时间上限仍然是全局优化的第一停止条件。
            if ((System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                break;
            }

            // 修改原因：减板后 solutions 数量会变化，不能继续使用初始板材数。
            // 每轮重新读取数量，保证后续索引和候选集合与当前结果一致。
            containerNum = executionResult.solutions.size();
            if (containerNum <= 1) {
                // 只剩一张板材时没有继续减板的可能。
                break;
            }

            ArrayList<Box> unplacedBox = new ArrayList<>();
            double unplacedBoxVol = 0;

            double allAvgUtilization = executionResult.avgUtilization;
            if (containerNum <= 200) {
                allAvgUtilization = 100;
            } else {
                if (allAvgUtilization < 90) {
                    allAvgUtilization = 90;
                }
            }
            double maxUtilization = 100;
            boolean decNode = false;
            if (containerNum >= 350 && allAvgUtilization < 94 && executionResult.solution.getInst().boxes[0].containerOrientId != 0) {
                maxUtilization = 97;
                decNode = true;
            }

            System.out.println("Selecting boards with low utilization for repacking...");
            location = -1;
            double u = Double.MAX_VALUE;

            for (int i = 0; i < containerNum; i++) {
                if (locations.contains(i)) {
                    continue;
                }
                if (executionResult.solutions.get(i).getUtilization() < u) {
                    location = i;
                    u = executionResult.solutions.get(location).getUtilization();
                }
            }

            if (location == -1 || executionResult.solutions.get(location).getUtilization() > allAvgUtilization) {
                // 修改原因：只有在所有候选板材都被尝试后才结束一轮，不能
                // 因为某一次局部尝试无效就直接停止整个优化。
                locations.clear();
                noImprovementSweeps++;
                System.out.println("Completed repack sweep without new board candidate. "
                        + "No-improvement sweeps: " + noImprovementSweeps
                        + "/" + MAX_NO_IMPROVEMENT_SWEEPS);
                if (noImprovementSweeps >= MAX_NO_IMPROVEMENT_SWEEPS) {
                    // 这是无改进保护，不替代前面的时间限制。
                    break;
                }
                continue;
            }

            // 只有确认当前板材满足低利用率条件后才记录，避免把 -1
            // 或不符合条件的索引混入本轮已尝试集合。
            locations.add(location);

            for (int i = 0; i < executionResult.solutions.get(location).getPlacedCuboid().size(); i++) {
                unplacedBox.add(executionResult.solutions.get(location).getPlacedCuboid().get(i).box.copy());
            }
            for (int i = 0; i < unplacedBox.size(); i++) {
                unplacedBoxVol += unplacedBox.get(i).volume;
            }

            ArrayList<Solution> newSolutions = new ArrayList<Solution>();
            for (int i = 0; i < containerNum; i++) {
                if (i != location) {
                    newSolutions.add(executionResult.solutions.get(i));
                }
            }

            ArrayList<Integer> pairSet = new ArrayList<>();
            for (int i = 0; i < newSolutions.size(); i++) {
                if (newSolutions.get(i).getUtilization() > maxUtilization) {
                    continue;
                }

                // 增加 a == b 的候选，使被移出的板材可以尝试与某一张
                // 现有板材合并到一张板上。原实现只尝试两张现有板材，
                // 当总板材数较少时无法执行真正的减板优化。
                pairSet.add(i * containerNum + i);

                for (int j = i + 1; j < newSolutions.size(); j++) {
                    if ((newSolutions.get(i).getUtilization() > allAvgUtilization && newSolutions.get(j).getUtilization() > allAvgUtilization)
                            || newSolutions.get(j).getUtilization() > maxUtilization) {
                        continue;
                    }
                    pairSet.add(i * containerNum + j);
                }

            }

            // 修改原因：当前板材已经计入 locations，候选为空时只应跳过
            // 当前板材，继续扫描本轮其他低利用率板材。
            if (pairSet.isEmpty()) {
                continue;
            }

            Collections.shuffle(pairSet, random);
            int iteration = 0;
            int index = 0;
            // 记录当前 pairSet 遍历是否产生过改进；它只控制是否重新
            // 从第一个候选开始，不再承担全局优化的停止职责。
            boolean improvedInCurrentPass = false;
            boolean currentBoardImproved = false;

            // 修改原因：删除原来的 maxIter=1000 硬截止。当前候选有限，
            // 无改进时会在完整遍历后退出；有改进时重新遍历，最终由时间
            // 上限或完整无改进遍历控制，避免在 1000 次时提前截断。
            while (unplacedBox.size() > 0) {
                if ((System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                    break;
                }

                iteration++;
                int a = pairSet.get(index) / containerNum;
                int b = pairSet.get(index) % containerNum;

                ArrayList<Box> boxs = new ArrayList<>(unplacedBox);

                for (int i = 0; i < newSolutions.get(a).getPlacedCuboid().size(); i++) {
                    boxs.add(newSolutions.get(a).getPlacedCuboid().get(i).box.copy());
                }
                if (a != b) {
                    for (int i = 0; i < newSolutions.get(b).getPlacedCuboid().size(); i++) {
                        boxs.add(newSolutions.get(b).getPlacedCuboid().get(i).box.copy());
                    }
                }

                Instance newInst = new Instance(inst, boxs);
                ExecutionResult newSol;
                if (a == b) {
                    newSol = multipSolve(newInst, 1, decNode);
                } else {
                    newSol = multipSolve(newInst, 2, decNode);
                }

                if (newSol.unplacedBoxesVol < unplacedBoxVol) {
                    System.out.println("iter" + iteration +
                            "\t\t Improve solution by repack " + unplacedBoxVol / inst.length / inst.width
                            + "->" + newSol.unplacedBoxesVol / inst.length / inst.width + " , unplacedBoxesSize:"
                            + newSol.unplacedBoxes.size());
                    System.out.println("currentRepackCostTime:" + ((System.currentTimeMillis() - startTime) * 0.001) + "s , currentRepackMaxTime:" + maxTime + "s");
                    unplacedBox = newSol.unplacedBoxes;
                    unplacedBoxVol = newSol.unplacedBoxesVol;
                    if (a == b) {
                        newSolutions.set(a, newSol.solutions.get(0));
                    } else {
                        newSolutions.set(a, newSol.solutions.get(0));
                        newSolutions.set(b, newSol.solutions.get(1));
                    }
                    // 记录当前板材的确发生过严格的重排改进。
                    currentBoardImproved = true;
                    improvedInCurrentPass = true;
                }

                if (index < pairSet.size() - 1) {
                    index++;
                } else {
                    if (improvedInCurrentPass) {
                        // 当前轮有改进，重新完整扫描 pairSet，继续寻找
                        // 是否还能把剩余工件放入现有板材。
                        index = 0;
                        improvedInCurrentPass = false;
                    } else {
                        // 这里只结束当前被移出板材的候选遍历；外层仍会
                        // 继续选择本轮其他低利用率板材。
                        break;
                    }
                }
            }

            if (unplacedBox.size() == 0) {
                System.out.println("Improved success. Continue searching for another removable board.");
                executionResult.solutions = newSolutions;

                // 修改原因：减板后平均利用率和板材数量都已变化，立即
                // 更新状态并清空旧索引，下一轮必须基于新解重新选择板材。
                executionResult.setAvgUtilization();
                containerNum = executionResult.solutions.size();
                locations.clear();
                noImprovementSweeps = 0;
                continue;
            }

            // 时间耗尽时不再调用 getSolutions() 进行额外的重建排样，
            // 避免已经达到预算后又执行一轮不可中断的求解。
            if (!currentBoardImproved
                    || (System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                continue;
            }

            ArrayList<Solution> solutions = getSolutions(unplacedBox);
            if (solutions.size() == 1) {
                executionResult.solutions = newSolutions;
                executionResult.solutions.add(location, solutions.get(0));

                // 当前板材未被删除，但其余板材已经发生严格改进；
                // 接受该布局后重新开始扫描，避免沿用旧布局下的索引顺序。
                executionResult.setAvgUtilization();
                locations.clear();
                noImprovementSweeps = 0;
            }
        }
        return executionResult;
    }

    public void update(TreeSet<Node> offspring, int WIDTH, Node v) {
        if (offspring.contains(v)) {
            Node s = offspring.floor(v);
            if (s.state.getSpaceManager().getTotalSpaceArea() < v.state.getSpaceManager().getTotalSpaceArea()) {
                offspring.remove(s);
                offspring.add(v);
            }
        } else {
            offspring.add(v);
            if (offspring.size() > WIDTH) {
                offspring.remove(offspring.last());
            }
        }
    }

    public class Node implements Comparable<Node> {
        State state = null;
        Double score = -Double.MAX_VALUE;
        private long hash = -1;

        private void buildHash() {
            long result = Arrays.hashCode(state.getFreeBoxes());

            // 仅使用 freeBoxes 会把“同样剩余工件、不同剩余空间”的状态
            // 合并。新增阶段依赖空间形状，因此把空间坐标纳入状态签名。
            ArrayList<Space> spaces = new ArrayList<>(state.getSpaceManager().getSpaceList());
            spaces.sort((left, right) -> {
                int compare = Integer.compare(left.x1, right.x1);
                if (compare != 0) {
                    return compare;
                }
                compare = Integer.compare(left.y1, right.y1);
                if (compare != 0) {
                    return compare;
                }
                compare = Integer.compare(left.x2, right.x2);
                if (compare != 0) {
                    return compare;
                }
                return Integer.compare(left.y2, right.y2);
            });

            for (Space space : spaces) {
                result = 31 * result + space.x1;
                result = 31 * result + space.y1;
                result = 31 * result + space.x2;
                result = 31 * result + space.y2;
            }
            hash = result;
        }

        @Override
        public int compareTo(Node o) {
            double dv = this.score - o.score;
            if (dv > 0) {
                return -1;
            } else if (dv < 0) {
                return 1;
            }
            this.buildHash();
            o.buildHash();
            if (this.hash < o.hash) {
                return -1;
            } else if (this.hash > o.hash) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    private void blockSearch(State state, int width, ArrayList<Node> child, int volumeType) {
        if (state.hasFreeSpace()) {
            Space space = state.chooseBestSpace();
            List<GeneralBlock> candidate = state.chooseBestBlocks(space, width);

            if (candidate.size() == 0) {
                State newState = state.deleteSpace(space);
                blockSearch(newState, width, child, volumeType);
            } else {
                for (GeneralBlock pb : candidate) {
                    State newState = state.packBlock(space, pb);
                    Node newNode = new Node();
                    newNode.state = newState;
                    newNode.score = completeSolution(newNode.state, volumeType);
                    child.add(newNode);
                }
            }
        }
    }

    private double completeSolution(State initState, int volumeType) {
        double score = 0;
        State state = initState;
        while (state.hasFreeSpace()) {
            Space space = state.chooseBestSpace();
            GeneralBlock block = state.chooseBestBlock(space);
            if (block == null) {
                state = state.deleteSpace(space);
            } else {
                state = state.packBlock(space, block);
            }
        }
        if (volumeType == 0) {
            score = state.getScoreVolume();
        } else if (volumeType == 1) {
            score = state.getPackedVolume();
        }

        return score;
    }

    private ArrayList<Solution> getSolutions(ArrayList<Box> unplacedBoxes) {
        ArrayList<Solution> solutions = new ArrayList<>();
        Instance instance = new Instance(inst, unplacedBoxes);
        GeneralBlock[] allBlocks = new BlockGenerator(instance).generateSingleBlock(true);
        State endState = null;
        int containerNum = 1;
        while (allBlocks.length > 0) {
            State state;
            if (containerNum == 1) {
                state = State.createMultipleInitState(instance, spaceManager, allBlocks, 1);
            } else {
                state = State.initState(endState, spaceManager);
            }
            while (state.hasFreeSpace()) {
                Space space = state.chooseBestSpace();
                GeneralBlock block = state.chooseBestBlock(space);
                if (block == null) {
                    state = state.deleteSpace(space);
                } else {
                    state = state.packBlock(space, block);
                }
            }
            allBlocks = state.availableBlocks;
            endState = state;
            containerNum++;
            Solution newSolution = state.toSolution();

            solutions.add(newSolution);
        }
        return solutions;
    }
}
