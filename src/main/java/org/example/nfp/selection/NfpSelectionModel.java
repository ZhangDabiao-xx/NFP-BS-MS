package org.example.nfp.selection;

import java.util.Map;

/**
 * Local, deterministic scorer used by NFP search.  Implementations never receive geometry
 * mutators, so a profile can rank valid candidates but cannot relax a geometric constraint.
 */
public interface NfpSelectionModel {
    double score(NfpSelectionStage stage, Map<String, Double> normalizedFeatures);

    String id();

    default boolean isProfileDriven() {
        return false;
    }
}
