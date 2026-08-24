package org.example.nfp;

/**
 * 轴对齐包围盒
 */
public class BBox {

    public double minX;
    public double minY;
    public double maxX;
    public double maxY;

    public BBox() {}

    public BBox(double minX, double minY, double maxX, double maxY) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }
}