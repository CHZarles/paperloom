package io.github.chzarles.paperloom.paper.parser;

import java.util.Map;

public record ParsedPaperTable(
        String tableId,
        String elementId,
        Integer pageNumber,
        Integer readingOrder,
        String caption,
        String sectionTitle,
        Integer rowCount,
        Integer columnCount,
        String tableText,
        String tableMarkdown,
        BoundingBox boundingBox,
        Map<String, Object> rawAttributes
) {
}
