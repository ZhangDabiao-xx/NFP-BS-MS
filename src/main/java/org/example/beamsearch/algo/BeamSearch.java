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

    private long finishTime = 0;

    public BeamSearch(SpaceManager spaceManager, Instance inst) {
        this.inst = inst;
        this.spaceManager = spaceManager;
    }

    public ExecutionResult solve(int timeLimit, int minCon) {
        ExecutionResult exeResult = new ExecutionResult();
        int containerNum = 1;
        State endState = null;
        long start = System.currentTimeMillis();

        GeneralBlock[] allBlocks = new BlockGenerator(inst).generateSingleBlock(true);
        while (allBlocks.length > 0) {
            System.out.println("Start nesting on the " + containerNum + "th large board, remaining blocks count: " + allBlocks.length);
            State initialState;
            if (containerNum == 1) {
                initialState = State.createInitState(inst, spaceManager, allBlocks);
            } else {
                initialState = State.initState(endState, spaceManager);
            }

            // 把单张板材的搜索抽成独立方法，后续阶段可以从已有空间继续搜索。
            endState = searchOneBoard(initialState, timeLimit, minCon, 0);

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
     * @param timeLimit 总搜索时间，单位为毫秒
     */
    public ExecutionResult packIntoExistingBoards(List<BoardStateSnapshot> seedBoards,
                                                   boolean[] allowedTypes,
                                                   int timeLimit) {
        ExecutionResult result = new ExecutionResult();
        if (seedBoards == null || seedBoards.isEmpty()) {
            result.unplacedBoxes = new ArrayList<>();
            result.unplacedCounts = new int[inst.boxes.length];
            return result;
        }

        GeneralBlock[] availableBlocks = new BlockGenerator(inst)
                .generateSingleBlock(true, allowedTypes);
        int[] freeBoxes = initialFreeBoxes(allowedTypes);
        long deadline = System.currentTimeMillis() + Math.max(1, timeLimit);

        for (int boardIndex = 0; boardIndex < seedBoards.size(); boardIndex++) {
            BoardStateSnapshot seedBoard = seedBoards.get(boardIndex);

            // 普通件已经全部放完时，后面的优先件板材仍然要保留在结果中。
            if (availableBlocks.length == 0 || hasNoAllowedBoxes(freeBoxes, allowedTypes)) {
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
            long remainingTime = deadline - System.currentTimeMillis();
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

    public ExecutionResult ImproveByRepack(ExecutionResult executionResult, double maxTime, Random random) {
        System.out.println("Start improving solution by repacking.");
        long startTime = System.currentTimeMillis();
        int containerNum = executionResult.solutions.size();
        List<Integer> locations = new ArrayList<>();

        int location;
        int flag = 0;

        while (true) {
            if ((System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                break;
            }
            if (flag >= 3) {
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
            locations.add(location);
            if (location == -1 || executionResult.solutions.get(location).getUtilization() > allAvgUtilization) {
                locations.clear();
                System.out.println("All low-utilization boards have been repacked once. Restarting repacking process...");
                flag++;
                continue;
            }

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
            ArrayList<Integer> pairSet1 = new ArrayList<>();
            for (int i = 0; i < newSolutions.size(); i++) {
                if (newSolutions.get(i).getUtilization() > maxUtilization) {
                    continue;
                }
                for (int j = i + 1; j < newSolutions.size(); j++) {
                    if ((newSolutions.get(i).getUtilization() > allAvgUtilization && newSolutions.get(j).getUtilization() > allAvgUtilization)
                            || newSolutions.get(j).getUtilization() > maxUtilization) {
                        continue;
                    }
                    pairSet.add(i * containerNum + j);
                    pairSet1.add(i * containerNum + j);
                }

            }

            Collections.shuffle(pairSet, random);
            int iter = 0;
            int index = 0;
            boolean improved = false;
            int maxIter = 1000;

            while (iter++ <= maxIter && unplacedBox.size() > 0) {
                if ((System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                    break;
                }

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
                    System.out.println("iter" + iter +
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
                    iter = 0;
                    improved = true;
                }

                if (index < pairSet.size() - 1) {
                    index++;
                } else {
                    if (improved) {
                        index = 0;
                        improved = false;
                    } else {
                        break;
                    }
                }
            }



            if (unplacedBox.size() == 0) {
                System.out.println("Improved success.");
                executionResult.solutions = newSolutions;
                break;
            } else {
                ArrayList<Solution> solutions = getSolutions(unplacedBox);
                if (solutions.size() == 1) {
                    executionResult.solutions = newSolutions;
                    executionResult.solutions.add(location, solutions.get(0));
                }
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
