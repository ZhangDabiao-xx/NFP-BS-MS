package org.example.beamsearch.common;

public class HyperCuboid implements Cloneable{
    public int x1;
    public int y1;

    public int x2;
    public int y2;

    public double volume;

    public int length() {
        return x2-x1;
    }
    public int width() {
        return y2-y1;
    }

    public HyperCuboid(int sx1, int sy1, int sx2, int sy2) {
        x1 = sx1;
        y1 = sy1;

        x2 = sx2;
        y2 = sy2;
        volume = 1.0*(x2-x1)*(y2-y1);
    }

    public boolean contains(HyperCuboid h) {
        return x1 <= h.x1 && h.x2 <= x2 &&
                y1 <= h.y1 && h.y2 <= y2;
    }

    public boolean intersectTest(HyperCuboid h) {
        return x1 < h.x2 && y1 < h.y2 && h.x1 < x2 && h.y1 < y2;
    }
}

