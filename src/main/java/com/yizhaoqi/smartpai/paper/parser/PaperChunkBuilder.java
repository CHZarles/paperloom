package com.yizhaoqi.smartpai.paper.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.EvidenceQuality;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaperChunkBuilder {

    private static final int ANCHOR_TEXT_MAX_LENGTH = 120;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<PaperChunkCandidate> buildChunks(ParsedPaper paper, int chunkSize) {
        List<PaperChunkCandidate> chunks = new ArrayList<>();
        if (paper == null || paper.elements() == null || paper.elements().isEmpty()) {
            return chunks;
        }

        String currentSectionTitle = null;
        Integer currentSectionLevel = null;
        int nextChunkId = 1;
        Map<String, ParsedPaperTable> tablesByElementId = paper.tables() == null
                ? Map.of()
                : paper.tables().stream()
                .filter(table -> table != null && table.elementId() != null)
                .collect(Collectors.toMap(ParsedPaperTable::elementId, Function.identity(), (first, replacement) -> first));
        Map<String, ParsedPaperFigure> figuresByElementId = paper.figures() == null
                ? Map.of()
                : paper.figures().stream()
                .filter(figure -> figure != null && figure.elementId() != null)
                .collect(Collectors.toMap(ParsedPaperFigure::elementId, Function.identity(), (first, replacement) -> first));
        Map<String, ParsedPaperFormula> formulasByElementId = paper.formulas() == null
                ? Map.of()
                : paper.formulas().stream()
                .filter(formula -> formula != null && formula.elementId() != null)
                .collect(Collectors.toMap(ParsedPaperFormula::elementId, Function.identity(), (first, replacement) -> first));

        for (ParsedPaperElement element : paper.elements()) {
            if (element == null) {
                continue;
            }

            if (element.elementType() == ParsedPaperElementType.HEADING) {
                String headingText = normalizeText(element.text());
                if (!headingText.isBlank()) {
                    currentSectionTitle = headingText;
                    currentSectionLevel = element.sectionLevel();
                }
                continue;
            }

            if (!isChunkable(element)) {
                continue;
            }

            ParsedPaperTable parsedTable = tablesByElementId.get(element.elementId());
            ParsedPaperFigure parsedFigure = figuresByElementId.get(element.elementId());
            ParsedPaperFormula parsedFormula = formulasByElementId.get(element.elementId());
            String text = normalizeText(resolveChunkText(element, parsedTable, parsedFigure, parsedFormula));
            if (!EvidenceQuality.isUsable(text)) {
                continue;
            }
            String sourceKind = resolveSourceKind(element);
            String tableId = parsedTable != null ? parsedTable.tableId() : null;
            String figureId = parsedFigure != null ? parsedFigure.figureId() : null;
            String formulaId = parsedFormula != null ? parsedFormula.formulaId() : null;
            String evidenceRole = resolveEvidenceRole(element, text);

            for (String chunkText : splitIntoChunks(text, chunkSize)) {
                if (!EvidenceQuality.isUsable(chunkText)) {
                    continue;
                }
                chunks.add(new PaperChunkCandidate(
                        nextChunkId++,
                        chunkText,
                        element.pageNumber(),
                        buildAnchorText(chunkText),
                        currentSectionTitle,
                        currentSectionLevel,
                        element.elementType().name(),
                        toJson(element.boundingBox()),
                        paper.parserName(),
                        paper.parserVersion(),
                        toRawProvenanceJson(element),
                        sourceKind,
                        tableId,
                        figureId,
                        formulaId,
                        evidenceRole
                ));
            }
        }

        return chunks;
    }

    private boolean isChunkable(ParsedPaperElement element) {
        return switch (element.elementType()) {
            case PARAGRAPH, TEXT_BLOCK, CAPTION, TABLE, FIGURE, CHART, FORMULA, LIST, LIST_ITEM -> true;
            case TITLE, HEADING, IMAGE, HEADER, FOOTER, UNKNOWN -> false;
        };
    }

    private String resolveChunkText(ParsedPaperElement element,
                                    ParsedPaperTable parsedTable,
                                    ParsedPaperFigure parsedFigure,
                                    ParsedPaperFormula parsedFormula) {
        if (parsedTable != null) {
            return parsedTable.tableText();
        }
        if (parsedFigure != null) {
            return firstNonBlank(parsedFigure.figureText(), parsedFigure.caption(), element.text());
        }
        if (parsedFormula != null) {
            return firstNonBlank(parsedFormula.latex(), parsedFormula.contextText(), element.text());
        }
        return element.text();
    }

    private String resolveSourceKind(ParsedPaperElement element) {
        return switch (element.elementType()) {
            case TABLE -> "TABLE";
            case FIGURE -> "FIGURE";
            case CHART -> "CHART";
            case FORMULA -> "FORMULA";
            default -> "TEXT";
        };
    }

    private String resolveEvidenceRole(ParsedPaperElement element, String text) {
        String lower = text == null ? "" : text.toLowerCase();
        if (element.elementType() == ParsedPaperElementType.FIGURE
                || element.elementType() == ParsedPaperElementType.CHART) {
            return "FIGURE_CAPTION";
        }
        if (element.elementType() == ParsedPaperElementType.FORMULA) {
            return "FORMULA";
        }
        if (element.elementType() == ParsedPaperElementType.TABLE
                || lower.contains("accuracy")
                || lower.contains("experiment")
                || lower.contains("evaluation")
                || lower.contains("benchmark")
                || lower.contains("table ")) {
            return "EXPERIMENT_RESULT";
        }
        return "NORMAL_TEXT";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<String> splitIntoChunks(String text, int chunkSize) {
        int effectiveChunkSize = Math.max(1, chunkSize);
        if (text.length() <= effectiveChunkSize) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？!?；;\\.])\\s+");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String normalizedSentence = normalizeText(sentence);
            if (normalizedSentence.isBlank()) {
                continue;
            }
            if (normalizedSentence.length() > effectiveChunkSize) {
                flushCurrentChunk(chunks, current);
                splitLongText(chunks, normalizedSentence, effectiveChunkSize);
                continue;
            }
            int separatorLength = current.length() == 0 ? 0 : 1;
            if (current.length() + separatorLength + normalizedSentence.length() > effectiveChunkSize) {
                flushCurrentChunk(chunks, current);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(normalizedSentence);
        }
        flushCurrentChunk(chunks, current);
        return chunks;
    }

    private void splitLongText(List<String> chunks, String text, int chunkSize) {
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(index + chunkSize, text.length());
            chunks.add(text.substring(index, end).trim());
            index = end;
        }
    }

    private void flushCurrentChunk(List<String> chunks, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        chunks.add(current.toString().trim());
        current.setLength(0);
    }

    private String buildAnchorText(String text) {
        String normalized = normalizeText(text);
        if (normalized.length() <= ANCHOR_TEXT_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, ANCHOR_TEXT_MAX_LENGTH) + "...";
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String toRawProvenanceJson(ParsedPaperElement element) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("elementId", element.elementId());
        raw.put("pageNumber", element.pageNumber());
        raw.put("readingOrder", element.readingOrder());
        raw.put("elementType", element.elementType().name());
        return toJson(raw);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new PaperParsingException("Failed to serialize paper chunk provenance", e);
        }
    }
}
