package org.example.nfp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

public class Block {

    public static final int MAX_MEMBER_COUNT = 20;

    public final String id;
    public final boolean backFrontPriority;
    public final List<Integer> rotate;
    public final List<ItemPlacement> placements;
    public final List<Point> combinedCoordinates;
    public final List<Point> outline;
    public final double areaSum;
    public final double sourceBoxAreaSum;
    public final double boxArea;
    // score2 = 子物品外接矩形面积之和 - 组合块外接矩形面积；值越大表示矩形化收益越高。
    public final double score2;

    private Block(List<ItemPlacement> placements, List<Integer> rotate) {
        List<ItemPlacement> normalizedPlacements = normalizePlacements(placements);
        this.placements = Collections.unmodifiableList(normalizedPlacements);
        this.rotate = PolygonItem.normalizeRotations(rotate);
        this.id = joinIds(normalizedPlacements);
        this.backFrontPriority = normalizedPlacements.get(0).item.backFrontPriority;
        this.combinedCoordinates = Collections.unmodifiableList(buildCombinedCoordinates(normalizedPlacements));
        this.outline = Collections.unmodifiableList(buildOutline(normalizedPlacmentsAsPolygons(normalizedPlacements), combinedCoordinates));
        this.areaSum = sumArea(normalizedPlacements);
        this.sourceBoxAreaSum = sumSourceBoxArea(normalizedPlacements);
        this.boxArea = PolygonStitcher.boundingBoxArea(combinedCoordinates);
        this.score2 = sourceBoxAreaSum - boxArea;
    }

    public static Block fromSingle(PolygonItem item) {
        List<ItemPlacement> placements = new ArrayList<>();
        placements.add(ItemPlacement.fromItem(item));
        return new Block(placements, item.rotate);
    }

    public Block withAdditionalItem(PolygonItem item, PolygonStitcher.StitchingCandidate candidate) {
        List<Integer> nextRotations = validRotationsAfter(item, candidate.movingRotationDegrees);
        List<ItemPlacement> nextPlacements = new ArrayList<>(placements);
        nextPlacements.add(ItemPlacement.fromCandidate(item, candidate));
        return new Block(nextPlacements, nextRotations);
    }

    public boolean canStitchWith(PolygonItem item) {
        // 拼接前的硬约束：
        // 1) 复合块成员数不能超过上限；
        // 2) BackFrontPriority 必须一致；
        // 3) 两者可接受的旋转集合必须至少存在一个交集。
        // 这里先拦住不合法候选，再交给几何求解器处理具体位置。
        return placements.size() < MAX_MEMBER_COUNT
                && backFrontPriority == item.backFrontPriority
                && !relativeRotationsFor(item).isEmpty();
    }

    public List<Integer> validRotationsAfter(PolygonItem item, int relativeRotation) {
        // 该方法用于校验：在已经选定"相对旋转"后，当前复合块保留的旋转集合里，
        // 是否还能找到与新物品自有允许旋转相匹配的方案。
        Set<Integer> itemRotationSet = new LinkedHashSet<>(item.rotate);
        List<Integer> result = new ArrayList<>();
        for (Integer blockRotation : rotate) {
            int itemRotation = PolygonItem.normalizeRotation(blockRotation + relativeRotation);
            if (itemRotationSet.contains(itemRotation)) {
                result.add(blockRotation);
            }
        }
        return normalizeRotationIntersection(result);
    }

    public List<Integer> relativeRotationsFor(PolygonItem item) {
        // 相对旋转集合表示"当前块旋转多少度时，新物品旋转多少度能对上"。
        // 后续 NFP 搜索只需要处理这些候选角度，不必遍历全部 0~359 度。
        Set<Integer> relativeRotations = new LinkedHashSet<>();
        for (Integer blockRotation : rotate) {
            for (Integer itemRotation : item.rotate) {
                relativeRotations.add(PolygonItem.normalizeRotation(itemRotation - blockRotation));
            }
        }
        List<Integer> result = new ArrayList<>(relativeRotations);
        Collections.sort(result);
        return normalizeRotationIntersection(result);
    }

    public boolean hasPositiveOverlapWith(List<Point> polygon) {
        for (ItemPlacement placement : placements) {
            if (PolygonStitcher.intersectionArea(placement.placedPoints, polygon) > PolygonStitcher.SCORE_EPS) {
                return true;
            }
        }
        return false;
    }

    public int memberCount() {
        return placements.size();
    }

    public static List<Integer> intersectRotations(List<Integer> first, List<Integer> second) {
        Set<Integer> secondSet = new LinkedHashSet<>(second);
        List<Integer> result = new ArrayList<>();
        for (Integer rotation : first) {
            if (secondSet.contains(rotation)) {
                result.add(rotation);
            }
        }
        return normalizeRotationIntersection(result);
    }

