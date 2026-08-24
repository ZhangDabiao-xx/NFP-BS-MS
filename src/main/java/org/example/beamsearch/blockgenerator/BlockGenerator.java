package org.example.beamsearch.blockgenerator;

import org.example.beamsearch.common.Box;
import org.example.beamsearch.common.Instance;

import java.util.*;

public class BlockGenerator {
    private final int maxSBCount;
    private final int maxBlockCount;
    private double minUtilization;
    private final Instance inst;

    public BlockGenerator(Instance inst) {
        this.inst = inst;
        this.maxSBCount = 10000;
        this.maxBlockCount = 10000;
        this.minUtilization = 1;
    }

    public static class BlockComparator implements Comparator<GeneralBlock> {
        @Override
        public int compare(GeneralBlock o1, GeneralBlock o2) {
            int result = o1.length - o2.length;
            if (result != 0) {
                return result;
            }
            result = o1.width - o2.width;
            if (result != 0) {
                return result;
            }
            result = o1.component.length - o2.component.length;
            if (result != 0) {
                return result;
            }

            for (int i = 0; i < o1.component.length; i++) {
                int t1 = o1.component[i];
                int t2 = o2.component[i];
                result = t1 - t2;
                if (result != 0) {
                    return result;
                }
                result = o1.typeCount[t1] - o2.typeCount[t2];
                if (result != 0) {
                    return result;
                }
            }

            return 0;
        }
    }

    private TreeSet<GeneralBlock> generatedBlockSet = null;

    public GeneralBlock[] generateSingleBlock(boolean isOrder) {
        GeneralBlock[] list = null;
        generatedBlockSet = new TreeSet<GeneralBlock>(new BlockComparator());
        ArrayList<GeneralBlock> result = null;

        result = generateSingleBox();

        list = new GeneralBlock[result.size()];

        for (int i = 0; i < result.size(); ++i) {
            list[i] = result.get(i);
        }

        if (isOrder){
            Arrays.sort(list, (o1, o2) -> {
                double diff = o1.scoreVolume - o2.scoreVolume;
                if (diff < 0){
                    return 1;
                } else if (diff > 0) {
                    return -1;
                } else {
                    double d = o1.length - o2.length;
                    if (d < 0){
                        return 1;
                    } else if (d > 0){
                        return -1;
                    } else {
                        return 0;
                    }
                }
            });
        }

        return list;
    }

    private ArrayList<GeneralBlock> generateSingleBox() {
        ArrayList<GeneralBlock> list = new ArrayList<GeneralBlock>();
        for (Box box : inst.boxes) {
            if (box.count == 0) {
                continue;
            }
            perBox: for (int ortIdx = 0; ortIdx < box.variation.length; ortIdx++) {
                int[] variation = box.variation[ortIdx];
                int l = variation[1];
                int w = variation[0];
                if (l > inst.length  || w > inst.width ) {
                    continue;
                }
                for (int j = 0; j < ortIdx; j++) {
                    if (l == box.variation[j][1] && w == box.variation[j][0]) {
                        continue perBox;
                    }
                }

                GeneralBlock gb = new GeneralBlock(1, 1, box, ortIdx, inst.boxes.length);
                list.add(gb);
            }
        }
        return list;
    }

    public static GeneralBlock[] retainFeasibleBlocks(int[] freeBoxes, GeneralBlock[] blockList) {
        boolean[] removed = new boolean[blockList.length];
        int count = blockList.length;

        for (int i = 0; i < blockList.length; ++i) {
            GeneralBlock b = blockList[i];
            if (!usable(freeBoxes, b)) {
                removed[i] = true;
                count--;
            }
        }
        GeneralBlock[] newGeneralBlockList = new GeneralBlock[count];
        count = 0;
        for (int i = 0; i < blockList.length; ++i) {
            if (!removed[i]) {
                newGeneralBlockList[count++] = blockList[i];
            }
        }
        return newGeneralBlockList;
    }

    private static boolean usable(int[] freeBoxes, GeneralBlock b) {
        for (int i : b.component) {
            if (freeBoxes[i] < b.typeCount[i]) {
                return false;
            }
        }
        return true;
    }


}

