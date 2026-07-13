package io.github.chzarles.paperloom.paper.parser;

import java.util.Map;

public record ParsedPaperFormula(
        String formulaId,
        String elementId,
        Integer pageNumber,
        Integer readingOrder,
        String latex,
        String contextText,
        String sectionTitle,
        BoundingBox boundingBox,
        Map<String, Object> rawAttributes
) {
}
