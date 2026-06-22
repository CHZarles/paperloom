package com.yizhaoqi.smartpai.paper.parser;

import java.util.List;
import java.util.Map;

public record ParsedPaper(
        String parserName,
        String parserVersion,
        ParsedPaperMetadata metadata,
        List<ParsedPaperElement> elements,
        Map<String, Object> rawMetadata
) {
}
