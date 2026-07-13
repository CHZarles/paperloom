package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingElement;

public record PaperReadingElementSearchResult(
        PaperReadingElement element,
        String routedLocationRef,
        PaperLocationType routedLocationType,
        String routingSource
) {
}
