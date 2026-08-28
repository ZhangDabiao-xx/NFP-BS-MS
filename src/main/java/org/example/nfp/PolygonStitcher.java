package org.example.nfp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PolygonStitcher {

    public static final double SCORE_EPS = 1e-6;
    // 组合块达到 98% 填充率后已经足够紧凑，不再把它作为后续 NFP 拼接的主块。
    public static final double TARGET_FILL_RATE = 0.98;
    // 新组合块的最低绝对填充率。仅仅比原块提高一点但整体仍很松散的候选不再接受。
    // 0.85 作为默认底线，既能排除明显松散的组合，又不会把有长边有效接触的可用块全部过滤掉。
    public static final double MIN_COMBINED_FILL_RATE = 0.85;
    // NFP 边界经过整数缩放和布尔运算后允许的接触距离，单位与输入坐标一致。
    public static final double CONTACT_DISTANCE_TOLERANCE = 0.01;
    // 组合块至少需要一段有实际长度的边界接触，避免仅角点接触或近距离分离被当成拼接。
    public static final double MIN_CONTACT_LENGTH = 5.0;
    // 每个“主块 + 工件”组合返回的候选位置数量，供外层 beam search 保留不同几何方案。
    public static final int DEFAULT_TOP_CANDIDATE_COUNT = 3;
    // 每个旋转最多保留的精确连通候选数；先按廉价指标排序，再对少量前排候选执行布尔并集。
    private static final int MAX_CONNECTED_CANDIDATES_PER_ROTATION = DEFAULT_TOP_CANDIDATE_COUNT;

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
        // 拼接后填充率 = (A 实际面积 + B 实际面积) / 拼接后外接矩形面积。
        public final double combinedFillRate;
        // 相对于主块 A 当前填充率的提升量，是本次 NFP 拼接的新评分。
        public final double fillRateGain;
        // 候选位置处两个多边形边界的最小距离，用于排除 NFP 误差造成的远距离放置。
        public final double minBoundaryDistance;
        // 候选位置处可重合的最长近似共线边界长度，用于保证形成有效边接触。
        public final double contactLength;
        // 旧 score2 仅保留用于兼容结果输出和诊断，不再作为候选接受条件。
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
        // 经过重叠、接触和绝对填充率校验的候选，按质量从高到低排列。
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

        /** 返回当前 NFP 搜索中最优的若干个不同放置位置，供 beam search 使用。 */
        public List<StitchingCandidate> topCandidates(int limit) {
            int count = Math.max(1, limit);
            List<StitchingCandidate> result = new ArrayList<>();
            Set<String> signatures = new HashSet<>();
            for (StitchingCandidate candidate : validCandidates) {
                if (!signatures.add(candidateSignature(candidate))) {
                    continue;
                }
                result.add(candidate);
                if (result.size() >= count) {
                    break;
                }
            }
            return result;
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
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
        }

        List<Integer> normalizedRotations = PolygonItem.normalizeRotations(movingRotationDegrees);
        List<StitchingCandidate> candidates = new ArrayList<>();
        List<StitchingCandidate> validCandidates = new ArrayList<>();
        String lastError = "No NFP contour to sample";
        StitchingCandidate bestCandidate = null;
        List<Point> bestOuterNfp = new ArrayList<>();
        List<List<Point>> bestHoles = new ArrayList<>();

        // 对每一个允许的旋转角，单独计算外 NFP。
        // NFP 拼接是两个工件的外部相切，候选还会经过多边形重叠校验，因此只计算外 NFP。
        // 修改理由：原来同时计算内 NFP；内 NFP 表示被移动工件位于固定工件内部的可行域，
        // 这类候选最终会被 intersectionArea 判为重叠，属于当前外部拼接场景中的重复计算。
        for (Integer movingRotationDegree : normalizedRotations) {
            List<Point> rotatedPolygonB = Geometry.rotatePolygon(polygonB, movingRotationDegree);
            NFPResult nfpResult = NFPComputer.computeNFP(polygonA, rotatedPolygonB, true, false);
            if (!nfpResult.success) {
                lastError = nfpResult.errorMsg;
                continue;
            }

            List<NfpContour> contours = collectNfpContours(nfpResult);
            if (contours.isEmpty()) {
                continue;
            }

            List<StitchingCandidate> rotationCandidates = new ArrayList<>();
            for (NfpContour contour : contours) {
                rotationCandidates.addAll(buildCandidates(
                        polygonA,
                        areaA,
                        boxAArea,
                        rotatedPolygonB,
                        areaB,
                        movingRotationDegree,
                        contour));
            }
            candidates.addAll(rotationCandidates);

            // 先执行统一合法性过滤，再将有效候选加入结果；这样调用方可以保留同一对工件的多个位置。
            List<StitchingCandidate> validRotationCandidates = filterValidCandidates(
                    rotationCandidates, polygonA);
            validCandidates.addAll(validRotationCandidates);

            StitchingCandidate rotationBestCandidate = selectBestCandidate(validRotationCandidates);
            if (isBetterCandidate(rotationBestCandidate, bestCandidate)) {
                bestCandidate = rotationBestCandidate;
                // 直接复用当前旋转已经计算出的 NFP，避免找到最佳候选后再次计算同一个 NFP。
                bestOuterNfp = copyPolygon(nfpResult.outerNFP);
                bestHoles = copyPolygonList(nfpResult.holes);
            }
        }

        if (bestCandidate == null) {
            return rejected(lastError, polygonA, polygonB, new ArrayList<>(), new ArrayList<>(),
                    candidates, validCandidates, null);
        }

        validCandidates.sort(PolygonStitcher::compareCandidates);

        // 返回搜索过程中已经保存的最佳旋转对应 NFP，避免额外的重复几何计算。
        return new StitchingResult(true, true, "Best stitching placement found", polygonA, polygonB,
                bestOuterNfp, bestHoles, candidates, validCandidates, bestCandidate);
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

    private static List<StitchingCandidate> buildCandidates(List<Point> polygonA,
                                                            double areaA,
                                                            double boxAArea,
                                                            List<Point> rotatedPolygonB,
                                                            double areaB,
                                                            int movingRotationDegrees,
                                                            NfpContour contour) {
        List<Point> contourPoints = contour.points;
        // 计算密集采样阈值：取两个多边形中较小包围盒对角线长度的比例。
        // 该阈值对外 NFP 的每条边生效，避免凹边只采样端点和中点。
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

            // 密集采样：外 NFP 的长边可能对应完整凹槽边界，额外采样能提高凹多边形候选质量。
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
        double baseFillRate = calculateFillRate(areaA, boxAArea);
        double combinedFillRate = calculateFillRate(areaA + areaB, boxABArea);
        double fillRateGain = combinedFillRate - baseFillRate;
        double minBoundaryDistance = minimumBoundaryDistance(polygonA, translatedPolygonB);
        double contactLength = maximumContactLength(
                polygonA, translatedPolygonB, CONTACT_DISTANCE_TOLERANCE);

        // 保留旧 score2 供结果文件和可视化查看，但新的 NFP 选择改用填充率提升量。
        double score2 = boxAArea + boxBArea - boxABArea;

        return new StitchingCandidate(sourceType, edgeStartIndex, edgeEndIndex, movingRotationDegrees,
                placementPoint, translation, boxAArea, boxBArea, boxABArea,
                combinedFillRate, fillRateGain, minBoundaryDistance, contactLength, score2,
                rotatedPolygonB, translatedPolygonB, combinedCoordinates);
    }

    /**
     * 过滤候选的几何质量和硬约束。
     *
     * 修改理由：单纯“不重叠且外接矩形变小”会接受远距离、只角点接触或彼此不连通的工件组合。
     * 这里把绝对填充率、边界距离、接触长度和并集连通性都纳入拼接的底层合法性判断。
     */
    private static List<StitchingCandidate> filterValidCandidates(List<StitchingCandidate> candidates,
                                                                    List<Point> polygonA) {
        List<StitchingCandidate> geometricCandidates = new ArrayList<>();
        for (StitchingCandidate candidate : candidates) {
            if (candidate.fillRateGain <= SCORE_EPS
                    || candidate.combinedFillRate < MIN_COMBINED_FILL_RATE - SCORE_EPS) {
                continue;
            }

            if (candidate.minBoundaryDistance > CONTACT_DISTANCE_TOLERANCE + SCORE_EPS
                    || candidate.contactLength < MIN_CONTACT_LENGTH - SCORE_EPS) {
                continue;
            }

            // NFP 边界候选仍需经过精确重叠检测，避免数值误差产生正面积穿透。
            if (mayHavePositiveBBoxOverlap(polygonA, candidate.translatedPolygonB)
                    && intersectionArea(polygonA, candidate.translatedPolygonB) > SCORE_EPS) {
                continue;
            }

            // 接触长度和距离是第一层快速筛选；先收集候选，后续按评分排序后再做精确连通校验。
            geometricCandidates.add(candidate);
        }

        geometricCandidates.sort(PolygonStitcher::compareCandidates);

        List<StitchingCandidate> validCandidates = new ArrayList<>();
        for (StitchingCandidate candidate : geometricCandidates) {
            // 修改理由：仅靠接触长度仍可能接受小间隙，导致不连通候选抢占 Top-K；
            // 对排序靠前的候选执行精确并集，确保送入 beam search 的位置确实属于同一连通块。
            if (!hasSingleOuterUnionComponent(polygonA, candidate.translatedPolygonB)) {
                continue;
            }
            validCandidates.add(candidate);
            // 每个旋转保留少量已确认连通的候选即可覆盖不同放置位置，避免对全部采样点执行 Area 并集。
            if (validCandidates.size() >= MAX_CONNECTED_CANDIDATES_PER_ROTATION) {
                break;
            }
        }
        return validCandidates;
    }

    /** 精确判断两个不重叠工件的并集是否只有一个外部连通分量。 */
    private static boolean hasSingleOuterUnionComponent(List<Point> first, List<Point> second) {
        List<List<Point>> polygons = new ArrayList<>(2);
        polygons.add(first);
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
     * 填充率提升量是主评分，拼接后填充率用于稳定排序，旧 score2 只作为最后的兼容性平局条件。
     */
    private static boolean isBetterCandidate(StitchingCandidate candidate,
                                              StitchingCandidate currentBest) {
        if (candidate == null) {
            return false;
        }
        if (currentBest == null) {
            return true;
        }
        if (candidate.combinedFillRate > currentBest.combinedFillRate + SCORE_EPS) {
            return true;
        }
        if (Math.abs(candidate.combinedFillRate - currentBest.combinedFillRate) <= SCORE_EPS
                && candidate.contactLength > currentBest.contactLength + SCORE_EPS) {
            return true;
        }
        if (Math.abs(candidate.combinedFillRate - currentBest.combinedFillRate) <= SCORE_EPS
                && Math.abs(candidate.contactLength - currentBest.contactLength) <= SCORE_EPS
                && candidate.fillRateGain > currentBest.fillRateGain + SCORE_EPS) {
            return true;
        }
        return Math.abs(candidate.combinedFillRate - currentBest.combinedFillRate) <= SCORE_EPS
                && Math.abs(candidate.contactLength - currentBest.contactLength) <= SCORE_EPS
                && Math.abs(candidate.fillRateGain - currentBest.fillRateGain) <= SCORE_EPS
                && candidate.score2 > currentBest.score2 + SCORE_EPS;
    }

    /** 与 isBetterCandidate 相同的排序方向，供有效候选和 beam search 稳定排序。 */
    private static int compareCandidates(StitchingCandidate left, StitchingCandidate right) {
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

    private static String candidateSignature(StitchingCandidate candidate) {
        return candidate.movingRotationDegrees
                + ":" + roundedCoordinate(candidate.translation.x)
                + ":" + roundedCoordinate(candidate.translation.y);
    }

    private static long roundedCoordinate(double coordinate) {
        return Math.round(coordinate * 1_000.0);
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
     * 只负责遍历顶点、中点和密集采样点。
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
