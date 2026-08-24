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

