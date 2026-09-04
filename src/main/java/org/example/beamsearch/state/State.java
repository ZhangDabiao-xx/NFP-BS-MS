package org.example.beamsearch.state;

import org.example.beamsearch.blockgenerator.BlockGenerator;
import org.example.beamsearch.blockgenerator.GeneralBlock;
import org.example.beamsearch.common.*;
import org.example.beamsearch.spacemanager.SpaceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class State {
    private final Instance inst;
    private final double packedVolume;
    private final double scoreVolume;
    private final int[] freeBoxes;
    private final SpaceManager spaceManager;
    public final GeneralBlock[] availableBlocks;
    private final PlacedBlock[] placedBlock;

    public State(Instance inst, double packedVolume, double scoreVolume, int[] freeBoxes, SpaceManager spaceManager,
                 GeneralBlock[] availableBlocks, PlacedBlock[] placedBlock) {
        super();
        this.inst = inst;
        this.packedVolume = packedVolume;
        this.scoreVolume = scoreVolume;
        this.freeBoxes = freeBoxes;
        this.spaceManager = spaceManager;
        this.availableBlocks = availableBlocks;
        this.placedBlock = placedBlock;
    }

    public int[] getFreeBoxes() {
        return freeBoxes;
    }

    /**
     * 返回当前搜索状态使用的板材与工件实例。
     *
     * @return 当前状态的只读 Instance 引用；调用方不得修改其中的 Box 数量
     */
    public Instance getInstance() {
        return inst;
    }

    /**
     * 返回最多若干个按当前启发式排序的剩余空间副本。
     *
     * @param limit 最多返回的空间数量
     * @return 可用于只读候选评分的空间副本列表
     */
    public ArrayList<Space> getCandidateSpaces(int limit) {
        return spaceManager.getBestSpaceCopies(limit);
    }

    /**
     * 返回指定空间中可放置的前若干个候选矩形块。
     *
     * @param space 要评估的剩余空间
     * @param limit 最多返回的候选数量
     * @return 按现有 block 顺序保留的可放置候选
     */
    public ArrayList<GeneralBlock> getFeasibleBlocks(Space space, int limit) {
        if (space == null || limit <= 0) {
            return new ArrayList<>();
        }
        return chooseBestBlocks(space, limit);
    }

    /**
     * 返回当前状态已经放置的块的深复制，用于估算候选接触长度。
     *
     * @return 当前已放置块副本数组
     */
    public PlacedBlock[] getPlacedBlocks() {
        PlacedBlock[] copies = new PlacedBlock[placedBlock.length];
        for (int i = 0; i < placedBlock.length; i++) {
            copies[i] = placedBlock[i].clone();
        }
        return copies;
    }

    /**
     * 计算当前板材内全部已放置块的矩形占用面积。
     *
     * <p>这里使用 {@link GeneralBlock#blockVolume}，而非多边形材料实际面积
     * {@link GeneralBlock#boxVolume}。前者才是 Beam Search 空间切分时已被占据的
     * 矩形区域，适合用于 Q-learning 的碎片率和空间利用状态。</p>
     *
     * @return 已放置矩形块的占用面积之和，包含固定优先件和当前阶段新放置的普通件
     */
    public double getTotalPlacedBlockArea() {
        double totalArea = 0.0;
        for (PlacedBlock block : placedBlock) {
            totalArea += block.block.blockVolume;
        }
        return totalArea;
    }

    public Space chooseBestSpace() {
        return spaceManager.chooseBestSpace();
    }

    public GeneralBlock chooseBestBlock(Space s) {
        for (GeneralBlock b : this.availableBlocks) {
            if (b.length <= s.length() && b.width <= s.width()) {
                return b;
            }
        }
        return null;
    }

    public ArrayList<GeneralBlock> chooseBestBlocks(Space s, int count) {
        ArrayList<GeneralBlock> candidate = new ArrayList<>();
        for (GeneralBlock b : this.availableBlocks) {
            if (b.length <= s.length() && b.width <= s.width()) {
                candidate.add(b);
            }
            if (candidate.size() == count) {
                break;
            }
        }
        return candidate;
    }

    public double getScoreVolume() {
        return this.scoreVolume;
    }

    public double getPackedVolume() {
        return this.packedVolume;
    }

    public int countPlacedBlock() {
        return this.placedBlock.length;
    }


    public static State createInitState(final Instance inst, final SpaceManager spaceManagerReadOnly,
                                        final GeneralBlock[] availableBlocks) {
        int[] freeBoxes = new int[inst.boxes.length];
        for (int i = 0; i < freeBoxes.length; ++i) {
            freeBoxes[i] = inst.boxes[i].count;
        }

        SpaceManager spaceManager = spaceManagerReadOnly.copy();
        spaceManager.insert(new Space(0, 0, inst.length, inst.width));

        State state = new State(inst, 0, 0, freeBoxes, spaceManager, availableBlocks, new PlacedBlock[0]);

        return state;
    }

    /**
     * 从已有板材布局创建一个新的搜索状态。
     *
     * <p>该入口用于分阶段排样：优先件已经通过第一阶段求解并作为
     * placedBlocks 固定在板材上，spaceManagerReadOnly 中只放入几何剩余
     * 空间，availableBlocks 则只包含下一阶段允许放置的普通件。</p>
     *
     * <p>freeBoxes、placedBlocks 和 SpaceManager 都会复制一份，避免多个
     * BeamSearch 分支共享可变状态。</p>
     */
    public static State createSeededState(final Instance inst,
                                          final SpaceManager spaceManagerReadOnly,
                                          final int[] freeBoxes,
                                          final GeneralBlock[] availableBlocks,
                                          final PlacedBlock[] placedBlocks) {
        int[] copiedFreeBoxes = freeBoxes == null ? new int[inst.boxes.length] : freeBoxes.clone();

        PlacedBlock[] copiedPlacedBlocks;
        if (placedBlocks == null) {
            copiedPlacedBlocks = new PlacedBlock[0];
        } else {
            copiedPlacedBlocks = new PlacedBlock[placedBlocks.length];
            for (int i = 0; i < placedBlocks.length; i++) {
                copiedPlacedBlocks[i] = placedBlocks[i].clone();
            }
        }

        return new State(
                inst,
                0,
                0,
                copiedFreeBoxes,
                spaceManagerReadOnly.copy(),
                availableBlocks,
                copiedPlacedBlocks);
    }

    public static State createMultipleInitState(final Instance inst, final SpaceManager spaceManagerReadOnly,
                                                final GeneralBlock[] availableBlocks, int containerNum) {
        int[] freeBoxes = new int[inst.boxes.length];
        for (int i = 0; i < freeBoxes.length; ++i) {
            freeBoxes[i] = inst.boxes[i].count;
        }
        SpaceManager spaceManager = spaceManagerReadOnly.copy();
        for (int i = 0; i < containerNum; i++) {
            spaceManager.insert(new Space(i * inst.length, 0, (i + 1) * inst.length, inst.width));
        }

        State state = new State(inst, 0, 0, freeBoxes, spaceManager, availableBlocks, new PlacedBlock[0]);


        return state;
    }

    public static State initState(State state, final SpaceManager spaceManagerReadOnly) {
        int[] freeBoxes = state.freeBoxes;
        SpaceManager spaceManager = spaceManagerReadOnly.copy();
        spaceManager.insert(new Space(0, 0, state.inst.length, state.inst.width));
        State state1 = new State(state.inst, 0, 0, freeBoxes, spaceManager, state.availableBlocks, new PlacedBlock[0]);
        return state1;
    }

    public boolean hasFreeSpace() {
        return this.spaceManager.hasFreeSpace();
    }

    public State deleteSpace(Space s) {
        SpaceManager spaceManager = this.spaceManager.copy();
        spaceManager.deleteSpace(s);

        return new State(inst, packedVolume, scoreVolume, freeBoxes, spaceManager, availableBlocks, placedBlock);
    }

    public State packBlock(Space s, GeneralBlock b) {
        PlacedBlock pb = s.packBlock(b);

        double packedVolume = this.packedVolume + b.boxVolume;
        double scoreVolume = this.scoreVolume + b.scoreVolume;

        int[] freeBoxes = this.freeBoxes.clone();
        for (int i : b.component) {
            freeBoxes[i] -= b.typeCount[i];
        }

        SpaceManager spaceManager = this.spaceManager.copy();
        updateSpaces(spaceManager, pb, inst, freeBoxes);

        GeneralBlock[] availableBlocks = BlockGenerator.retainFeasibleBlocks(freeBoxes, this.availableBlocks);

        PlacedBlock[] placedBlock = new PlacedBlock[this.placedBlock.length + 1];
        for (int i = 0; i < this.placedBlock.length; i++) {
            placedBlock[i] = this.placedBlock[i];
        }
        placedBlock[this.placedBlock.length] = pb;

        return new State(inst, packedVolume, scoreVolume, freeBoxes, spaceManager, availableBlocks, placedBlock);
    }

    private static Comparator<Space> volumeComparator = new SpaceVolumeComparator();

    private void updateSpaces(SpaceManager spaceManager, PlacedBlock pb, Instance inst, int[] freeBoxesCount) {
        Space taken = new Space(pb.x, pb.y, pb.x + pb.block.length, pb.y + pb.block.width);
        ArrayList<Space> deletedSpaces = spaceManager.deleteOverlap(taken);
        ArrayList<Space> newSpaces = new ArrayList<Space>();
        for (Space space : deletedSpaces) {
            Space intersectPart = space.intersect(taken);
            space.cut(intersectPart, newSpaces);
        }

        Collections.sort(newSpaces, volumeComparator);

        int count = 0;
        Box[] freeBoxes = new Box[freeBoxesCount.length];
        for (int i = 0; i < freeBoxes.length; ++i) {
            if (freeBoxesCount[i] > 0) {
                freeBoxes[count++] = inst.boxes[i];
            }
        }

        ArrayList<Space> validNewSpace = new ArrayList<Space>(newSpaces.size());
        for (Space space : newSpaces) {
            int l = space.length();
            int w = space.width();

            boolean largeEnough = false;
            outer:
            for (int i = 0; i < count; ++i) {
                for (int[] variation : freeBoxes[i].variation) {
                    if (w >= variation[0] && l >= variation[1]) {
                        largeEnough = true;
                        break outer;
                    }
                }
            }

            if (largeEnough) {
                validNewSpace.add(space);
            }
        }

        spaceManager.insert(validNewSpace);
    }

    public Solution toSolution(int idx) {
        Solution solution = new Solution(inst);
        for (PlacedBlock pb : this.placedBlock) {
            int k = pb.x / inst.length;
            if (k == idx) {
                PlacedBlock npBlock = pb.clone();
                npBlock.x -= idx * inst.length;
                solution.add(npBlock);
            }
        }
        return solution;
    }

    public Solution toSolution() {
        Solution solution = new Solution(inst);
        for (PlacedBlock pb : this.placedBlock) {
            solution.add(pb);
        }

        return solution;
    }


    public SpaceManager getSpaceManager() {
        return spaceManager;
    }
}

