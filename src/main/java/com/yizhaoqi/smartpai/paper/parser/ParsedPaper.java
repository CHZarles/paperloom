package com.yizhaoqi.smartpai.paper.parser;

import java.util.List;
import java.util.Map;

public record ParsedPaper(
        String parserName,
        String parserVersion,
        ParsedPaperMetadata metadata,
        List<ParsedPaperElement> elements,
        Map<String, Object> rawMetadata,
        String rawParserJson,
        List<ParsedPaperTable> tables,
        List<ParsedPaperFigure> figures,
        List<ParsedPaperFormula> formulas,
        List<ParsedPaperArtifactPayload> artifacts
) {
    public ParsedPaper(String parserName,
                       String parserVersion,
                       ParsedPaperMetadata metadata,
                       List<ParsedPaperElement> elements,
                       Map<String, Object> rawMetadata) {
        this(parserName, parserVersion, metadata, elements, rawMetadata, null, List.of(), List.of(), List.of(), List.of());
    }

    public ParsedPaper(String parserName,
                       String parserVersion,
                       ParsedPaperMetadata metadata,
                       List<ParsedPaperElement> elements,
                       Map<String, Object> rawMetadata,
                       String rawParserJson,
                       List<ParsedPaperTable> tables) {
        this(parserName, parserVersion, metadata, elements, rawMetadata, rawParserJson, tables, List.of(), List.of(), List.of());
    }

    public ParsedPaper(String parserName,
                       String parserVersion,
                       ParsedPaperMetadata metadata,
                       List<ParsedPaperElement> elements,
                       Map<String, Object> rawMetadata,
                       String rawParserJson,
                       List<ParsedPaperTable> tables,
                       List<ParsedPaperFigure> figures,
                       List<ParsedPaperFormula> formulas) {
        this(parserName, parserVersion, metadata, elements, rawMetadata, rawParserJson, tables, figures, formulas, List.of());
    }
}
