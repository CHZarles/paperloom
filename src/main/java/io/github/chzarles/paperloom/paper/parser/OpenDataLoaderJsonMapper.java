package io.github.chzarles.paperloom.paper.parser;

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
            List<ParsedPaperTable> tables = new ArrayList<>();
            JsonNode kids = root.path("kids");
            if (kids.isArray()) {
                int order = 0;
                String currentSectionTitle = null;
                for (JsonNode kid : kids) {
                    order++;
                    ParsedPaperElement element = toElement(kid, order);
                    if (element != null) {
                        elements.add(element);
                        if (element.elementType() == ParsedPaperElementType.HEADING) {
                            String headingText = normalizeWhitespace(element.text());
                            if (headingText != null && !headingText.isBlank()) {
                                currentSectionTitle = headingText;
                            }
                        } else if (element.elementType() == ParsedPaperElementType.TABLE) {
                            ParsedPaperTable table = toTable(kid, element, currentSectionTitle);
                            if (table != null) {
                                tables.add(table);
                            }
                        }
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

            return new ParsedPaper(parserName, parserVersion, metadata, elements, rawMetadata, json, tables);
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
        return new ParsedPaper(
                paper.parserName(),
                paper.parserVersion(),
                updatedMetadata,
                paper.elements(),
                rawMetadata,
                paper.rawParserJson(),
                paper.tables()
        );
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
        List<List<String>> rows = extractRows(table);
        if (rows.isEmpty()) {
            return null;
        }
        List<String> rowTexts = new ArrayList<>();
        for (List<String> row : rows) {
            rowTexts.add(String.join("\t", row));
        }
        return rowTexts.isEmpty() ? null : String.join("\n", rowTexts);
    }

    private ParsedPaperTable toTable(JsonNode tableNode, ParsedPaperElement element, String sectionTitle) {
        List<List<String>> rows = extractRows(tableNode);
        if (rows.isEmpty()) {
            return null;
        }

        Integer rowCount = integer(tableNode, "number of rows");
        if (rowCount == null) {
            rowCount = rows.size();
        }
        Integer columnCount = integer(tableNode, "number of columns");
        if (columnCount == null) {
            columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
        }

        String tableId = "table-" + (element.elementId() != null && !element.elementId().isBlank()
                ? element.elementId()
                : element.readingOrder());
        String caption = firstNonBlank(
                text(tableNode, "caption"),
                text(tableNode, "table caption"),
                text(tableNode, "title")
        );
        String tableText = buildTableText(caption, sectionTitle, rows);
        String tableMarkdown = buildTableMarkdown(rows);
        return new ParsedPaperTable(
                tableId,
                element.elementId(),
                element.pageNumber(),
                element.readingOrder(),
                caption,
                sectionTitle,
                rowCount,
                columnCount,
                tableText,
                tableMarkdown,
                element.boundingBox(),
                toMap(tableNode)
        );
    }

    private List<List<String>> extractRows(JsonNode table) {
        JsonNode rowsNode = table.path("rows");
        if (!rowsNode.isArray()) {
            return List.of();
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : rowsNode) {
            JsonNode cells = row.path("cells");
            if (!cells.isArray()) {
                continue;
            }
            List<String> cellTexts = new ArrayList<>();
            for (JsonNode cell : cells) {
                String cellText = normalizeWhitespace(text(cell, "content"));
                if (cellText != null && !cellText.isBlank()) {
                    cellTexts.add(cellText);
                }
            }
            if (!cellTexts.isEmpty()) {
                rows.add(cellTexts);
            }
        }
        return rows;
    }

    private String buildTableText(String caption, String sectionTitle, List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        if (caption != null && !caption.isBlank()) {
            lines.add("Caption: " + caption);
        }
        if (sectionTitle != null && !sectionTitle.isBlank()) {
            lines.add("Section: " + sectionTitle);
        }
        for (List<String> row : rows) {
            if (row.size() == 2) {
                lines.add(row.get(0) + ": " + row.get(1));
            } else {
                lines.add(String.join(" | ", row));
            }
        }
        return String.join("\n", lines);
    }

    private String buildTableMarkdown(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        List<String> header = rows.get(0);
        lines.add(markdownRow(header));
        lines.add(markdownSeparator(header.size()));
        for (int i = 1; i < rows.size(); i++) {
            lines.add(markdownRow(rows.get(i)));
        }
        return String.join("\n", lines);
    }

    private String markdownRow(List<String> cells) {
        return cells.stream()
                .map(this::escapeMarkdownCell)
                .collect(Collectors.joining(" | ", "| ", " |"));
    }

    private String markdownSeparator(int columns) {
        return java.util.stream.IntStream.range(0, Math.max(1, columns))
                .mapToObj(i -> "---")
                .collect(Collectors.joining(" | ", "| ", " |"));
    }

    private String escapeMarkdownCell(String cell) {
        return cell == null ? "" : cell.replace("|", "\\|");
    }

    private String normalizeWhitespace(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
