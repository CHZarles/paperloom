package com.yizhaoqi.smartpai.paper.parser;

import java.util.Map;

public record ParsedPaperFigure(
        String figureId,
        String elementId,
        Integer pageNumber,
        Integer readingOrder,
        String caption,
        String sectionTitle,
        String figureText,
        BoundingBox boundingBox,
        String detectionSource,
        String confidence,
        Map<String, Object> rawAttributes
) {
}
