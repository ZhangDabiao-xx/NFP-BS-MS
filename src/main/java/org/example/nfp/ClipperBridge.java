package org.example.nfp;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clipper 桥接层：提供 NFP 计算所需的整数坐标转换、区域布尔运算和 Minkowski Sum。
 *
 * 内部使用整数坐标系 (缩放因子 100000) 以降低浮点误差；区域合并通过 Java2D Area 完成。
 */
public class ClipperBridge {

    /** double坐标到整数坐标的缩放因子 */
    private static final double CLIPPER_SCALE = 100000.0;

    private static final double AREA_FLATNESS = 1e-7;

    // ========== 坐标转换 ==========

    /**
     * 将浮点多边形转为整数坐标路径
     * @param poly 浮点坐标多边形
     * @return 缩放后的整数坐标点列表 (long[]{x, y})
     */
    public static List<long[]> toIntPath(List<Point> poly) {
        List<long[]> path = new ArrayList<>(poly.size());
        for (Point p : poly) {
            long ix = Math.round(p.x * CLIPPER_SCALE);
            long iy = Math.round(p.y * CLIPPER_SCALE);
            path.add(new long[]{ix, iy});
        }
        return path;
    }

    /**
     * 将整数坐标路径转回浮点多边形
     * @param path 整数坐标点列表
     * @return 浮点坐标多边形
     */
    public static List<Point> fromIntPath(List<long[]> path) {
        List<Point> poly = new ArrayList<>(path.size());
        for (long[] p : path) {
            poly.add(new Point(p[0] / CLIPPER_SCALE, p[1] / CLIPPER_SCALE));
        }
        return poly;
    }

    // ========== 整数路径基本操作 ==========

