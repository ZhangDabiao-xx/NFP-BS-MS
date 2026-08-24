package org.example.nfp;

/**
 * 二维点/向量，支持基本几何运算
 */
public class Point {

    public double x;
    public double y;

    public Point() {
        this.x = 0;
        this.y = 0;
    }

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /** 向量加法 */
    public Point add(Point p) {
        return new Point(x + p.x, y + p.y);
    }

    /** 向量减法 */
    public Point sub(Point p) {
        return new Point(x - p.x, y - p.y);
    }

    /** 标量乘法 */
    public Point mul(double s) {
        return new Point(x * s, y * s);
    }

    /** 标量除法 */
    public Point div(double s) {
        return new Point(x / s, y / s);
    }

    /** 取反 (镜像翻转) */
    public Point negate() {
        return new Point(-x, -y);
    }

    /** 点积 */
    public double dot(Point p) {
        return x * p.x + y * p.y;
    }

    /** 叉积 (返回标量z分量) */
    public double cross(Point p) {
        return x * p.y - y * p.x;
    }

    /** 向量长度 */
    public double length() {
        return Math.sqrt(x * x + y * y);
    }

    /** 向量长度的平方 */
    public double lengthSq() {
        return x * x + y * y;
    }

    /** 单位化 */
    public Point normalize() {
        double len = length();
        if (len < Geometry.EPS) return new Point(0, 0);
        return new Point(x / len, y / len);
    }

    /** 逆时针旋转90度 */
    public Point perp() {
        return new Point(-y, x);
    }

    /** 方向角 (atan2) */
    public double angle() {
        return Math.atan2(y, x);
    }

    /** 两点之间的欧几里得距离 */
    public double distance(Point p) {
        double dx = x - p.x;
        double dy = y - p.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 线性插值: this + (other - this) * t */
    public Point lerp(Point other, double t) {
        return new Point(x + (other.x - x) * t, y + (other.y - y) * t);
    }

    /** 浮点容差相等判断 */
    public boolean equalsEps(Point p) {
        return Math.abs(x - p.x) < Geometry.EPS && Math.abs(y - p.y) < Geometry.EPS;
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f)", x, y);
    }
}