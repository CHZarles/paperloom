package com.yizhaoqi.smartpai.paper.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OpenDataLoaderJsonMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedPaper map(String json, String parserName, String parserVersion) {
        try {
            JsonNode root = objectMapper.readTree(json);
            ParsedPaperMetadata metadata = new ParsedPaperMetadata(
                    text(root, "file name"),
                    text(root, "title"),
                    text(root, "author"),
                    integer(root, "number of pages"),
                    text(root, "creation date"),
                    text(root, "modification date")
            );

            List<ParsedPaperElement> elements = new ArrayList<>();
            JsonNode kids = root.path("kids");
            if (kids.isArray()) {
                int order = 0;
                for (JsonNode kid : kids) {
                    order++;
                    ParsedPaperElement element = toElement(kid, order);
                    if (element != null) {
                        elements.add(element);
                    }
                }
            }

            Map<String, Object> rawMetadata = new LinkedHashMap<>();
            rawMetadata.put("file name", metadata.originalFilename());
            rawMetadata.put("number of pages", metadata.pageCount());
            rawMetadata.put("author", metadata.authors());
            rawMetadata.put("title", metadata.title());
            rawMetadata.put("creation date", metadata.creationDate());
            rawMetadata.put("modification date", metadata.modificationDate());

            return new ParsedPaper(parserName, parserVersion, metadata, elements, rawMetadata);
        } catch (JsonProcessingException e) {
            throw new PaperParsingException("Failed to parse OpenDataLoader JSON", e);
        }
    }

    public ParsedPaper withOriginalFilename(ParsedPaper paper, String originalFilename) {
        if (paper == null || originalFilename == null || originalFilename.isBlank()) {
            return paper;
        }
        ParsedPaperMetadata metadata = paper.metadata();
        ParsedPaperMetadata updatedMetadata = new ParsedPaperMetadata(
                originalFilename,
                metadata.title(),
                metadata.authors(),
                metadata.pageCount(),
                metadata.creationDate(),
                metadata.modificationDate()
        );
        Map<String, Object> rawMetadata = new LinkedHashMap<>(paper.rawMetadata());
        rawMetadata.put("originalFilename", originalFilename);
        return new ParsedPaper(paper.parserName(), paper.parserVersion(), updatedMetadata, paper.elements(), rawMetadata);
    }

    private ParsedPaperElement toElement(JsonNode node, int readingOrder) {
        if (node == null || !node.isObject()) {
            return null;
        }

        ParsedPaperElementType elementType = mapElementType(text(node, "type"), text(node, "level"));
        return new ParsedPaperElement(
                textOrIntegerId(node, "id"),
                integer(node, "page number"),
                readingOrder,
                elementType,
                extractText(node),
                null,
                integer(node, "heading level"),
                boundingBox(node.path("bounding box"), integer(node, "page number")),
                toMap(node)
        );
    }

    private ParsedPaperElementType mapElementType(String type, String level) {
        String normalizedType = normalizeKey(type);
        String normalizedLevel = normalizeKey(level);
        if ("doctitle".equals(normalizedLevel)) {
            return ParsedPaperElementType.TITLE;
        }
        return switch (normalizedType) {
            case "heading" -> ParsedPaperElementType.HEADING;
            case "paragraph" -> ParsedPaperElementType.PARAGRAPH;
            case "textblock", "text block" -> ParsedPaperElementType.TEXT_BLOCK;
            case "caption" -> ParsedPaperElementType.CAPTION;
            case "table" -> ParsedPaperElementType.TABLE;
            case "list" -> ParsedPaperElementType.LIST;
            case "listitem", "list item" -> ParsedPaperElementType.LIST_ITEM;
            case "image" -> ParsedPaperElementType.IMAGE;
            case "header", "footer", "headerfooter", "header/footer" -> mapHeaderFooter(type);
            default -> ParsedPaperElementType.UNKNOWN;
        };
    }

    private ParsedPaperElementType mapHeaderFooter(String type) {
        String normalized = normalizeKey(type);
        if (normalized.contains("header")) {
            return ParsedPaperElementType.HEADER;
        }
        if (normalized.contains("footer")) {
            return ParsedPaperElementType.FOOTER;
        }
        return ParsedPaperElementType.UNKNOWN;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace("_", " ");
    }

    private String extractText(JsonNode node) {
        String content = text(node, "content");
        if (content != null) {
            return content;
        }
        if ("table".equalsIgnoreCase(text(node, "type"))) {
            return extractTableText(node);
        }
        return null;
    }

    private String extractTableText(JsonNode table) {
        JsonNode rows = table.path("rows");
        if (!rows.isArray()) {
            return null;
        }
        List<String> rowTexts = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode cells = row.path("cells");
            if (!cells.isArray()) {
                continue;
            }
            List<String> cellTexts = new ArrayList<>();
            for (JsonNode cell : cells) {
                String cellText = text(cell, "content");
                if (cellText != null && !cellText.isBlank()) {
                    cellTexts.add(cellText.replaceAll("\\s+", " ").trim());
                }
            }
            if (!cellTexts.isEmpty()) {
                rowTexts.add(String.join("\t", cellTexts));
            }
        }
        return rowTexts.isEmpty() ? null : String.join("\n", rowTexts);
    }

    private BoundingBox boundingBox(JsonNode bboxNode, Integer pageNumber) {
        if (bboxNode == null || !bboxNode.isArray() || bboxNode.size() != 4) {
            return null;
        }
        return new BoundingBox(
                pageNumber,
                bboxNode.get(0).asDouble(),
                bboxNode.get(1).asDouble(),
                bboxNode.get(2).asDouble(),
                bboxNode.get(3).asDouble(),
                "pdf_points",
                "bottom_left"
        );
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private Integer integer(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull() || !value.canConvertToInt()) {
            return null;
        }
        return value.asInt();
    }

    private String textOrIntegerId(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Collections.emptyMap();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }
}
