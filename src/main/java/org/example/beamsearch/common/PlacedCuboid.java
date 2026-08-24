package org.example.beamsearch.common;

public class PlacedCuboid {
    public int x;
    public int y;
    public int length;
    public int width;
    public Box box;
    public int ortIdx;

    public double getVolume() {
        return box.volume;
    }

    public PlacedCuboid(int px, int py, int l, int w, Box b, int ortIdx) {
        x = px;
        y = py;
        length = l;
        width = w;
        box = b;
        this.ortIdx = ortIdx;
    }

    public PlacedCuboid translate(int dx, int dy) {
        return new PlacedCuboid(x + dx, y + dy, length, width, box, ortIdx);
    }

    @Override
    public PlacedCuboid clone() {
        return new PlacedCuboid(x, y, length, width, box, ortIdx);
    }

    @Override
    public String toString() {
        return "{" + x + ", " + y + ", " + "},{" + (x + length) + ", " + (y + width) + ", " + "}";
    }
}

