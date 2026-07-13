package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperSection;

import java.util.List;

public record PaperReadingModelBuildResult(
        List<PaperPage> pages,
        List<PaperSection> sections,
        List<PaperLocation> locations,
        List<PaperReadingElement> readingElements,
        String diagnosticsJson
) {
}
