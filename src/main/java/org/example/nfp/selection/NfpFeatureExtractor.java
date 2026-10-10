package org.example.nfp.selection;

import org.example.nfp.Block;
import org.example.nfp.BBox;
import org.example.nfp.NfpSearchSession;
import org.example.nfp.Geometry;
import org.example.nfp.PolygonItem;
import org.example.nfp.PolygonStitcher;

import java.util.HashMap;
import java.util.Map;

/** Fixed Java definitions of all profile features; the Agent can choose weights, never formulas. */
public final class NfpFeatureExtractor {
    private NfpFeatureExtractor() { }

    public static Map<String, Double> placement(PolygonStitcher.StitchingCandidate candidate,
                                                PolygonItem movingItem, int remainingItemCount) {
        Map<String, Double> values = new HashMap<>();
        put(values, "normalizedSBox", candidate.normalizedSBox); put(values, "sArea", candidate.sArea);
        put(values, "combinedFillRate", candidate.combinedFillRate); put(values, "cavityInsertion", candidate.cavityInsertion ? 1 : 0);
        put(values, "boxExpansionRatio", ratio(candidate.boxExpansionArea, candidate.boxAArea));
        put(values, "contactRatio", ratio(candidate.contactLength, Math.sqrt(Math.max(candidate.boxBArea, Geometry.EPS))));
        put(values, "minBoundaryDistanceRatio", 1.0 - clamp(candidate.minBoundaryDistance / PolygonStitcher.CONTACT_DISTANCE_TOLERANCE));
        put(values, "movingItemSmall", movingItem != null && movingItem.smallItem ? 1 : 0);
        put(values, "nextRotationCountRatio", movingItem == null ? 0 : clamp(movingItem.rotate.size() / 4.0));
        put(values, "remainingItemRatio", clamp(remainingItemCount / 1000.0));
        return values;
    }

    /**
     * Extracts root features using the actual remaining-item count of this branch.  The previous
     * fixed divisor of 1,000 made this feature nearly constant for every state in one case.
     */
    public static Map<String, Double> root(Block block, int remainingItemCount, int totalItemCount) {
        NfpSearchSession session = NfpSearchSession.current();
        return root(block, remainingItemCount, totalItemCount,
                session == null ? 2440 : session.plateLength, session == null ? 1220 : session.plateWidth);
    }

    /** 使用真实包围盒与案例板材尺寸；等面积细长块不能再与方形块得到相同特征。 */
    public static Map<String, Double> root(Block block, int remainingItemCount, int totalItemCount,
                                         double plateLength, double plateWidth) {
        Map<String, Double> values = new HashMap<>();
        Block.ItemPlacement last = block.placements.get(block.placements.size() - 1);
        put(values, "normalizedSBox", last.candidateNormalizedSBox); put(values, "sArea", last.candidateSArea);
        put(values, "combinedFillRate", last.candidateCombinedFillRate); put(values, "cavityInsertion", last.candidateCavityInsertion ? 1 : 0);
        put(values, "boxExpansionRatio", ratio(Math.max(0, block.boxArea - block.sourceBoxAreaSum), block.sourceBoxAreaSum));
        put(values, "contactRatio", ratio(last.candidateContactLength, Math.sqrt(Math.max(last.item.boxArea, Geometry.EPS))));
        put(values, "minBoundaryDistanceRatio", 1.0 - clamp(last.candidateMinBoundaryDistance / PolygonStitcher.CONTACT_DISTANCE_TOLERANCE));
        put(values, "movingItemSmall", last.item.smallItem ? 1 : 0); put(values, "nextRotationCountRatio", clamp(block.rotate.size() / 4.0));
        put(values, "fillRate", block.fillRate); put(values, "fillRateGainFromRoot", Math.max(0, block.fillRate - block.placements.get(0).item.fillRate));
        put(values, "memberCountRatio", clamp(block.memberCount() / (double) Block.MAX_MEMBER_COUNT));
        int cavity = 0, small = 0, rectangles = 0;
        for (int i = 1; i < block.placements.size(); i++) { Block.ItemPlacement placement = block.placements.get(i); if (placement.candidateCavityInsertion) cavity++; if (placement.item.smallItem) small++; if (placement.item.smallItem && placement.item.rectangular) rectangles++; }
        put(values, "cavityInsertionRatio", ratio(cavity, Math.max(1, block.memberCount() - 1))); put(values, "smallItemUsageRatio", ratio(small, Math.max(1, block.memberCount() - 1))); put(values, "smallRectangleUsageRatio", ratio(rectangles, Math.max(1, block.memberCount() - 1)));
        put(values, "boxAreaToPlateArea", ratio(block.boxArea, plateLength * plateWidth));
        BBox box = Geometry.polygonBBox(block.combinedCoordinates);
        double longSide = Math.max(box.maxX - box.minX, box.maxY - box.minY);
        double shortSide = Math.min(box.maxX - box.minX, box.maxY - box.minY);
        put(values, "longSideToPlateLongSide", ratio(longSide, Math.max(plateLength, plateWidth)));
        put(values, "shortSideToPlateShortSide", ratio(shortSide, Math.min(plateLength, plateWidth)));
        // 允许角度数量相同不代表矩形阶段都能交换长宽，例如 [0,180] 与 [0,90]。
        put(values, "canSwapSides", block.rotate.contains(90) || block.rotate.contains(270) ? 1 : 0);
        put(values, "aspectRatio", ratio(shortSide, longSide));
        put(values, "sourceBoxSavingRatio", ratio(block.score2, block.sourceBoxAreaSum));
        put(values, "remainingItemRatio", ratio(remainingItemCount, Math.max(1, totalItemCount)));
        return values;
    }

    private static void put(Map<String, Double> values, String name, double value) { values.put(name, clamp(value)); }
    private static double ratio(double numerator, double denominator) { return denominator <= Geometry.EPS ? 0 : numerator / denominator; }
    private static double clamp(double value) { return !Double.isFinite(value) ? 0 : Math.max(0, Math.min(1, value)); }
}
