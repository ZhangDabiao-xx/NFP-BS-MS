package org.example.nfp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PolygonItem {

    public static final double RECTANGULAR_AREA_RATE = 0.96;

    public final String id;
    public final boolean backFrontPriority;
    public final Point centerPoint;
    public final List<Point> points;
    public final List<Integer> rotate;
    public final boolean smallItem;
    public final double area;
    public final double boxArea;
    public final double areaRate;

    public PolygonItem(String id,
                       boolean backFrontPriority,
                       Point centerPoint,
                       List<Point> points,
                       List<Integer> rotate,
                       boolean smallItem) {
        this.id = id;
        this.backFrontPriority = backFrontPriority;
        this.centerPoint = copyPoint(centerPoint);
        this.points = copyPolygon(points);
        this.rotate = normalizeRotations(rotate);
        this.smallItem = smallItem;
        this.area = Geometry.polygonAreaAbs(this.points);
        this.boxArea = PolygonStitcher.boundingBoxArea(this.points);
        this.areaRate = this.boxArea <= Geometry.EPS ? 0 : this.area / this.boxArea;
    }

    public boolean shouldStaySingle() {
        // 旧入口保留，但语义已经收敛为：
        // 1) smallItem 直接进入组合池；
        // 2) 非 smallItem 时，如果 areaRate 足够高，则保持单独块。
        return !smallItem && areaRate > RECTANGULAR_AREA_RATE;
    }

    public List<Point> rotatedPoints(int rotationDegrees) {
        return Geometry.rotatePolygon(points, rotationDegrees);
    }

    public static List<Integer> normalizeRotations(List<Integer> rotations) {
        Set<Integer> normalized = new LinkedHashSet<>();
        if (rotations != null) {
            for (Integer rotation : rotations) {
                if (rotation != null) {
                    normalized.add(normalizeRotation(rotation));
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(0);
        }
        List<Integer> result = new ArrayList<>(normalized);
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    public static int normalizeRotation(int rotation) {
        int normalized = rotation % 360;
        if (normalized < 0) {
            normalized += 360;
        }
        return normalized;
    }

    private static List<Point> copyPolygon(List<Point> polygon) {
        List<Point> copy = new ArrayList<>(polygon.size());
        for (Point point : polygon) {
            copy.add(copyPoint(point));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Point copyPoint(Point point) {
        return new Point(point.x, point.y);
    }
}