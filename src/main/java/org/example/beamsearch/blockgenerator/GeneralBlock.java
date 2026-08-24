package org.example.beamsearch.blockgenerator;

import org.example.beamsearch.common.Box;
import org.example.beamsearch.common.PlacedCuboid;

import java.util.ArrayList;

public class GeneralBlock implements Comparable<GeneralBlock> {
    public int length;
    public int width;
    public long blockVolume;
    public double boxVolume;
    public double scoreVolume;
    public double sizeVolume;
    public ArrayList<PlacedCuboid> cuboid;
    public int[] typeCount;
    public int[] component = null;
    public boolean homoBlock;


    public GeneralBlock(int nl, int nw, Box b, int ortIdx, int typeCount) {
        int[] wl = b.variation[ortIdx];
        int l = wl[1];
        int w = wl[0];

        length = l * nl;
        width = w * nw;

        blockVolume = ((long) length) * width ;
        boxVolume = nl * nw * b.volume;
        scoreVolume = nl * nw * b.scoreVolume;
        sizeVolume = nl * nw * b.sizeVolume;

        this.typeCount = new int[typeCount];
        this.component = new int[1];
        this.component[0] = b.typeNum - 1;
        this.typeCount[b.typeNum - 1] = nl * nw;

        cuboid = new ArrayList<PlacedCuboid>();
        for (int i = 0; i < nl; ++i) {
            for (int j = 0; j < nw; ++j) {
                cuboid.add(new PlacedCuboid(i * l, j * w, l, w, b, ortIdx));
            }
        }

        this.homoBlock = true;
    }

    private int totalBoxCount = 0;

    @Override
    public int compareTo(GeneralBlock o) {
        double comp = o.boxVolume - boxVolume;
        if (comp > 0) {
            return 1;
        } else if (comp < 0) {
            return -1;
        }
        long diff = blockVolume - o.blockVolume;
        if (diff > 0) {
            return 1;
        } else if (diff < 0) {
            return -1;
        }
        return cuboid.size() - o.cuboid.size();
    }
}

