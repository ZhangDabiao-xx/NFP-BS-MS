package org.example.nfp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 几何工具类：多边形面积、方向、凸包、点在多边形内判断等
 * 对应 C++ geometry.h / geometry.cpp
 */
public class Geometry {

    /** 浮点容差 */
    public static final double EPS = 1e-9;
    // 矩形直角判定使用相对容差，避免输入坐标存在微小小数误差时误杀真实矩形。
    private static final double RECTANGLE_ORTHOGONAL_TOLERANCE = 1e-6;
    public static final double PI = Math.PI;

    // ===== 三点叉积 =====

    /** 叉积 (p2-p1) × (p3-p1) */
    public static double cross(Point p1, Point p2, Point p3) {
        return (p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x);
    }

    // ===== 点在线段上判断 =====

    /**
     * 判断点 p 是否在线段 ab 上
     */
    public static boolean onSegment(Point p, Point a, Point b) {
        double crossVal = (b.sub(a)).cross(p.sub(a));
        if (Math.abs(crossVal) > EPS) return false;
        double dotVal = (p.sub(a)).dot(b.sub(a));
        double lenSq = (b.sub(a)).lengthSq();
        return dotVal >= -EPS && dotVal <= lenSq + EPS;
    }

    // ===== 线段相交 =====

    /**
     * 检测线段 a1a2 与 b1b2 是否相交
     * @param result 存储交点(当返回1时有效)
     * @return 1=有交点, 0=不相交, -1=共线
     */
    public static int segmentIntersection(Point a1, Point a2, Point b1, Point b2, Point result) {
        Point d1 = a2.sub(a1);
        Point d2 = b2.sub(b1);
        double denom = d1.cross(d2);

        Point diff = b1.sub(a1);
        double t = diff.cross(d2);
        double u = diff.cross(d1);

        if (Math.abs(denom) < EPS) {
            if (Math.abs(t) < EPS) {
                return -1; // 共线
            }
            return 0; // 平行不相交
        }

        t /= denom;
        u /= denom;

        if (t >= -EPS && t <= 1.0 + EPS && u >= -EPS && u <= 1.0 + EPS) {
            Point intersection = a1.add(d1.mul(t));
            result.x = intersection.x;
            result.y = intersection.y;
            return 1;
        }
        return 0;
    }

    // ===== 多边形面积 =====

