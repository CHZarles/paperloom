package com.yizhaoqi.smartpai.paper.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

            String text = normalizeText(element.text());
            if (text.isBlank()) {
                continue;
            }

            for (String chunkText : splitIntoChunks(text, chunkSize)) {
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
                        toRawProvenanceJson(element)
                ));
            }
        }

        return chunks;
    }

    private boolean isChunkable(ParsedPaperElement element) {
        return switch (element.elementType()) {
            case PARAGRAPH, TEXT_BLOCK, CAPTION, TABLE, LIST, LIST_ITEM -> true;
            case TITLE, HEADING, IMAGE, HEADER, FOOTER, UNKNOWN -> false;
        };
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
        raw.put("rawAttributes", element.rawAttributes());
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
