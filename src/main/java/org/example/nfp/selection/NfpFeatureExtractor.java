package org.example.nfp.selection;

import org.example.nfp.Block;
import org.example.nfp.Geometry;
import org.example.nfp.PolygonItem;
import org.example.nfp.PolygonStitcher;

import java.util.HashMap;
import java.util.Map;

/** Fixed Java definitions of all profile features; the Agent can choose weights, never formulas. */
public final class NfpFeatureExtractor {
    private static final double PLATE_AREA = 2440.0 * 1220.0;
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

    public static Map<String, Double> root(Block block, int remainingItemCount) {
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
        put(values, "boxAreaToPlateArea", clamp(block.boxArea / PLATE_AREA));
        double longSide = Math.max(0, Math.sqrt(Math.max(block.boxArea, 0))); put(values, "longSideToPlateLongSide", clamp(longSide / 2440.0)); put(values, "shortSideToPlateShortSide", clamp(longSide / 1220.0));
        put(values, "sourceBoxSavingRatio", ratio(block.score2, block.sourceBoxAreaSum)); put(values, "remainingItemRatio", clamp(remainingItemCount / 1000.0));
        return values;
    }

    private static void put(Map<String, Double> values, String name, double value) { values.put(name, clamp(value)); }
    private static double ratio(double numerator, double denominator) { return denominator <= Geometry.EPS ? 0 : numerator / denominator; }
    private static double clamp(double value) { return !Double.isFinite(value) ? 0 : Math.max(0, Math.min(1, value)); }
}
