package org.example.nfp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PolygonStitcher {

    public static final double SCORE_EPS = 1e-6;

    // === 自适应密集采样参数 ===
    // NFP 边密集采样的阈值比例：边长超过此值（取两个多边形中较小对角线长度的比例）时进行细分采样
    private static final double DENSE_SAMPLE_DIAGONAL_RATIO = 0.05;
    // 单条边最多额外插入的采样点数，防止候选数量爆炸
    private static final int MAX_EXTRA_SAMPLES_PER_EDGE = 10;

    public static final class StitchingCandidate {
        public final String sourceType;
        public final int edgeStartIndex;
        public final int edgeEndIndex;
        public final int movingRotationDegrees;
        public final Point placementPoint;
        public final Point translation;
        public final double boxAArea;
        public final double boxBArea;
        public final double boxABArea;
        // score2 = 两个输入外接矩形面积之和 - 拼接后外接矩形面积，只保留正收益候选。
        public final double score2;
        public final List<Point> rotatedPolygonB;
        public final List<Point> translatedPolygonB;
        public final List<Point> combinedCoordinates;

        /**
         * 候选对象只允许由 PolygonStitcher 内部评分流程创建，避免外部策略绕过 NFP 约束后混入不同评分体系。
         */
        private StitchingCandidate(String sourceType,
                                   int edgeStartIndex,
                                   int edgeEndIndex,
                                   int movingRotationDegrees,
                                   Point placementPoint,
                                   Point translation,
                                   double boxAArea,
                                   double boxBArea,
                                   double boxABArea,
                                   double score2,
                                   List<Point> rotatedPolygonB,
                                   List<Point> translatedPolygonB,
                                   List<Point> combinedCoordinates) {
            this.sourceType = sourceType;
            this.edgeStartIndex = edgeStartIndex;
            this.edgeEndIndex = edgeEndIndex;
            this.movingRotationDegrees = PolygonItem.normalizeRotation(movingRotationDegrees);
            this.placementPoint = copyPoint(placementPoint);
            this.translation = copyPoint(translation);
            this.boxAArea = boxAArea;
            this.boxBArea = boxBArea;
            this.boxABArea = boxABArea;
            this.score2 = score2;
            this.rotatedPolygonB = copyPolygon(rotatedPolygonB);
            this.translatedPolygonB = copyPolygon(translatedPolygonB);
            this.combinedCoordinates = copyPolygon(combinedCoordinates);
        }
    }

    public static final class StitchingResult {
        public final boolean success;
        public final boolean stitched;
        public final String message;
        public final List<Point> polygonA;
        public final List<Point> polygonB;
        public final List<Point> outerNFP;
        public final List<List<Point>> holes;
        public final List<StitchingCandidate> candidates;
        public final StitchingCandidate bestCandidate;

        private StitchingResult(boolean success,
                                boolean stitched,
                                String message,
                                List<Point> polygonA,
                                List<Point> polygonB,
                                List<Point> outerNFP,
                                List<List<Point>> holes,
                                List<StitchingCandidate> candidates,
                                StitchingCandidate bestCandidate) {
            this.success = success;
            this.stitched = stitched;
            this.message = message;
            this.polygonA = copyPolygon(polygonA);
            this.polygonB = copyPolygon(polygonB);
            this.outerNFP = copyPolygon(outerNFP);
            this.holes = copyPolygonList(holes);
            this.candidates = new ArrayList<>(candidates);
            this.bestCandidate = bestCandidate;
        }
    }

    public static StitchingResult findBestStitch(List<Point> polygonA, List<Point> polygonB) {
        return findBestStitch(
                polygonA,
                Geometry.polygonAreaAbs(polygonA),
                boundingBoxArea(polygonA),
                polygonB,
                Geometry.polygonAreaAbs(polygonB),
                Collections.singletonList(0));
    }

    public static StitchingResult findBestStitch(List<Point> polygonA,
                                                 double areaA,
                                                 double boxAArea,
                                                 List<Point> polygonB,
                                                 double areaB,
                                                 List<Integer> movingRotationDegrees) {
        if (polygonA.size() < 3 || polygonB.size() < 3) {
            return rejected("Invalid polygon: less than 3 vertices", polygonA, polygonB,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
        }

        List<Integer> normalizedRotations = PolygonItem.normalizeRotations(movingRotationDegrees);
        List<StitchingCandidate> candidates = new ArrayList<>();
        String lastError = "No NFP contour to sample";

        // 对每一个允许的旋转角，单独计算完整 NFP。
        // 这里同时计算 outerNFP、holes 和 innerLoops，原因是凹多边形的有效贴合位置
        // 经常落在孔洞/内部闭环边界上，只采样最大外边界会漏掉这类候选。
        for (Integer movingRotationDegree : normalizedRotations) {
            List<Point> rotatedPolygonB = Geometry.rotatePolygon(polygonB, movingRotationDegree);
            NFPResult nfpResult = NFPComputer.computeNFP(polygonA, rotatedPolygonB, true, true);
            if (!nfpResult.success) {
                lastError = nfpResult.errorMsg;
                continue;
            }

            List<NfpContour> contours = collectNfpContours(nfpResult);
            if (contours.isEmpty()) {
                continue;
            }

            for (NfpContour contour : contours) {
                candidates.addAll(buildCandidates(
                        polygonA,
                        areaA,
                        boxAArea,
                        rotatedPolygonB,
                        areaB,
                        movingRotationDegree,
                        contour));
            }
        }

        StitchingCandidate bestCandidate = selectBestCandidate(candidates, polygonA);
        if (bestCandidate == null) {
            return rejected(lastError, polygonA, polygonB, new ArrayList<>(), new ArrayList<>(), candidates, null);
        }

        // 返回 bestCandidate 对应旋转角的完整 NFP，便于后续调试/可视化查看外轮廓和孔洞。
        NFPResult selectedNfp = NFPComputer.computeNFP(polygonA, bestCandidate.rotatedPolygonB, true, true);
        List<Point> selectedOuterNfp = selectedNfp.success ? selectedNfp.outerNFP : new ArrayList<>();
        List<List<Point>> selectedHoles = selectedNfp.success ? selectedNfp.holes : new ArrayList<>();
        return new StitchingResult(true, true, "Best stitching placement found", polygonA, polygonB,
                selectedOuterNfp, selectedHoles, candidates, bestCandidate);
    }

    public static double boundingBoxArea(List<Point> polygon) {
        if (polygon.isEmpty()) return 0;
        BBox box = Geometry.polygonBBox(polygon);
        double width = Math.max(0, box.maxX - box.minX);
        double height = Math.max(0, box.maxY - box.minY);
        return width * height;
    }

    public static double intersectionArea(List<Point> first, List<Point> second) {
        if (first.size() < 3 || second.size() < 3) {
            return 0;
        }
        List<List<long[]>> intersections = ClipperBridge.polygonIntersection(
                ClipperBridge.toIntPath(first),
                ClipperBridge.toIntPath(second));
        double area = 0;
        for (List<long[]> intersection : intersections) {
            area += Math.abs(ClipperBridge.intPathArea(intersection)) / 10_000_000_000.0;
        }
        return area;
    }

    public static List<Point> largestUnionBoundary(List<List<Point>> polygons) {
        List<List<long[]>> paths = new ArrayList<>();
        for (List<Point> polygon : polygons) {
            if (polygon.size() >= 3) {
                paths.add(ClipperBridge.toIntPath(polygon));
            }
        }
        List<List<long[]>> unionPaths = ClipperBridge.unionAllPolygons(paths);
        if (unionPaths.isEmpty()) {
            return new ArrayList<>();
        }

        List<long[]> bestPath = null;
        double bestArea = -1;
        for (List<long[]> unionPath : unionPaths) {
            double area = Math.abs(ClipperBridge.intPathArea(unionPath));
            if (area > bestArea) {
                bestArea = area;
                bestPath = unionPath;
            }
        }
        if (bestPath == null) {
            return new ArrayList<>();
        }

        List<Point> boundary = ClipperBridge.fromIntPath(bestPath);
        boundary = Geometry.removeCollinearPoints(boundary);
        Geometry.ensureCCW(boundary);
        return boundary;
    }

    /**
     * 收集 NFPComputer 返回的全部候选轮廓。
     *
     * 用途：凹多边形的 NFP 不一定是单个外边界；holes 和 inner loops 往往对应凹槽、
     * 内部缺陷或局部可贴合边界。统一遍历这些闭环可以扩大候选空间，避免只适配凸多边形。
     */
    private static List<NfpContour> collectNfpContours(NFPResult nfpResult) {
        List<NfpContour> contours = new ArrayList<>();
        addContourIfValid(contours, "OUTER_NFP", nfpResult.outerNFP);

        for (int i = 0; i < nfpResult.holes.size(); i++) {
            addContourIfValid(contours, "HOLE_NFP_" + i, nfpResult.holes.get(i));
        }

        if (!nfpResult.innerLoops.isEmpty()) {
            for (int i = 0; i < nfpResult.innerLoops.size(); i++) {
                addContourIfValid(contours, "INNER_LOOP_" + i, nfpResult.innerLoops.get(i));
            }
        } else {
            addContourIfValid(contours, "INNER_NFP", nfpResult.innerNFP);
        }
        return contours;
    }

    private static void addContourIfValid(List<NfpContour> contours, String sourceType, List<Point> points) {
        // NFP 轮廓至少需要 3 个点才能提供稳定的顶点/中点候选；退化线段不参与拼接评分。
        if (points != null && points.size() >= 3) {
            contours.add(new NfpContour(sourceType, points));
        }
    }

    private static List<StitchingCandidate> buildCandidates(List<Point> polygonA,
                                                            double areaA,
                                                            double boxAArea,
                                                            List<Point> rotatedPolygonB,
                                                            double areaB,
                                                            int movingRotationDegrees,
                                                            NfpContour contour) {
        List<Point> contourPoints = contour.points;
        // 计算密集采样阈值：取两个多边形中较小包围盒对角线长度的比例。
        // 该阈值对所有 NFP 闭环生效，避免 holes/innerLoops 的长边只采样端点和中点。
        double boxBArea = boundingBoxArea(rotatedPolygonB);
        double diagA = Math.sqrt(boxAArea > 0 ? boxAArea : 1);
        double diagB = Math.sqrt(boxBArea > 0 ? boxBArea : 1);
        double denseSampleThreshold = Math.min(diagA, diagB) * DENSE_SAMPLE_DIAGONAL_RATIO;

        List<StitchingCandidate> candidates = new ArrayList<>(contourPoints.size() * 2);
        int vertexCount = contourPoints.size();
        for (int i = 0; i < vertexCount; i++) {
            Point current = contourPoints.get(i);
            Point next = contourPoints.get((i + 1) % vertexCount);

            // 顶点候选：对应 NFP 轮廓上的临界接触位置，适合捕捉角点卡入凹槽的方案。
            candidates.add(scoreCandidate(contour.sourceType + "_VERTEX", i, i, movingRotationDegrees, current,
                    polygonA, areaA, boxAArea, rotatedPolygonB, areaB));

            // 中点候选：补足仅采样顶点时容易漏掉的边贴合位置，尤其适合矩形小件贴合长凹边。
            candidates.add(scoreCandidate(contour.sourceType + "_MIDPOINT", i, (i + 1) % vertexCount, movingRotationDegrees,
                    midpoint(current, next), polygonA, areaA, boxAArea, rotatedPolygonB, areaB));

            // 密集采样：holes/innerLoops 的长边可能对应完整凹槽边界，额外采样能提高凹多边形候选质量。
            double edgeLength = current.distance(next);
            if (edgeLength > denseSampleThreshold) {
                int extraSamples = Math.min(
                        (int) (edgeLength / denseSampleThreshold) - 1,
                        MAX_EXTRA_SAMPLES_PER_EDGE);
                for (int s = 1; s <= extraSamples; s++) {
                    double t = s / (extraSamples + 1.0);
                    Point samplePoint = current.lerp(next, t);
                    candidates.add(scoreCandidate(contour.sourceType + "_DENSE", i, (i + 1) % vertexCount,
                            movingRotationDegrees, samplePoint,
                            polygonA, areaA, boxAArea, rotatedPolygonB, areaB));
                }
            }
        }
        return candidates;
    }

    private static StitchingCandidate scoreCandidate(String sourceType,
                                                     int edgeStartIndex,
                                                     int edgeEndIndex,
                                                     int movingRotationDegrees,
                                                     Point placementPoint,
                                                     List<Point> polygonA,
                                                     double areaA,
                                                     double boxAArea,
                                                     List<Point> rotatedPolygonB,
                                                     double areaB) {
        Point referencePointB = rotatedPolygonB.get(0);
        Point translation = placementPoint.sub(referencePointB);
        List<Point> translatedPolygonB = Geometry.translatePolygon(rotatedPolygonB, translation);
        List<Point> combinedCoordinates = combinePolygons(polygonA, translatedPolygonB);

        double boxBArea = boundingBoxArea(rotatedPolygonB);
        double boxABArea = boundingBoxArea(combinedCoordinates);
        // score2 关注"当前两块分别占用的面积"与"合并后外包框面积"的差值，
        // 这里越大表示越有拼接收益。
        double score2 = boxAArea + boxBArea - boxABArea;

        return new StitchingCandidate(sourceType, edgeStartIndex, edgeEndIndex, movingRotationDegrees,
                placementPoint, translation, boxAArea, boxBArea, boxABArea, score2,
                rotatedPolygonB, translatedPolygonB, combinedCoordinates);
    }

    // ==== 候选选择：只按 score2 保留正收益最高候选 ====
    private static StitchingCandidate selectBestCandidate(List<StitchingCandidate> candidates, List<Point> polygonA) {
        if (candidates.isEmpty()) {
            return null;
        }

        StitchingCandidate bestCandidate = null;
        for (StitchingCandidate candidate : candidates) {
            // 功能说明：score2<=0 表示拼接后外接矩形没有面积收益，直接不作为可用拼接候选。
            if (candidate.score2 <= SCORE_EPS) {
                continue;
            }
            // 功能说明：NFP 采样点仍需做重叠面积校验，保证最终候选是合法相切或贴合位置。
            if (intersectionArea(polygonA, candidate.translatedPolygonB) > SCORE_EPS) {
                continue;
            }
            if (bestCandidate == null || candidate.score2 > bestCandidate.score2 + SCORE_EPS) {
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    private static StitchingResult rejected(String message,
                                            List<Point> polygonA,
                                            List<Point> polygonB,
                                            List<Point> outerNFP,
                                            List<List<Point>> holes,
                                            List<StitchingCandidate> candidates,
                                            StitchingCandidate bestCandidate) {
        return new StitchingResult(true, false, message, polygonA, polygonB, outerNFP, holes, candidates, bestCandidate);
    }

    private static List<Point> combinePolygons(List<Point> polygonA, List<Point> polygonB) {
        List<Point> combined = new ArrayList<>(polygonA.size() + polygonB.size());
        combined.addAll(copyPolygon(polygonA));
        combined.addAll(copyPolygon(polygonB));
        return combined;
    }

    private static Point midpoint(Point a, Point b) {
        return new Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0);
    }

    private static List<Point> copyPolygon(List<Point> polygon) {
        List<Point> copy = new ArrayList<>(polygon.size());
        for (Point point : polygon) {
            copy.add(copyPoint(point));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<List<Point>> copyPolygonList(List<List<Point>> polygons) {
        List<List<Point>> copy = new ArrayList<>(polygons.size());
        for (List<Point> polygon : polygons) {
            copy.add(copyPolygon(polygon));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Point copyPoint(Point point) {
        return new Point(point.x, point.y);
    }

    /**
     * NFP 候选来源轮廓。
     *
     * 用途：把 outerNFP、holes、innerLoops 统一成同一种输入，
     * buildCandidates 不再关心轮廓来源，只负责遍历顶点、中点和密集采样点。
     */
    private static final class NfpContour {
        private final String sourceType;
        private final List<Point> points;

        private NfpContour(String sourceType, List<Point> points) {
            this.sourceType = sourceType;
            this.points = copyPolygon(points);
        }
    }
}