    private static List<Integer> normalizeRotationIntersection(List<Integer> rotations) {
        if (rotations.isEmpty()) {
            return Collections.emptyList();
        }
        return PolygonItem.normalizeRotations(rotations);
    }

    private static List<ItemPlacement> normalizePlacements(List<ItemPlacement> placements) {
        BBox box = placementBBox(placements);
        Point offset = new Point(-box.minX, -box.minY);
        List<ItemPlacement> normalized = new ArrayList<>(placements.size());
        for (ItemPlacement placement : placements) {
            normalized.add(placement.translated(offset));
        }
        return normalized;
    }

    private static BBox placementBBox(List<ItemPlacement> placements) {
        List<Point> allPoints = new ArrayList<>();
        for (ItemPlacement placement : placements) {
            allPoints.addAll(placement.placedPoints);
        }
        return Geometry.polygonBBox(allPoints);
    }

    private static List<Point> buildCombinedCoordinates(List<ItemPlacement> placements) {
        List<Point> combined = new ArrayList<>();
        for (ItemPlacement placement : placements) {
            combined.addAll(copyPolygon(placement.placedPoints));
        }
        return combined;
    }

    private static List<List<Point>> normalizedPlacmentsAsPolygons(List<ItemPlacement> placements) {
        List<List<Point>> polygons = new ArrayList<>(placements.size());
        for (ItemPlacement placement : placements) {
            polygons.add(placement.placedPoints);
        }
        return polygons;
    }

    private static List<Point> buildOutline(List<List<Point>> polygons, List<Point> combinedCoordinates) {
        List<Point> unionBoundary = PolygonStitcher.largestUnionBoundary(polygons);
        if (!unionBoundary.isEmpty()) {
            return unionBoundary;
        }
        return Geometry.convexHull(combinedCoordinates);
    }

    private static double sumArea(List<ItemPlacement> placements) {
        double area = 0;
        for (ItemPlacement placement : placements) {
            area += placement.item.area;
        }
        return area;
    }

    private static double sumSourceBoxArea(List<ItemPlacement> placements) {
        double sum = 0;
        for (ItemPlacement placement : placements) {
            List<Point> rotatedPoints = placement.item.rotatedPoints(placement.selectedRelativeRotation);
            sum += PolygonStitcher.boundingBoxArea(rotatedPoints);
        }
        return sum;
    }

    private static String joinIds(List<ItemPlacement> placements) {
        StringJoiner joiner = new StringJoiner("+");
        for (ItemPlacement placement : placements) {
            joiner.add(placement.item.id);
        }
        return joiner.toString();
    }

    private static List<Point> copyPolygon(List<Point> polygon) {
        List<Point> copy = new ArrayList<>(polygon.size());
        for (Point point : polygon) {
            copy.add(new Point(point.x, point.y));
        }
        return copy;
    }

    public static final class ItemPlacement {
        public final PolygonItem item;
        public final int selectedRelativeRotation;
        public final Point translation;
        public final List<Point> placedPoints;
        // 保存最终采用的 NFP 来源和 score2，便于追踪每个子物品进入组合块时的面积收益。
        public final String sourceType;
        public final double candidateScore2;

        private ItemPlacement(PolygonItem item,
                              int selectedRelativeRotation,
                              Point translation,
                              List<Point> placedPoints,
                              String sourceType,
                              double candidateScore2) {
            this.item = item;
            this.selectedRelativeRotation = PolygonItem.normalizeRotation(selectedRelativeRotation);
            this.translation = new Point(translation.x, translation.y);
            this.placedPoints = Collections.unmodifiableList(copyPolygon(placedPoints));
            this.sourceType = sourceType;
            this.candidateScore2 = candidateScore2;
        }

        private static ItemPlacement fromItem(PolygonItem item) {
            return new ItemPlacement(item, 0, new Point(0, 0), item.points,
                    "SINGLE", 0);
        }

        private static ItemPlacement fromCandidate(PolygonItem item, PolygonStitcher.StitchingCandidate candidate) {
            return new ItemPlacement(item, candidate.movingRotationDegrees, candidate.translation, candidate.translatedPolygonB,
                    candidate.sourceType, candidate.score2);
        }

        private ItemPlacement translated(Point offset) {
            return new ItemPlacement(item,
                    selectedRelativeRotation,
                    translation.add(offset),
                    Geometry.translatePolygon(placedPoints, offset),
                    sourceType,
                    candidateScore2);
        }
    }
}