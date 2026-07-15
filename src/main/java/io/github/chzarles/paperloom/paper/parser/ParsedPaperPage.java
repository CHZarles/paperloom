package io.github.chzarles.paperloom.paper.parser;

import java.util.List;
import java.util.Map;

public record ParsedPaperPage(
        Integer pageNumber,
        List<ParsedPaperPageBlock> blocks,
        Map<String, Object> rawAttributes
) {
}
