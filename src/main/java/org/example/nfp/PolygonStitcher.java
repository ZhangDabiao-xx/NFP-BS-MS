package org.example.nfp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PolygonStitcher {

    public static final double SCORE_EPS = 1e-6;
    // 组合块达到 98% 填充率后已经足够紧凑，不再把它作为后续 NFP 拼接的主块。
    public static final double TARGET_FILL_RATE = 0.98;
    // NFP 边界经过整数缩放和布尔运算后允许的接触距离，单位与输入坐标一致。
    public static final double CONTACT_DISTANCE_TOLERANCE = 0.01;
    // 组合块至少需要一段有实际长度的边界接触，避免仅角点接触或近距离分离被当成拼接。
    public static final double MIN_CONTACT_LENGTH = 5.0;
    // 外边界扩展必须带来明显的填充率收益；凹腔内部候选仍只需满足正收益。
    // 修改理由：仅有极小的正收益也会让小矩形不断向外叠加，形成尺寸变大的无效 Block。
    public static final double MIN_OUTER_FILL_RATE_GAIN = 0.005;

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
        // 拼接后填充率 = (A 实际面积 + B 实际面积) / 拼接后外接矩形面积。
        public final double combinedFillRate;
        // 相对于主块 A 当前填充率的提升量，是本次 NFP 拼接的新评分。
        public final double fillRateGain;
        // 新工件是否完全位于当前主块外接框内。该指标用于识别开放凹槽和内部空洞中的插入。
        public final boolean cavityInsertion;
        // 拼接后外接矩形相对于主块外接矩形增加的面积；外扩候选需要额外受到该指标约束。
        public final double boxExpansionArea;
        // 候选位置处两个多边形边界的最小距离，用于排除 NFP 误差造成的远距离放置。
        public final double minBoundaryDistance;
        // 候选位置处可重合的最长近似共线边界长度，用于保证形成有效边接触。
        public final double contactLength;
        // score2 继续用于结果输出和诊断；外边界候选还必须通过该指标的紧凑性约束。
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
                                   double combinedFillRate,
                                   double fillRateGain,
                                   boolean cavityInsertion,
                                   double boxExpansionArea,
                                   double minBoundaryDistance,
                                   double contactLength,
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
            this.combinedFillRate = combinedFillRate;
            this.fillRateGain = fillRateGain;
            this.cavityInsertion = cavityInsertion;
            this.boxExpansionArea = boxExpansionArea;
            this.minBoundaryDistance = minBoundaryDistance;
            this.contactLength = contactLength;
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
        // 经过重叠、接触、连通性和填充率提升校验后保留的唯一最优候选。
        public final List<StitchingCandidate> validCandidates;
        public final StitchingCandidate bestCandidate;

        private StitchingResult(boolean success,
                                boolean stitched,
                                String message,
                                List<Point> polygonA,
                                List<Point> polygonB,
                                List<Point> outerNFP,
                                List<List<Point>> holes,
                                List<StitchingCandidate> candidates,
                                List<StitchingCandidate> validCandidates,
                                StitchingCandidate bestCandidate) {
            this.success = success;
            this.stitched = stitched;
            this.message = message;
            this.polygonA = copyPolygon(polygonA);
            this.polygonB = copyPolygon(polygonB);
            this.outerNFP = copyPolygon(outerNFP);
            this.holes = copyPolygonList(holes);
            this.candidates = new ArrayList<>(candidates);
            this.validCandidates = Collections.unmodifiableList(new ArrayList<>(validCandidates));
            this.bestCandidate = bestCandidate;
        }

        /**
         * 兼容旧调用方的候选访问方法。
         *
         * 修改理由：当前外层集束搜索在“工件对”层面保留分支，而同一工件对只保留
         * 最优角度下的最优位置，避免把多个几何位置重复送入外层搜索。
         */
        public List<StitchingCandidate> topCandidates(int limit) {
            if (bestCandidate == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(bestCandidate);
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
        List<List<Point>> fixedPolygons = new ArrayList<>(1);
        fixedPolygons.add(polygonA);
        return findBestStitchForFixedPolygons(
                fixedPolygons, areaA, boxAArea, polygonB, areaB, movingRotationDegrees);
    }

    /**
     * 为由多个已经放置的工件组成的 Block 求解 NFP 拼接。
     *
     * 固定块不能只用一个外轮廓表示：外轮廓会把块内部凹槽和孔洞填平，导致小工件
     * 被错误地判定为无法插入。这里对 Block 中的每个实际工件分别计算外 NFP，
     * 同时收集外边界和 NFP 孔洞边界，再用所有固定工件做统一重叠和连通性校验。
     * 这样既保留了内部空洞候选，也不会把 Block 的内部空白误当成实体。
     *
     * @param fixedPolygons 已经放置在同一坐标系中的固定工件
     * @param areaA         固定 Block 内全部工件的实际面积和
     * @param boxAArea      固定 Block 的外接矩形面积
     * @param polygonB      待插入工件的原始坐标
     * @param areaB         待插入工件的实际面积
     * @param movingRotationDegrees 待插入工件允许的相对旋转角
     */
    public static StitchingResult findBestStitchForFixedPolygons(
            List<List<Point>> fixedPolygons,
            double areaA,
            double boxAArea,
            List<Point> polygonB,
            double areaB,
            List<Integer> movingRotationDegrees) {
        // 兼容旧调用方：未声明为小件时，仍允许经过紧凑性检查的外边界拼接。
        return findBestStitchForFixedPolygons(
                fixedPolygons,
                areaA,
                boxAArea,
                polygonB,
                areaB,
                movingRotationDegrees,
                false);
    }

    /**
     * 为复合 Block 求解带有插入策略的 NFP 拼接。
     *
     * @param requireCavityInsertion 是否要求移动工件完全落在当前 Block 外接框内。
     *                               小件使用该模式，避免其沿 Block 外边界继续扩张。
     */
    public static StitchingResult findBestStitchForFixedPolygons(
            List<List<Point>> fixedPolygons,
            double areaA,
            double boxAArea,
            List<Point> polygonB,
            double areaB,
            List<Integer> movingRotationDegrees,
            boolean requireCavityInsertion) {
        List<Point> firstFixedPolygon = fixedPolygons == null || fixedPolygons.isEmpty()
                ? new ArrayList<>()
                : fixedPolygons.get(0);
        if (!hasValidPolygons(fixedPolygons) || polygonB == null || polygonB.size() < 3) {
            return rejected("Invalid polygon: less than 3 vertices", firstFixedPolygon, polygonB,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
        }

        List<Integer> normalizedRotations = PolygonItem.normalizeRotations(movingRotationDegrees);
        String lastError = "No NFP contour to sample";
        StitchingCandidate bestCandidate = null;
        List<Point> bestOuterNfp = new ArrayList<>();
        List<List<Point>> bestHoles = new ArrayList<>();

        // 仅保存最终的全局最优候选；中间的顶点/中点候选在本轮评分后立即释放，
        // 避免 NFP 缓存把大量无效位置长期保存在内存中。
        List<StitchingCandidate> retainedCandidates = new ArrayList<>(1);

        for (Integer movingRotationDegree : normalizedRotations) {
            List<Point> rotatedPolygonB = Geometry.rotatePolygon(polygonB, movingRotationDegree);
            List<StitchingCandidate> rotationCandidates = new ArrayList<>();
            List<Point> rotationOuterNfp = new ArrayList<>();
            List<List<Point>> rotationHoles = new ArrayList<>();

            // 对复合块的每个成员分别建立 NFP。每个成员的 NFP 孔洞都是潜在的凹槽接触边界，
            // 不能只保留固定块的最大外轮廓。
            for (List<Point> fixedPolygon : fixedPolygons) {
                // 外部拼接只需要外 NFP；内 NFP 表示 B 完全位于 A 内部，最终会被重叠校验排除。
                NFPResult nfpResult = NFPComputer.computeNFP(fixedPolygon, rotatedPolygonB, true, false);
                if (!nfpResult.success) {
                    lastError = nfpResult.errorMsg;
                    continue;
                }

                if (rotationOuterNfp.isEmpty() && !nfpResult.outerNFP.isEmpty()) {
                    // StitchingResult 的 NFP 字段用于诊断，保存当前旋转的第一个有效 NFP。
                    rotationOuterNfp = copyPolygon(nfpResult.outerNFP);
                    rotationHoles = copyPolygonList(nfpResult.holes);
                }

                for (NfpContour contour : collectNfpContours(nfpResult)) {
                    rotationCandidates.addAll(buildCandidates(
                            fixedPolygons,
                            areaA,
                            boxAArea,
                            rotatedPolygonB,
                            areaB,
                            movingRotationDegree,
                            contour));
                }
            }

            if (rotationCandidates.isEmpty()) {
                continue;
            }

            // 每个旋转只返回一个通过精确连通性校验的最佳位置，随后再在角度之间比较全局最优。
            List<StitchingCandidate> validRotationCandidates = filterValidCandidates(
                    rotationCandidates, fixedPolygons, requireCavityInsertion);
            StitchingCandidate rotationBestCandidate = selectBestCandidate(validRotationCandidates);
            if (isBetterCandidate(rotationBestCandidate, bestCandidate)) {
                bestCandidate = rotationBestCandidate;
                bestOuterNfp = rotationOuterNfp;
                bestHoles = rotationHoles;
                retainedCandidates.clear();
                retainedCandidates.add(rotationBestCandidate);
            }
        }

        if (bestCandidate == null) {
            return rejected(lastError, firstFixedPolygon, polygonB, new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), null);
        }

        return new StitchingResult(true, true, "Best stitching placement found", firstFixedPolygon, polygonB,
                bestOuterNfp, bestHoles, retainedCandidates, retainedCandidates, bestCandidate);
    }

    public static double boundingBoxArea(List<Point> polygon) {
        if (polygon.isEmpty()) return 0;
        BBox box = Geometry.polygonBBox(polygon);
        double width = Math.max(0, box.maxX - box.minX);
        double height = Math.max(0, box.maxY - box.minY);
        return width * height;
    }

    /** 计算多个固定工件合并后的轴对齐外接框，供凹腔候选判定复用。 */
    private static BBox boundingBoxOfPolygons(List<List<Point>> polygons) {
        List<Point> allPoints = new ArrayList<>();
        for (List<Point> polygon : polygons) {
            if (polygon != null) {
                allPoints.addAll(polygon);
            }
        }
        if (allPoints.isEmpty()) {
            return new BBox(0, 0, 0, 0);
        }
        return Geometry.polygonBBox(allPoints);
    }

    /**
     * 判断移动工件的外接框是否完全位于当前 Block 的外接框内。
     *
     * 这不是用外接框代替最终几何合法性检查；重叠和并集连通性仍由后续精确检查负责。
     * 该判断只用于识别“有机会填入现有空白区域”的候选，并阻止小件向外扩张 Block。
     */
    private static boolean isInsideBoundingBox(BBox movingBox, BBox baseBox) {
        return movingBox.minX >= baseBox.minX - CONTACT_DISTANCE_TOLERANCE
                && movingBox.maxX <= baseBox.maxX + CONTACT_DISTANCE_TOLERANCE
                && movingBox.minY >= baseBox.minY - CONTACT_DISTANCE_TOLERANCE
                && movingBox.maxY <= baseBox.maxY + CONTACT_DISTANCE_TOLERANCE;
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

    /**
     * 计算多个工件并集的全部边界轮廓。
     *
     * 旧实现只返回面积最大的轮廓，多个不连通工件组成的 Block 会因此丢失部分几何信息。
     * 这里保留所有布尔并集路径，调用方可以据此判断连通分量并完整绘制组合块。
     */
    public static List<List<Point>> unionBoundaries(List<List<Point>> polygons) {
        List<List<long[]>> paths = new ArrayList<>();
        for (List<Point> polygon : polygons) {
            if (polygon.size() >= 3) {
                paths.add(ClipperBridge.toIntPath(polygon));
            }
        }
        List<List<long[]>> unionPaths = ClipperBridge.unionAllPolygons(paths);
        List<List<Point>> boundaries = new ArrayList<>(unionPaths.size());
        for (List<long[]> unionPath : unionPaths) {
            List<Point> boundary = ClipperBridge.fromIntPath(unionPath);
            boundary = Geometry.removeCollinearPoints(boundary);
            if (boundary.size() >= 3 && Geometry.polygonAreaAbs(boundary) > Geometry.EPS) {
                Geometry.ensureCCW(boundary);
                boundaries.add(boundary);
            }
        }
        return boundaries;
    }

    /**
     * 从并集路径中筛选外轮廓。
     *
     * Java2D Area 会同时返回外轮廓和孔洞。孔洞的首点位于其他路径内部，不能被当成独立工件分量。
     */
    public static List<List<Point>> outerUnionBoundaries(List<List<Point>> unionBoundaries) {
        List<List<Point>> outerBoundaries = new ArrayList<>();
        for (int i = 0; i < unionBoundaries.size(); i++) {
            List<Point> boundary = unionBoundaries.get(i);
            if (boundary.isEmpty()) {
                continue;
            }

            boolean isHole = false;
            Point probe = boundary.get(0);
            for (int j = 0; j < unionBoundaries.size(); j++) {
                if (i == j) {
                    continue;
                }
                if (Geometry.pointInPolygon(probe, unionBoundaries.get(j)) == 1) {
                    isHole = true;
                    break;
                }
            }
            if (!isHole) {
                outerBoundaries.add(copyPolygon(boundary));
            }
        }
        return outerBoundaries;
    }

    /** 返回并集中的外部连通分量数量。 */
    public static int countOuterUnionComponents(List<List<Point>> unionBoundaries) {
        return outerUnionBoundaries(unionBoundaries).size();
    }

    /**
     * 兼容旧调用方的最大外轮廓接口。
     * 新的 Block 不再依赖该方法表示完整组合块，而是保存 unionBoundaries 的全部路径。
     */
    @Deprecated
    public static List<Point> largestUnionBoundary(List<List<Point>> polygons) {
        List<List<Point>> allBoundaries = unionBoundaries(polygons);
        List<List<Point>> outerBoundaries = outerUnionBoundaries(allBoundaries);
        List<List<Point>> candidates = outerBoundaries.isEmpty() ? allBoundaries : outerBoundaries;
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        List<Point> bestBoundary = candidates.get(0);
        double bestArea = Geometry.polygonAreaAbs(bestBoundary);
        for (int i = 1; i < candidates.size(); i++) {
            List<Point> boundary = candidates.get(i);
            double area = Geometry.polygonAreaAbs(boundary);
            if (area > bestArea) {
                bestArea = area;
                bestBoundary = boundary;
            }
        }
        return copyPolygon(bestBoundary);
    }

    /**
     * 收集 NFPComputer 返回的全部候选轮廓。
     *
     * 外部拼接使用外 NFP 的外边界和孔洞边界：孔洞边界可能对应凹槽内的合法接触位置。
     * 所有候选仍必须通过无正面积重叠、接触长度和精确并集连通校验；内 NFP 表示包含关系，
     * 不属于当前的外部拼接场景，因此明确排除。
     */
    private static List<NfpContour> collectNfpContours(NFPResult nfpResult) {
        List<NfpContour> contours = new ArrayList<>();
        addContourIfValid(contours, "OUTER_NFP", nfpResult.outerNFP);
        for (int i = 0; i < nfpResult.holes.size(); i++) {
            addContourIfValid(contours, "HOLE_NFP_" + i, nfpResult.holes.get(i));
        }
        return contours;
    }

    private static void addContourIfValid(List<NfpContour> contours, String sourceType, List<Point> points) {
        // NFP 轮廓至少需要 3 个点才能提供稳定的顶点/中点候选；退化线段不参与拼接评分。
        if (points != null && points.size() >= 3) {
            contours.add(new NfpContour(sourceType, points));
        }
    }

    private static List<StitchingCandidate> buildCandidates(List<List<Point>> fixedPolygons,
                                                            double areaA,
                                                            double boxAArea,
                                                            List<Point> rotatedPolygonB,
                                                            double areaB,
                                                            int movingRotationDegrees,
                                                            NfpContour contour) {
        List<Point> contourPoints = contour.points;
        // 只取每条 NFP 边的两个端点和一个中点。
        // 修改理由：原来的长边密集采样会使候选数量随边长线性膨胀，且大量候选的几何质量
        // 相近；顶点负责角点卡位，中点负责长边贴合，已经覆盖本次拼接所需的代表位置。
        List<StitchingCandidate> candidates = new ArrayList<>(contourPoints.size() * 2);
        // 固定多边形的外接框在同一个 NFP 轮廓中不变，提前计算一次，
        // 用于判断候选是否落入现有 Block 的凹槽/空洞区域。
        BBox baseBox = boundingBoxOfPolygons(fixedPolygons);
        int vertexCount = contourPoints.size();
        for (int i = 0; i < vertexCount; i++) {
            Point current = contourPoints.get(i);
            Point next = contourPoints.get((i + 1) % vertexCount);

            // 顶点候选：对应 NFP 轮廓上的临界接触位置，适合捕捉角点卡入凹槽的方案。
            candidates.add(scoreCandidate(contour.sourceType + "_VERTEX", i, i, movingRotationDegrees, current,
                    fixedPolygons, baseBox, areaA, boxAArea, rotatedPolygonB, areaB));

            // 中点候选：补足仅采样顶点时容易漏掉的边贴合位置，尤其适合矩形小件贴合长凹边。
            candidates.add(scoreCandidate(contour.sourceType + "_MIDPOINT", i, (i + 1) % vertexCount, movingRotationDegrees,
                    midpoint(current, next), fixedPolygons, baseBox, areaA, boxAArea, rotatedPolygonB, areaB));
        }
        return candidates;
    }

    private static StitchingCandidate scoreCandidate(String sourceType,
                                                     int edgeStartIndex,
                                                     int edgeEndIndex,
                                                     int movingRotationDegrees,
                                                     Point placementPoint,
                                                     List<List<Point>> fixedPolygons,
                                                     BBox baseBox,
                                                     double areaA,
                                                     double boxAArea,
                                                     List<Point> rotatedPolygonB,
                                                     double areaB) {
        Point referencePointB = rotatedPolygonB.get(0);
        Point translation = placementPoint.sub(referencePointB);
        List<Point> translatedPolygonB = Geometry.translatePolygon(rotatedPolygonB, translation);
        List<Point> combinedCoordinates = combineFixedPolygons(fixedPolygons, translatedPolygonB);

        double boxBArea = boundingBoxArea(rotatedPolygonB);
        double boxABArea = boundingBoxArea(combinedCoordinates);
        double baseFillRate = calculateFillRate(areaA, boxAArea);
        double combinedFillRate = calculateFillRate(areaA + areaB, boxABArea);
        double fillRateGain = combinedFillRate - baseFillRate;
        BBox movingBox = Geometry.polygonBBox(translatedPolygonB);
        boolean cavityInsertion = isInsideBoundingBox(movingBox, baseBox);
        double boxExpansionArea = Math.max(0.0, boxABArea - boxAArea);
        double minBoundaryDistance = Double.POSITIVE_INFINITY;
        double contactLength = 0.0;
        for (List<Point> fixedPolygon : fixedPolygons) {
            minBoundaryDistance = Math.min(
                    minBoundaryDistance,
                    minimumBoundaryDistance(fixedPolygon, translatedPolygonB));
            contactLength = Math.max(
                    contactLength,
                    maximumContactLength(fixedPolygon, translatedPolygonB, CONTACT_DISTANCE_TOLERANCE));
        }

        // score2 表示合并后节省的外接矩形面积；它用于限制外扩候选，避免只看填充率。
        double score2 = boxAArea + boxBArea - boxABArea;

        return new StitchingCandidate(sourceType, edgeStartIndex, edgeEndIndex, movingRotationDegrees,
                placementPoint, translation, boxAArea, boxBArea, boxABArea,
                combinedFillRate, fillRateGain, cavityInsertion, boxExpansionArea,
                minBoundaryDistance, contactLength, score2,
                rotatedPolygonB, translatedPolygonB, combinedCoordinates);
    }

    /**
     * 判断候选是否属于高质量的外边界互补闭合。
     *
     * 修改理由：smallItem 通常用于填补凹腔，但某些小型不规则工件也可能与另一个工件
     * 沿斜边互补，直接把组合块闭合成接近矩形的形状。此类候选虽然会越过当前 Block
     * 的外接框，却不属于之前需要拦截的“低质量向外扩张”。
     *
     * 这里只提供“高质量闭合”的例外条件，不放行一般外扩：
     * 1) 拼接后填充率达到目标值；
     * 2) 组合块没有明显损失外接框面积，允许 score2 在数值误差范围内等于 0；
     * 3) 两个工件有足够长度的边界接触。
     * 正面积重叠、并集连通性等条件仍由 filterValidCandidates 后续检查。
     */
    static boolean isHighQualityOuterClosure(StitchingCandidate candidate) {
        return candidate != null
                && candidate.combinedFillRate >= TARGET_FILL_RATE - SCORE_EPS
                && candidate.score2 >= -SCORE_EPS
                && candidate.contactLength >= MIN_CONTACT_LENGTH - SCORE_EPS;
    }

    /**
     * 过滤候选的几何质量和硬约束。
     *
     * 修改理由：仅要求“填充率增加”会把沿 Block 外边界添加长条矩形也视为有效拼接。
     * 这类候选可能增加组合块尺寸、得到负 score2，并降低第二阶段矩形排样能力。
     * 现在把候选分成三类：
     * 1) 凹腔候选：移动工件完全位于当前 Block 外接框内，优先保留；
     * 2) 高质量外边界闭合：填充率达到目标值，可以作为 smallItem 的有限例外；
     * 3) 普通外边界候选：只有局部外接框收益为正且填充率有明显提升时才允许。
     *
     * requireCavityInsertion=true 时仍然禁止普通外边界候选，只有第二类闭合候选可以通过。
     */
    private static List<StitchingCandidate> filterValidCandidates(List<StitchingCandidate> candidates,
                                                                    List<List<Point>> fixedPolygons,
                                                                    boolean requireCavityInsertion) {
        List<StitchingCandidate> geometricCandidates = new ArrayList<>();
        for (StitchingCandidate candidate : candidates) {
            if (candidate.fillRateGain <= SCORE_EPS) {
                continue;
            }

            boolean highQualityOuterClosure = isHighQualityOuterClosure(candidate);
            if (requireCavityInsertion && !candidate.cavityInsertion && !highQualityOuterClosure) {
                // smallItem 仍不能进行普通外扩；高质量互补闭合由专门的例外条件放行。
                continue;
            }

            if (!candidate.cavityInsertion
                    && !highQualityOuterClosure
                    && (candidate.score2 <= SCORE_EPS
                    || candidate.fillRateGain < MIN_OUTER_FILL_RATE_GAIN - SCORE_EPS)) {
                // 普通外扩若没有节省外接矩形面积，或收益过小，仍会制造不可排样的大块。
                continue;
            }

            if (candidate.minBoundaryDistance > CONTACT_DISTANCE_TOLERANCE + SCORE_EPS
                    || candidate.contactLength < MIN_CONTACT_LENGTH - SCORE_EPS) {
                continue;
            }

            // NFP 边界候选仍需经过精确重叠检测，避免数值误差产生正面积穿透。
            boolean overlaps = false;
            for (List<Point> fixedPolygon : fixedPolygons) {
                if (mayHavePositiveBBoxOverlap(fixedPolygon, candidate.translatedPolygonB)
                        && intersectionArea(fixedPolygon, candidate.translatedPolygonB) > SCORE_EPS) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue;
            }

            // 接触长度和距离是第一层快速筛选；先收集候选，后续按评分排序后再做精确连通校验。
            geometricCandidates.add(candidate);
        }

        geometricCandidates.sort(PolygonStitcher::compareCandidates);

        for (StitchingCandidate candidate : geometricCandidates) {
            // 修改理由：仅靠接触长度仍可能接受小间隙；对排序后的候选执行精确并集，
            // 确保送入 beam search 的位置确实属于同一连通块。
            if (hasSingleOuterUnionComponent(fixedPolygons, candidate.translatedPolygonB)) {
                // 每个旋转只保留一个通过精确校验的候选；排序已优先选择凹腔候选，
                // 外边界候选则优先选择更高 score2 和更小的外接框扩张。
                return Collections.singletonList(candidate);
            }
        }
        return Collections.emptyList();
    }

    /** 精确判断固定 Block 加入新工件后是否仍只有一个外部连通分量。 */
    private static boolean hasSingleOuterUnionComponent(List<List<Point>> fixedPolygons,
                                                         List<Point> second) {
        List<List<Point>> polygons = new ArrayList<>(fixedPolygons.size() + 1);
        polygons.addAll(fixedPolygons);
        polygons.add(second);
        return countOuterUnionComponents(unionBoundaries(polygons)) == 1;
    }

    // ==== 候选选择：按绝对填充率、接触质量和增益排序 ====
    private static StitchingCandidate selectBestCandidate(List<StitchingCandidate> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(0);
    }

    /**
     * 比较两个 NFP 候选。
     *
     * 修改理由：旧排序只按填充率，可能让外边界扩展候选压过真正的凹腔候选。
     * 现在先比较候选类型，再比较外接框收益，最后比较填充率，确保“填凹腔”优先于“向外摊开”。
     */
    private static boolean isBetterCandidate(StitchingCandidate candidate,
                                              StitchingCandidate currentBest) {
        if (candidate == null) {
            return false;
        }
        if (currentBest == null) {
            return true;
        }
        return compareCandidates(candidate, currentBest) < 0;
    }

    /**
     * 与 isBetterCandidate 相同的排序方向，供有效候选和跨旋转候选稳定排序。
     * 凹腔候选优先；外边界候选先比较 score2 和外接框扩张，再比较填充率。
     */
    private static int compareCandidates(StitchingCandidate left, StitchingCandidate right) {
        if (left.cavityInsertion != right.cavityInsertion) {
            return left.cavityInsertion ? -1 : 1;
        }
        if (!left.cavityInsertion && Math.abs(left.score2 - right.score2) > SCORE_EPS) {
            return Double.compare(right.score2, left.score2);
        }
        if (Math.abs(left.boxExpansionArea - right.boxExpansionArea) > SCORE_EPS) {
            return Double.compare(left.boxExpansionArea, right.boxExpansionArea);
        }
        if (Math.abs(left.combinedFillRate - right.combinedFillRate) > SCORE_EPS) {
            return Double.compare(right.combinedFillRate, left.combinedFillRate);
        }
        if (Math.abs(left.contactLength - right.contactLength) > SCORE_EPS) {
            return Double.compare(right.contactLength, left.contactLength);
        }
        if (Math.abs(left.fillRateGain - right.fillRateGain) > SCORE_EPS) {
            return Double.compare(right.fillRateGain, left.fillRateGain);
        }
        if (Math.abs(left.minBoundaryDistance - right.minBoundaryDistance) > SCORE_EPS) {
            return Double.compare(left.minBoundaryDistance, right.minBoundaryDistance);
        }
        return Double.compare(right.score2, left.score2);
    }

    /** 面积相交前的包围盒快速判断，避免对明显分离的多边形创建 Area。 */
    static boolean mayHavePositiveBBoxOverlap(List<Point> first, List<Point> second) {
        if (first.size() < 3 || second.size() < 3) {
            return false;
        }
        BBox firstBox = Geometry.polygonBBox(first);
        BBox secondBox = Geometry.polygonBBox(second);
        return firstBox.minX < secondBox.maxX - SCORE_EPS
                && secondBox.minX < firstBox.maxX - SCORE_EPS
                && firstBox.minY < secondBox.maxY - SCORE_EPS
                && secondBox.minY < firstBox.maxY - SCORE_EPS;
    }

    /**
     * 计算两个多边形边界之间的最小距离。
     *
     * 该方法只处理边界距离，不把两个多边形的外接矩形重叠误认为工件接触。
     * 由于候选数量较多，先用边段外接盒做一次廉价剪枝，再计算线段距离。
     */
    public static double minimumBoundaryDistance(List<Point> first, List<Point> second) {
        if (first.size() < 3 || second.size() < 3) {
            return Double.POSITIVE_INFINITY;
        }

        double minimumDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < first.size(); i++) {
            Point firstStart = first.get(i);
            Point firstEnd = first.get((i + 1) % first.size());
            for (int j = 0; j < second.size(); j++) {
                Point secondStart = second.get(j);
                Point secondEnd = second.get((j + 1) % second.size());

                if (segmentBoxesAreFartherThan(firstStart, firstEnd,
                        secondStart, secondEnd, minimumDistance)) {
                    continue;
                }
                double distance = segmentDistance(firstStart, firstEnd, secondStart, secondEnd);
                if (distance < minimumDistance) {
                    minimumDistance = distance;
                    if (minimumDistance <= Geometry.EPS) {
                        return 0.0;
                    }
                }
            }
        }
        return minimumDistance;
    }

    /**
     * 计算两个多边形之间最长的近似共线接触边长度。
     *
     * 只统计方向平行且位于 CONTACT_DISTANCE_TOLERANCE 内的边段重叠长度，
     * 因此单点接触不会被误判为高质量拼接。
     */
    public static double maximumContactLength(List<Point> first,
                                              List<Point> second,
                                              double distanceTolerance) {
        if (first.size() < 3 || second.size() < 3) {
            return 0.0;
        }

        double maximumLength = 0.0;
        for (int i = 0; i < first.size(); i++) {
            Point firstStart = first.get(i);
            Point firstEnd = first.get((i + 1) % first.size());
            Point firstVector = firstEnd.sub(firstStart);
            double firstLength = firstVector.length();
            if (firstLength <= Geometry.EPS) {
                continue;
            }

            Point firstDirection = firstVector.div(firstLength);
            for (int j = 0; j < second.size(); j++) {
                Point secondStart = second.get(j);
                Point secondEnd = second.get((j + 1) % second.size());
                Point secondVector = secondEnd.sub(secondStart);
                double secondLength = secondVector.length();
                if (secondLength <= Geometry.EPS) {
                    continue;
                }

                Point secondDirection = secondVector.div(secondLength);
                if (Math.abs(firstDirection.cross(secondDirection)) > 1e-6) {
                    continue;
                }

                double lineDistanceStart = Math.abs(firstVector.cross(secondStart.sub(firstStart)))
                        / firstLength;
                double lineDistanceEnd = Math.abs(firstVector.cross(secondEnd.sub(firstStart)))
                        / firstLength;
                if (lineDistanceStart > distanceTolerance || lineDistanceEnd > distanceTolerance) {
                    continue;
                }

                double firstProjectionStart = 0.0;
                double firstProjectionEnd = firstLength;
                double secondProjectionStart = secondStart.sub(firstStart).dot(firstDirection);
                double secondProjectionEnd = secondEnd.sub(firstStart).dot(firstDirection);
                double secondMinimum = Math.min(secondProjectionStart, secondProjectionEnd);
                double secondMaximum = Math.max(secondProjectionStart, secondProjectionEnd);
                double overlapLength = Math.min(firstProjectionEnd, secondMaximum)
                        - Math.max(firstProjectionStart, secondMinimum);
                if (overlapLength > maximumLength) {
                    maximumLength = overlapLength;
                }
            }
        }
        return maximumLength;
    }

    private static boolean segmentBoxesAreFartherThan(Point firstStart,
                                                       Point firstEnd,
                                                       Point secondStart,
                                                       Point secondEnd,
                                                       double distance) {
        if (!Double.isFinite(distance)) {
            return false;
        }

        double firstMinX = Math.min(firstStart.x, firstEnd.x);
        double firstMaxX = Math.max(firstStart.x, firstEnd.x);
        double firstMinY = Math.min(firstStart.y, firstEnd.y);
        double firstMaxY = Math.max(firstStart.y, firstEnd.y);
        double secondMinX = Math.min(secondStart.x, secondEnd.x);
        double secondMaxX = Math.max(secondStart.x, secondEnd.x);
        double secondMinY = Math.min(secondStart.y, secondEnd.y);
        double secondMaxY = Math.max(secondStart.y, secondEnd.y);

        double horizontalGap = Math.max(0.0,
                Math.max(firstMinX - secondMaxX, secondMinX - firstMaxX));
        double verticalGap = Math.max(0.0,
                Math.max(firstMinY - secondMaxY, secondMinY - firstMaxY));
        return Math.hypot(horizontalGap, verticalGap) > distance;
    }

    private static double segmentDistance(Point firstStart,
                                          Point firstEnd,
                                          Point secondStart,
                                          Point secondEnd) {
        if (segmentsIntersectOrTouch(firstStart, firstEnd, secondStart, secondEnd)) {
            return 0.0;
        }

        double firstToSecondStart = pointToSegmentDistance(secondStart, firstStart, firstEnd);
        double firstToSecondEnd = pointToSegmentDistance(secondEnd, firstStart, firstEnd);
        double secondToFirstStart = pointToSegmentDistance(firstStart, secondStart, secondEnd);
        double secondToFirstEnd = pointToSegmentDistance(firstEnd, secondStart, secondEnd);
        return Math.min(Math.min(firstToSecondStart, firstToSecondEnd),
                Math.min(secondToFirstStart, secondToFirstEnd));
    }

    private static double pointToSegmentDistance(Point point, Point start, Point end) {
        Point direction = end.sub(start);
        double lengthSq = direction.lengthSq();
        if (lengthSq <= Geometry.EPS) {
            return point.distance(start);
        }

        double parameter = point.sub(start).dot(direction) / lengthSq;
        parameter = Math.max(0.0, Math.min(1.0, parameter));
        Point projection = start.add(direction.mul(parameter));
        return point.distance(projection);
    }

    private static boolean segmentsIntersectOrTouch(Point firstStart,
                                                     Point firstEnd,
                                                     Point secondStart,
                                                     Point secondEnd) {
        double firstOrientation = Geometry.cross(firstStart, firstEnd, secondStart);
        double secondOrientation = Geometry.cross(firstStart, firstEnd, secondEnd);
        double thirdOrientation = Geometry.cross(secondStart, secondEnd, firstStart);
        double fourthOrientation = Geometry.cross(secondStart, secondEnd, firstEnd);

        boolean properIntersection = ((firstOrientation > SCORE_EPS && secondOrientation < -SCORE_EPS)
                || (firstOrientation < -SCORE_EPS && secondOrientation > SCORE_EPS))
                && ((thirdOrientation > SCORE_EPS && fourthOrientation < -SCORE_EPS)
                || (thirdOrientation < -SCORE_EPS && fourthOrientation > SCORE_EPS));
        if (properIntersection) {
            return true;
        }

        return (Math.abs(firstOrientation) <= SCORE_EPS
                && Geometry.onSegment(secondStart, firstStart, firstEnd))
                || (Math.abs(secondOrientation) <= SCORE_EPS
                && Geometry.onSegment(secondEnd, firstStart, firstEnd))
                || (Math.abs(thirdOrientation) <= SCORE_EPS
                && Geometry.onSegment(firstStart, secondStart, secondEnd))
                || (Math.abs(fourthOrientation) <= SCORE_EPS
                && Geometry.onSegment(firstEnd, secondStart, secondEnd));
    }

    private static double calculateFillRate(double area, double boxArea) {
        if (boxArea <= SCORE_EPS) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, area / boxArea));
    }

    private static StitchingResult rejected(String message,
                                            List<Point> polygonA,
                                            List<Point> polygonB,
                                            List<Point> outerNFP,
                                            List<List<Point>> holes,
                                            List<StitchingCandidate> candidates,
                                            List<StitchingCandidate> validCandidates,
                                            StitchingCandidate bestCandidate) {
        return new StitchingResult(true, false, message, polygonA, polygonB, outerNFP, holes,
                candidates, validCandidates, bestCandidate);
    }

    private static List<Point> combinePolygons(List<Point> polygonA, List<Point> polygonB) {
        List<Point> combined = new ArrayList<>(polygonA.size() + polygonB.size());
        combined.addAll(copyPolygon(polygonA));
        combined.addAll(copyPolygon(polygonB));
        return combined;
    }

    /** 合并复合 Block 的所有成员坐标和新工件坐标，用于计算整体外接矩形。 */
    private static List<Point> combineFixedPolygons(List<List<Point>> fixedPolygons,
                                                    List<Point> polygonB) {
        int pointCount = polygonB.size();
        for (List<Point> fixedPolygon : fixedPolygons) {
            pointCount += fixedPolygon.size();
        }

        List<Point> combined = new ArrayList<>(pointCount);
        for (List<Point> fixedPolygon : fixedPolygons) {
            combined.addAll(copyPolygon(fixedPolygon));
        }
        combined.addAll(copyPolygon(polygonB));
        return combined;
    }

    private static boolean hasValidPolygons(List<List<Point>> polygons) {
        if (polygons == null || polygons.isEmpty()) {
            return false;
        }
        for (List<Point> polygon : polygons) {
            if (polygon == null || polygon.size() < 3) {
                return false;
            }
        }
        return true;
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
     * 用途：统一封装外 NFP 轮廓，使 buildCandidates 不必关心轮廓对象的来源，
     * 只负责遍历顶点和中点。
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
