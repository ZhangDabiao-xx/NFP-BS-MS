package org.example.nfp.selection;

import java.util.Map;

/** Executes a validated profile entirely locally during the search. */
public final class ProfileDrivenNfpSelectionModel implements NfpSelectionModel {
    private final NfpScoringProfile profile;
    public ProfileDrivenNfpSelectionModel(NfpScoringProfile profile) { this.profile = profile; }
    @Override public double score(NfpSelectionStage stage, Map<String, Double> features) { return profile.score(stage, features); }
    @Override public String id() { return profile.id(); }
    @Override public boolean isProfileDriven() { return true; }
    public NfpScoringProfile profile() { return profile; }
}
