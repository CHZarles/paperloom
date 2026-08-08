package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperPassage;

import java.util.List;

public record PassageBuildResult(List<PaperPassage> passages, List<PaperLocation> locations) {
    public PassageBuildResult {
        passages = List.copyOf(passages);
        locations = List.copyOf(locations);
    }
}
