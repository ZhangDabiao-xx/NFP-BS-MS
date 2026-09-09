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

    /**
     * 返回该已放置组块内真实多边形工件的面积和。
     *
     * <p>当输入来自旧版桥接文件且未提供真实面积时，回退到内部坐标单位的
     * 矩形面积换算到平方毫米后的值，以保持旧输入的兼容性。</p>
     *
     * @return 真实工件面积（平方毫米）；旧输入缺失该数据时返回矩形面积的平方毫米换算值。
     */
    public double getActualWorkpieceArea() {
        if (box != null && Double.isFinite(box.actualWorkpieceArea)
                && box.actualWorkpieceArea >= 0.0) {
            return box.actualWorkpieceArea;
        }
        return getVolume() / 100.0;
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

