package org.example.beamsearch.common;

import java.util.Comparator;

public class SpaceVolumeComparator implements Comparator<Space>{
    @Override
    public int compare(Space a, Space b) {
        double comp = b.volume - a.volume;
        if (comp < 0) {
            return -1;
        } else if (comp > 0) {
            return 1;
        } else {
            return 0;
        }
    }
}
