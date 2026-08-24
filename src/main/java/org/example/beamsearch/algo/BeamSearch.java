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
        outloop:
        while (allBlocks.length > 0) {
            long allTime = System.currentTimeMillis();
            System.out.println("Start nesting on the " + containerNum + "th large board, remaining blocks count: " + allBlocks.length);
            Node bestNode = new Node();
            finishTime = (allTime + timeLimit);


            for (int WIDTH = 4; System.currentTimeMillis() < finishTime; WIDTH <<= 1) {
                if (WIDTH > allBlocks.length / 3 && WIDTH != 4 && minCon > 330) {
                    break;
                }
                State state;

                if (containerNum == 1) {
                    state = State.createInitState(inst, spaceManager, allBlocks);
                } else {
                    state = State.initState(endState, spaceManager);
                }


                Queue<Node> newNodes = new LinkedList<>();
                Node root = new Node();
                root.state = state;
                newNodes.add(root);

                if (System.currentTimeMillis() >= finishTime && WIDTH > 4) {
                    break;
                }

                while (!newNodes.isEmpty()) {
                    int limit = newNodes.size();
                    TreeSet<Node> offspring = new TreeSet<Node>();

                    if (System.currentTimeMillis() >= finishTime && WIDTH > 4) {
                        break;
                    }

                    for (int i = 0; i < limit; i++) {
                        if (System.currentTimeMillis() >= finishTime && WIDTH > 4) {
                            break;
                        }

                        Node curNode = newNodes.poll();
                        ArrayList<Node> curChild = new ArrayList<Node>();
                        int childCount = (curNode.state.countPlacedBlock() == 0 ? WIDTH * WIDTH : WIDTH);

                        blockSearch(curNode.state, childCount, curChild, 0);

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

                    if (System.currentTimeMillis() >= finishTime && WIDTH > 4) {
                        break;
                    }
                }

                if (WIDTH >= allBlocks.length) {
                    break;
                }
            }
            endState = bestNode.state;

            allBlocks = endState.availableBlocks;

            exeResult.solution = endState.toSolution();

            exeResult.solutions.add(exeResult.solution);
            exeResult.unplacedBoxes = new ArrayList<Box>();
            System.out.println("Utilization of the " + containerNum + "th large board: " + exeResult.solution.getUtilization() + ", Time taken for this board: "
                    + ((System.currentTimeMillis() - start) * 0.001) + "s");
            System.out.println("-------------------------------------------------------------------");
            containerNum++;
        }

        return exeResult;
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
            hash = Arrays.hashCode(state.getFreeBoxes());
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
