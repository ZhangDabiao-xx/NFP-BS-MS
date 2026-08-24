package org.example.beamsearch.common;

import java.util.Comparator;

public class SpaceComparator implements Comparator<Space> {

    private int L, W;

    public SpaceComparator(int L, int W) {
        this.L = L;
        this.W = W;
    }

    @Override
    public int compare(Space arg0, Space arg1) {
        int st0 = arg0.cornerDistance(L, W);
        int st1 = arg1.cornerDistance(L, W);

        int result = st0 - st1;

        if (result != 0) {
            return result;
        }

        double comp = arg1.volume - arg0.volume;
        if (comp > 0) {
            return 1;
        } else if (comp < 0) {
            return -1;
        } else {
            return 0;
        }
    }

    public static Comparator<Space> getSpaceComparator(Instance instance, int cntNum) {
        Comparator<Space> spaceComparator = null;
        spaceComparator = new SpaceComparator((instance.length ) * cntNum, instance.width);
        return spaceComparator;
    }
}

