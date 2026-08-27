package org.example.nfp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PolygonItem 的初始化几何指标。
 *
 * 该类把“清理顶点、计算面积、计算外接矩形面积和计算填充率”集中到一次初始化中，
 * 避免 NFP 搜索阶段反复对同一个物品做相同的基础几何计算。
 */
final class PolygonItemMetrics {

    final List<Point> normalizedPoints;
    final boolean rectangular;
    final double area;
    final double boxArea;
    final double fillRate;

    private PolygonItemMetrics(List<Point> normalizedPoints,
                               boolean rectangular,
                               double area,
                               double boxArea,
                               double fillRate) {
        this.normalizedPoints = normalizedPoints;
        this.rectangular = rectangular;
        this.area = area;
        this.boxArea = boxArea;
        this.fillRate = fillRate;
    }

    /**
     * 初始化单个工件的几何指标。
     *
     * 规则矩形使用相邻两条边的长度相乘计算面积；复杂多边形先删除连续重复点和同一直线上的冗余点，
     * 再使用鞋带公式计算面积，既减少后续 NFP 顶点数量，也避免冗余点对浮点计算造成干扰。
     */
    static PolygonItemMetrics calculate(List<Point> rawPoints) {
        List<Point> normalizedPoints = normalizePoints(rawPoints);
        if (normalizedPoints.size() < 3) {
            return new PolygonItemMetrics(normalizedPoints, false, 0.0, 0.0, 0.0);
        }

        boolean rectangular = Geometry.isRectangle(normalizedPoints);
        double area = rectangular
                ? rectangleArea(normalizedPoints)
                : Geometry.polygonAreaAbs(normalizedPoints);
        double boxArea = boundingBoxArea(normalizedPoints);

        // 矩形自身就是其最小外接矩形，因此初始化填充率直接记为 1；
        // 不规则图形使用“实际面积 / 轴对齐外接矩形面积”。
        double fillRate = rectangular ? 1.0 : calculateFillRate(area, boxArea);
        return new PolygonItemMetrics(normalizedPoints, rectangular, area, boxArea, fillRate);
    }

    private static List<Point> normalizePoints(List<Point> rawPoints) {
        List<Point> copiedPoints = new ArrayList<>();
        if (rawPoints == null) {
            return Collections.emptyList();
        }

        for (Point point : rawPoints) {
            if (point != null) {
                copiedPoints.add(new Point(point.x, point.y));
            }
        }

        copiedPoints = removeConsecutiveDuplicates(copiedPoints);
        if (copiedPoints.size() >= 3) {
            copiedPoints = Geometry.removeCollinearPoints(copiedPoints);
            copiedPoints = removeConsecutiveDuplicates(copiedPoints);
        }
        return Collections.unmodifiableList(copiedPoints);
    }

    private static List<Point> removeConsecutiveDuplicates(List<Point> points) {
        List<Point> result = new ArrayList<>(points.size());
        for (Point point : points) {
            if (result.isEmpty() || !samePoint(result.get(result.size() - 1), point)) {
                result.add(point);
            }
        }

        if (result.size() > 1 && samePoint(result.get(0), result.get(result.size() - 1))) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static boolean samePoint(Point first, Point second) {
        return Math.abs(first.x - second.x) <= Geometry.EPS
                && Math.abs(first.y - second.y) <= Geometry.EPS;
    }

    private static double rectangleArea(List<Point> rectangle) {
        Point first = rectangle.get(0);
        Point second = rectangle.get(1);
        Point third = rectangle.get(2);
        return first.distance(second) * second.distance(third);
    }

    private static double boundingBoxArea(List<Point> points) {
        BBox box = Geometry.polygonBBox(points);
        double width = Math.max(0.0, box.maxX - box.minX);
        double height = Math.max(0.0, box.maxY - box.minY);
        return width * height;
    }

    private static double calculateFillRate(double area, double boxArea) {
        if (boxArea <= Geometry.EPS) {
            return 0.0;
        }
        // 浮点误差可能让理论上不超过 1 的比例略微越界，这里只做边界收敛。
        return Math.max(0.0, Math.min(1.0, area / boxArea));
    }
}
