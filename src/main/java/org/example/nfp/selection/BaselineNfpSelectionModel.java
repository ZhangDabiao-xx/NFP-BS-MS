package org.example.nfp.selection;

import java.util.Map;

/** Marker for the frozen legacy path; legacy comparators retain their exact historical behavior. */
public final class BaselineNfpSelectionModel implements NfpSelectionModel {
    public static final BaselineNfpSelectionModel INSTANCE = new BaselineNfpSelectionModel();
    private BaselineNfpSelectionModel() { }
    @Override public double score(NfpSelectionStage stage, Map<String, Double> features) { return 0.0; }
    @Override public String id() { return "legacy-baseline"; }
}
