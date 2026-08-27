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
    // 初始化阶段识别的规则矩形标记，避免 NFP 搜索中反复执行矩形判定。
    public final boolean rectangular;
    public final double area;
    public final double boxArea;
    // 填充率 = 工件实际面积 / 工件外接矩形面积，是新的 NFP 拼接评分基础。
    public final double fillRate;
    // 保留旧字段作为兼容别名，避免影响已有输出或外部调用；新的代码统一使用 fillRate。
    @Deprecated
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
        // 在构造工件时一次性完成顶点清理和基础几何计算，后续候选搜索直接复用结果。
        PolygonItemMetrics metrics = PolygonItemMetrics.calculate(points);
        this.points = metrics.normalizedPoints;
        this.rotate = normalizeRotations(rotate);
        this.smallItem = smallItem;
        this.rectangular = metrics.rectangular;
        this.area = metrics.area;
        this.boxArea = metrics.boxArea;
        this.fillRate = metrics.fillRate;
        this.areaRate = this.fillRate;
    }

    public boolean shouldStaySingle() {
        // 旧入口保留，但语义已经收敛为：
        // 1) smallItem 直接进入组合池；
        // 2) 非 smallItem 时，如果 fillRate 足够高，则保持单独块。
        return !smallItem && fillRate > RECTANGULAR_AREA_RATE;
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

    private static Point copyPoint(Point point) {
        return new Point(point.x, point.y);
    }
}
