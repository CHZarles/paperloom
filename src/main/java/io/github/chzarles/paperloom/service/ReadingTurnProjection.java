package io.github.chzarles.paperloom.service;

public record ReadingTurnProjection(
        ReadingTurnArtifacts artifacts,
        ReadingStatePatch statePatch
) {
    public ReadingTurnProjection {
        artifacts = artifacts == null ? ReadingTurnArtifacts.empty("") : artifacts;
        statePatch = statePatch == null ? ReadingStatePatch.empty() : statePatch;
    }
}
