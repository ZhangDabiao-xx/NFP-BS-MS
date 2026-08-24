package org.example.beamsearch.common;

import org.example.beamsearch.blockgenerator.GeneralBlock;

public class PlacedBlock {
    public GeneralBlock block;
    public int x;
    public int y;


    public PlacedBlock(int xx, int yy, GeneralBlock b) {
        x = xx;
        y = yy;
        block = b;
    }

    @Override
    public PlacedBlock clone() {
        return new PlacedBlock(x, y, block);
    }

}

