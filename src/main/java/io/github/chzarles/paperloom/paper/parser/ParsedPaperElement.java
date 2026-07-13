package io.github.chzarles.paperloom.paper.parser;

import java.util.Map;

public record ParsedPaperElement(
        String elementId,
        Integer pageNumber,
        Integer readingOrder,
        ParsedPaperElementType elementType,
        String text,
        String sectionTitle,
        Integer sectionLevel,
        BoundingBox boundingBox,
        Map<String, Object> rawAttributes
) {
}