    /**
     * 整数路径的有符号面积 (CCW为正)
     */
    public static double intPathArea(List<long[]> path) {
        int n = path.size();
        if (n < 3) return 0;
        double area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += (double) path.get(i)[0] * path.get(j)[1];
            area -= (double) path.get(j)[0] * path.get(i)[1];
        }
        return area / 2.0;
    }

    /**
     * 整数路径方向判断
     * @return true=CCW (正面积)
     */
    public static boolean intPathOrientation(List<long[]> path) {
        return intPathArea(path) > 0;
    }

    /** 反转整数路径方向 */
    public static void reverseIntPath(List<long[]> path) {
        Collections.reverse(path);
    }

    // ========== Minkowski Sum ==========

    /**
     * 计算 Minkowski Sum: pattern ⊕ path。
     *
     * 一般多边形先做耳切三角剖分；每对凸片计算 Minkowski Sum；最后对所有结果做区域并集。
     * @param pattern 模式多边形 (整数坐标)
     * @param path    路径多边形 (整数坐标)
     * @return Minkowski Sum 区域边界列表
     */
    public static List<List<long[]>> minkowskiSum(List<long[]> pattern, List<long[]> path) throws IOException {
        List<Point> patternPoly = normalizePolygon(fromIntPath(pattern));
        List<Point> pathPoly = normalizePolygon(fromIntPath(path));
        if (patternPoly.size() < 3 || pathPoly.size() < 3) return new ArrayList<>();

        List<List<Point>> patternParts = triangulateToConvexParts(patternPoly);
        List<List<Point>> pathParts = triangulateToConvexParts(pathPoly);
        List<List<long[]>> convexSums = new ArrayList<>();

        for (List<Point> pathPart : pathParts) {
            for (List<Point> patternPart : patternParts) {
                List<Point> sum = minkowskiSumConvex(pathPart, patternPart);
                if (sum.size() >= 3) {
                    convexSums.add(toIntPath(sum));
                }
            }
        }

        return unionAllPolygons(convexSums);
    }

    /**
     * 多个多边形区域并集。
     */
    public static List<List<long[]>> unionAllPolygons(List<List<long[]>> polygons) {
        Area unionArea = new Area();
        for (List<long[]> polygon : polygons) {
            List<Point> normalized = normalizePolygon(fromIntPath(polygon));
            if (normalized.size() >= 3) {
                unionArea.add(toArea(normalized));
            }
        }
        return fromArea(unionArea);
    }

    /**
     * 多个多边形区域交集。
     */
    public static List<List<long[]>> intersectionAllPolygons(List<List<long[]>> polygons) {
        Area intersectionArea = null;
        for (List<long[]> polygon : polygons) {
            List<Point> normalized = normalizePolygon(fromIntPath(polygon));
            if (normalized.size() < 3) continue;

            Area polygonArea = toArea(normalized);
            if (intersectionArea == null) {
                intersectionArea = polygonArea;
            } else {
                intersectionArea.intersect(polygonArea);
            }

            if (intersectionArea.isEmpty()) return new ArrayList<>();
        }

        if (intersectionArea == null) return new ArrayList<>();
        return fromArea(intersectionArea);
    }

    // ========== 多边形布尔运算 ==========

    /**
     * 两个多边形的并集
     */
    public static List<List<long[]>> polygonUnion(List<long[]> subject, List<long[]> clip) {
        List<List<long[]>> polygons = new ArrayList<>();
        polygons.add(subject);
        polygons.add(clip);
        return unionAllPolygons(polygons);
    }

    /**
     * 两个多边形的交集
     * @param subject 主多边形 (整数坐标)
     * @param clip    裁剪多边形 (整数坐标)
     * @return 交集结果
     */
    public static List<List<long[]>> polygonIntersection(List<long[]> subject, List<long[]> clip) {
        List<List<long[]>> polygons = new ArrayList<>();
        polygons.add(subject);
        polygons.add(clip);
        return intersectionAllPolygons(polygons);
    }

    // ========== 区域和路径转换 ==========

    private static Area toArea(List<Point> polygon) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        Point first = polygon.get(0);
        path.moveTo(first.x, first.y);
        for (int i = 1; i < polygon.size(); i++) {
            Point point = polygon.get(i);
            path.lineTo(point.x, point.y);
        }
        path.closePath();
        return new Area(path);
    }

    private static List<List<long[]>> fromArea(Area area) {
        List<List<long[]>> result = new ArrayList<>();
        if (area.isEmpty()) return result;

        for (List<Point> path : extractAreaPaths(area)) {
            if (path.size() >= 3) {
                result.add(toIntPath(path));
            }
        }
        return result;
    }

    private static List<List<Point>> extractAreaPaths(Area area) {
        List<List<Point>> paths = new ArrayList<>();
        PathIterator iterator = area.getPathIterator(null, AREA_FLATNESS);
        double[] coords = new double[6];
        List<Point> currentPath = new ArrayList<>();

        while (!iterator.isDone()) {
            int segmentType = iterator.currentSegment(coords);
            switch (segmentType) {
                case PathIterator.SEG_MOVETO:
                    finishExtractedPath(paths, currentPath);
                    currentPath.add(new Point(coords[0], coords[1]));
                    break;
                case PathIterator.SEG_LINETO:
                    currentPath.add(new Point(coords[0], coords[1]));
                    break;
                case PathIterator.SEG_CLOSE:
                    finishExtractedPath(paths, currentPath);
                    break;
                default:
                    break;
            }
            iterator.next();
        }

        finishExtractedPath(paths, currentPath);
        paths.sort((a, b) -> Double.compare(Geometry.polygonAreaAbs(b), Geometry.polygonAreaAbs(a)));
        return paths;
    }

    private static void finishExtractedPath(List<List<Point>> paths, List<Point> currentPath) {
        if (currentPath.isEmpty()) return;

        List<Point> normalized = normalizePolygon(currentPath);
        if (normalized.size() >= 3 && Geometry.polygonAreaAbs(normalized) > Geometry.EPS) {
            paths.add(normalized);
        }
        currentPath.clear();
    }

    // ========== 凸分解和凸 Minkowski Sum ==========

    private static List<List<Point>> triangulateToConvexParts(List<Point> polygon) throws IOException {
        List<Point> normalized = normalizePolygon(polygon);
        List<List<Point>> result = new ArrayList<>();
        if (normalized.size() < 3) return result;

        if (Geometry.isConvex(normalized)) {
            result.add(normalized);
            return result;
        }

        List<Point> working = new ArrayList<>(normalized);
        while (working.size() > 3) {
            boolean earFound = false;
            int vertexCount = working.size();

            for (int i = 0; i < vertexCount; i++) {
                Point prev = working.get((i - 1 + vertexCount) % vertexCount);
                Point curr = working.get(i);
                Point next = working.get((i + 1) % vertexCount);

                if (!isConvexCorner(prev, curr, next)) continue;
                if (containsAnotherVertexInTriangle(working, i)) continue;

                List<Point> triangle = new ArrayList<>(3);
                triangle.add(copyPoint(prev));
                triangle.add(copyPoint(curr));
                triangle.add(copyPoint(next));
                result.add(triangle);
                working.remove(i);
                earFound = true;
                break;
            }

            if (!earFound) {
                throw new IOException("无法完成凹多边形三角剖分，请检查输入是否为简单多边形");
            }
        }

        List<Point> lastTriangle = new ArrayList<>(3);
        for (Point point : working) {
            lastTriangle.add(copyPoint(point));
        }
        result.add(lastTriangle);
        return result;
    }

    private static boolean isConvexCorner(Point prev, Point curr, Point next) {
        return Geometry.cross(prev, curr, next) > Geometry.EPS;
    }

    private static boolean containsAnotherVertexInTriangle(List<Point> polygon, int triangleCenterIndex) {
        int vertexCount = polygon.size();
        int prevIndex = (triangleCenterIndex - 1 + vertexCount) % vertexCount;
        int nextIndex = (triangleCenterIndex + 1) % vertexCount;
        Point prev = polygon.get(prevIndex);
        Point curr = polygon.get(triangleCenterIndex);
        Point next = polygon.get(nextIndex);

        for (int i = 0; i < vertexCount; i++) {
            if (i == prevIndex || i == triangleCenterIndex || i == nextIndex) continue;
            if (pointInTriangle(polygon.get(i), prev, curr, next)) return true;
        }
        return false;
    }

    private static boolean pointInTriangle(Point point, Point a, Point b, Point c) {
        double ab = Geometry.cross(a, b, point);
        double bc = Geometry.cross(b, c, point);
        double ca = Geometry.cross(c, a, point);
        return ab >= -Geometry.EPS && bc >= -Geometry.EPS && ca >= -Geometry.EPS;
    }

    private static List<Point> minkowskiSumConvex(List<Point> polygonA, List<Point> polygonB) {
        List<Point> sums = new ArrayList<>(polygonA.size() * polygonB.size());
        for (Point a : polygonA) {
            for (Point b : polygonB) {
                sums.add(a.add(b));
            }
        }
        return normalizePolygon(Geometry.convexHull(sums));
    }

    // ========== 路径规范化 ==========

    private static List<Point> normalizePolygon(List<Point> polygon) {
        List<Point> cleaned = removeDuplicatePoints(polygon);
        if (cleaned.size() < 3) return cleaned;

        cleaned = Geometry.removeCollinearPoints(cleaned);
        cleaned = removeDuplicatePoints(cleaned);
        if (cleaned.size() < 3 || Geometry.polygonAreaAbs(cleaned) <= Geometry.EPS) {
            return new ArrayList<>();
        }

        Geometry.ensureCCW(cleaned);
        return cleaned;
    }

    private static List<Point> removeDuplicatePoints(List<Point> polygon) {
        List<Point> result = new ArrayList<>(polygon.size());
        for (Point point : polygon) {
            if (result.isEmpty() || !samePoint(result.get(result.size() - 1), point)) {
                result.add(copyPoint(point));
            }
        }

        if (result.size() > 1 && samePoint(result.get(0), result.get(result.size() - 1))) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static boolean samePoint(Point a, Point b) {
        return Math.abs(a.x - b.x) <= Geometry.EPS && Math.abs(a.y - b.y) <= Geometry.EPS;
    }

    private static Point copyPoint(Point point) {
        return new Point(point.x, point.y);
    }
}