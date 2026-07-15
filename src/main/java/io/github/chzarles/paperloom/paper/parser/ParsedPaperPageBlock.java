package io.github.chzarles.paperloom.paper.parser;

import java.util.Map;

public record ParsedPaperPageBlock(
        String blockId,
        Integer readingOrder,
        String blockType,
        String text,
        BoundingBox boundingBox,
        Map<String, Object> rawAttributes
) {
}