    /** 有符号面积 (CCW为正) */
    public static double polygonArea(List<Point> poly) {
        double area = 0;
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += poly.get(i).x * poly.get(j).y;
            area -= poly.get(j).x * poly.get(i).y;
        }
        return area / 2.0;
    }

    /** 无符号面积 */
    public static double polygonAreaAbs(List<Point> poly) {
        return Math.abs(polygonArea(poly));
    }

    // ===== 多边形方向 =====

    /**
     * 判断多边形方向
     * @return 1=CCW, -1=CW, 0=退化
     */
    public static int polygonOrientation(List<Point> poly) {
        double area = polygonArea(poly);
        if (area > EPS) return 1;
        if (area < -EPS) return -1;
        return 0;
    }

    /** 确保多边形为逆时针(CCW)方向 */
    public static void ensureCCW(List<Point> poly) {
        if (polygonOrientation(poly) == -1) {
            //如果返回值是-1，说明多边形是顺时针，需要进行反转操作
            Collections.reverse(poly);
        }
    }

    /** 确保多边形为顺时针(CW)方向 */
    public static void ensureCW(List<Point> poly) {
        if (polygonOrientation(poly) == 1) {
            Collections.reverse(poly);
        }
    }

    // ===== 包围盒 =====

    /** 计算多边形的轴对齐包围盒 */
    public static BBox polygonBBox(List<Point> poly) {
        BBox bb = new BBox();
        bb.minX = bb.maxX = poly.get(0).x;
        bb.minY = bb.maxY = poly.get(0).y;
        for (int i = 1; i < poly.size(); i++) {
            bb.minX = Math.min(bb.minX, poly.get(i).x);
            bb.minY = Math.min(bb.minY, poly.get(i).y);
            bb.maxX = Math.max(bb.maxX, poly.get(i).x);
            bb.maxY = Math.max(bb.maxY, poly.get(i).y);
        }
        return bb;
    }

    // ===== 特殊顶点查找 =====

    /** 最底部最左边的顶点索引 */
    public static int bottomLeftVertex(List<Point> poly) {
        int idx = 0;
        for (int i = 1; i < poly.size(); i++) {
            if (poly.get(i).y < poly.get(idx).y - EPS ||
                (Math.abs(poly.get(i).y - poly.get(idx).y) < EPS && poly.get(i).x < poly.get(idx).x - EPS)) {
                idx = i;
            }
        }
        return idx;
    }

    /** 最顶部最右边的顶点索引 */
    public static int topRightVertex(List<Point> poly) {
        int idx = 0;
        for (int i = 1; i < poly.size(); i++) {
            if (poly.get(i).y > poly.get(idx).y + EPS ||
                (Math.abs(poly.get(i).y - poly.get(idx).y) < EPS && poly.get(i).x > poly.get(idx).x + EPS)) {
                idx = i;
            }
        }
        return idx;
    }

    // ===== 多边形变换 =====

    /** 平移多边形 */
    public static List<Point> translatePolygon(List<Point> poly, Point offset) {
        List<Point> result = new ArrayList<>(poly.size());
        for (Point p : poly) {
            result.add(p.add(offset));
        }
        return result;
    }

    /** 按角度绕原点旋转多边形 */
    public static List<Point> rotatePolygon(List<Point> poly, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        List<Point> result = new ArrayList<>(poly.size());
        for (Point point : poly) {
            double x = point.x * cos - point.y * sin;
            double y = point.x * sin + point.y * cos;
            result.add(new Point(cleanNearZero(x), cleanNearZero(y)));
        }
        return result;
    }

    private static double cleanNearZero(double value) {
        return Math.abs(value) < EPS ? 0 : value;
    }

    /** 将多边形关于原点做镜像翻转 (Minkowski差需要) */
    public static List<Point> negatePolygon(List<Point> poly) {
        List<Point> result = new ArrayList<>(poly.size());
        for (Point p : poly) {
            result.add(p.negate());
        }
        return result;
    }

    // ===== 点在多边形内判断 =====

    /**
     * Winding Number 判断点与多边形关系
     * @return 1=内部, 0=边界上, -1=外部
     */
    public static int pointInPolygon(Point p, List<Point> poly) {
        int n = poly.size();
        int windingNumber = 0;

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            Point a = poly.get(i);
            Point b = poly.get(j);

            if (onSegment(p, a, b)) return 0;

            if (a.y <= p.y + EPS) {
                if (b.y > p.y + EPS) {
                    double v = cross(a, b, p);
                    if (v > EPS) windingNumber++;
                }
            } else {
                if (b.y <= p.y + EPS) {
                    double v = cross(a, b, p);
                    if (v < -EPS) windingNumber--;
                }
            }
        }
        return windingNumber != 0 ? 1 : -1;
    }

    // ===== 共线点移除 =====

    /** 移除多边形中的共线顶点 */
    public static List<Point> removeCollinearPoints(List<Point> poly) {
        int n = poly.size();
        if (n < 3) return new ArrayList<>(poly);

        List<Point> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int prev = (i - 1 + n) % n;
            int next = (i + 1) % n;
            Point d1 = poly.get(i).sub(poly.get(prev));
            Point d2 = poly.get(next).sub(poly.get(i));
            if (Math.abs(d1.cross(d2)) > EPS) {
                result.add(poly.get(i));
            }
        }
        return result;
    }

    // ===== 矩形判定 =====

    /**
     * 判断多边形是否为几何矩形。
     *
     * 功能说明：部分输入会把四角梯形也标记为 smallItem，因此不能只看 smallItem 标记；
     * 这里先移除共线冗余点，再要求 4 个有效顶点、面积非退化、每个相邻边夹角为直角。
     */
    public static boolean isRectangle(List<Point> poly) {
        List<Point> vertices = removeCollinearPoints(poly);
        if (vertices.size() != 4 || polygonAreaAbs(vertices) <= EPS) {
            return false;
        }

        for (int i = 0; i < vertices.size(); i++) {
            Point previous = vertices.get((i + vertices.size() - 1) % vertices.size());
            Point current = vertices.get(i);
            Point next = vertices.get((i + 1) % vertices.size());
            Point previousEdge = previous.sub(current);
            Point nextEdge = next.sub(current);
            if (previousEdge.lengthSq() <= EPS || nextEdge.lengthSq() <= EPS) {
                return false;
            }
            double dot = previousEdge.dot(nextEdge);
            double tolerance = RECTANGLE_ORTHOGONAL_TOLERANCE * Math.sqrt(previousEdge.lengthSq() * nextEdge.lengthSq());
            if (Math.abs(dot) > tolerance) {
                return false;
            }
        }
        return true;
    }

    // ===== 凸性判断 =====

    /** 判断多边形是否为凸多边形 */
    public static boolean isConvex(List<Point> poly) {
        int n = poly.size();
        if (n < 3) return false;

        boolean hasPos = false;
        boolean hasNeg = false;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            int k = (i + 2) % n;
            double c = cross(poly.get(i), poly.get(j), poly.get(k));
            if (c > EPS) hasPos = true;
            if (c < -EPS) hasNeg = true;
            if (hasPos && hasNeg) return false;
        }
        return true;
    }

    // ===== 凸包 (Andrew's Monotone Chain) =====

    /** 计算点集的凸包 */
    public static List<Point> convexHull(List<Point> points) {
        int n = points.size();
        if (n < 3) return new ArrayList<>(points);

        List<Point> pts = new ArrayList<>(points);
        pts.sort((a, b) -> {
            if (a.x < b.x - EPS) return -1;
            if (a.x > b.x + EPS) return 1;
            if (a.y < b.y - EPS) return -1;
            if (a.y > b.y + EPS) return 1;
            return 0;
        });

        Point[] hull = new Point[2 * n];
        int k = 0;

        // 下凸包
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], pts.get(i)) < EPS) k--;
            hull[k++] = pts.get(i);
        }

        // 上凸包
        int lowerSize = k + 1;
        for (int i = n - 2; i >= 0; i--) {
            while (k >= lowerSize && cross(hull[k - 2], hull[k - 1], pts.get(i)) < EPS) k--;
            hull[k++] = pts.get(i);
        }

        List<Point> result = new ArrayList<>(k - 1);
        for (int i = 0; i < k - 1; i++) {
            result.add(hull[i]);
        }
        return result;
    }

    // ===== 角度工具 =====

    /** 角度规范化到 [-PI, PI) */
    public static double normalizeAngle(double a) {
        while (a >= PI) a -= 2 * PI;
        while (a < -PI) a += 2 * PI;
        return a;
    }
}