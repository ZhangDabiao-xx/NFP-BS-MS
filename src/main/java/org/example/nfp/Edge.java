package org.example.nfp;

/**
 * 有向边：由起点和终点定义
 */
public class Edge {

    public Point start;
    public Point end;

    public Edge() {
        this.start = new Point();
        this.end = new Point();
    }

    public Edge(Point start, Point end) {
        this.start = start;
        this.end = end;
    }

    /** 边的方向向量 */
    public Point direction() {
        return end.sub(start);
    }

    /** 边的长度 */
    public double length() {
        return direction().length();
    }
}