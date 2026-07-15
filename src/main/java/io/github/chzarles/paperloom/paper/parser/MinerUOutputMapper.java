package io.github.chzarles.paperloom.paper.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

@Component
public class MinerUOutputMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedPaper map(String contentListJson,
                           String middleJson,
                           String markdown,
                           String parserName,
                           String parserVersion,
                           String originalFilename) {
        try {
            JsonNode contentRoot = parseContentList(contentListJson);
            JsonNode items = contentRoot.isArray() ? contentRoot : contentRoot.path("content_list");
            if (!items.isArray()) {
                items = contentRoot.path("contentList");
            }
            if (!items.isArray()) {
                items = objectMapper.createArrayNode();
            }

            List<ParsedPaperElement> elements = new ArrayList<>();
            List<ParsedPaperTable> tables = new ArrayList<>();
            List<ParsedPaperFigure> figures = new ArrayList<>();
            List<ParsedPaperFormula> formulas = new ArrayList<>();
            List<ParsedPaperPage> pages = toPhysicalPages(middleJson);
            String currentSectionTitle = null;
            Integer currentSectionLevel = null;
            int order = 0;

            for (JsonNode item : items) {
                order++;
                ParsedPaperElement element = toElement(item, order, currentSectionTitle, currentSectionLevel);
                if (element == null) {
                    continue;
                }
                elements.add(element);
                if (element.elementType() == ParsedPaperElementType.HEADING) {
                    currentSectionTitle = normalizeWhitespace(element.text());
                    currentSectionLevel = element.sectionLevel();
                } else if (element.elementType() == ParsedPaperElementType.TABLE) {
                    tables.add(toTable(item, element, currentSectionTitle));
                } else if (element.elementType() == ParsedPaperElementType.FIGURE
                        || element.elementType() == ParsedPaperElementType.CHART) {
                    figures.add(toFigure(item, element, currentSectionTitle));
                } else if (element.elementType() == ParsedPaperElementType.FORMULA) {
                    formulas.add(toFormula(item, element, currentSectionTitle));
                }
            }
            order = appendMiddleCodeBodyElements(middleJson, elements, order);

            ParsedPaperMetadata metadata = new ParsedPaperMetadata(
                    originalFilename,
                    inferTitle(elements, markdown, originalFilename),
                    null,
                    inferPageCount(elements, pages),
                    null,
                    null
            );
            Map<String, Object> rawMetadata = new LinkedHashMap<>();
            rawMetadata.put("originalFilename", originalFilename);
            rawMetadata.put("parser", parserName);
            rawMetadata.put("parserVersion", parserVersion);

            return new ParsedPaper(
                    parserName,
                    parserVersion,
                    metadata,
                    elements,
                    rawMetadata,
                    rawParserJson(contentListJson, middleJson, markdown),
                    tables,
                    figures,
                    formulas,
                    artifactPayloads(contentListJson, middleJson, markdown),
                    pages
            );
        } catch (Exception e) {
            throw new PaperParsingException("Failed to map MinerU output", e);
        }
    }

    private List<ParsedPaperPage> toPhysicalPages(String middleJson) throws JsonProcessingException {
        if (middleJson == null || middleJson.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(middleJson);
        JsonNode pdfInfo = root.path("pdf_info");
        if (!pdfInfo.isArray()) {
            return List.of();
        }

        List<ParsedPaperPage> pages = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < pdfInfo.size(); pageIndex++) {
            JsonNode page = pdfInfo.get(pageIndex);
            Integer parserPageIndex = integer(page, "page_idx");
            int pageNumber = (parserPageIndex == null ? pageIndex : parserPageIndex) + 1;
            List<ParsedPaperPageBlock> blocks = new ArrayList<>();
            appendPhysicalPageBlocks(page.path("preproc_blocks"), pageNumber, blocks);

            Map<String, Object> pageAttributes = new LinkedHashMap<>();
            pageAttributes.put("source", "mineru_middle_json.preproc_blocks");
            pageAttributes.put("page_idx", pageNumber - 1);
            if (page.has("page_size")) {
                pageAttributes.put("page_size", objectMapper.convertValue(page.path("page_size"), Object.class));
            }
            pages.add(new ParsedPaperPage(pageNumber, blocks, pageAttributes));
        }
        return pages;
    }

    private void appendPhysicalPageBlocks(JsonNode nodes,
                                          int pageNumber,
                                          List<ParsedPaperPageBlock> target) {
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            JsonNode nested = node.path("blocks");
            if (nested.isArray() && !nested.isEmpty()) {
                appendPhysicalPageBlocks(nested, pageNumber, target);
                continue;
            }
            String blockText = middleBlockText(node);
            if (blockText.isBlank()) {
                continue;
            }
            int readingOrder = target.size() + 1;
            Map<String, Object> rawAttributes = new LinkedHashMap<>(toMap(node));
            rawAttributes.put("source", "mineru_middle_json.preproc_blocks");
            target.add(new ParsedPaperPageBlock(
                    "middle-page-" + pageNumber + "-block-" + readingOrder,
                    readingOrder,
                    normalizeKey(text(node, "type")),
                    blockText,
                    boundingBox(node.path("bbox"), pageNumber),
                    rawAttributes
            ));
        }
    }

    private int appendMiddleCodeBodyElements(String middleJson,
                                             List<ParsedPaperElement> elements,
                                             int order) throws JsonProcessingException {
        if (middleJson == null || middleJson.isBlank()) {
            return order;
        }
        JsonNode root = objectMapper.readTree(middleJson);
        JsonNode pdfInfo = root.path("pdf_info");
        if (!pdfInfo.isArray()) {
            return order;
        }
        Set<String> existingTextKeys = new LinkedHashSet<>();
        for (ParsedPaperElement element : elements) {
            if (element != null && element.text() != null && !element.text().isBlank()) {
                existingTextKeys.add(textKey(element.text()));
            }
        }
        for (int pageIndex = 0; pageIndex < pdfInfo.size(); pageIndex++) {
            JsonNode page = pdfInfo.get(pageIndex);
            List<JsonNode> blocks = middleBlocks(page);
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                JsonNode block = blocks.get(blockIndex);
                if (!"code body".equals(normalizeKey(text(block, "type")))) {
                    continue;
                }
                String blockText = middleBlockText(block);
                String key = textKey(blockText);
                if (key.isBlank() || existingTextKeys.contains(key)) {
                    continue;
                }
                existingTextKeys.add(key);
                order++;
                int pageNumber = pageIndex + 1;
                Map<String, Object> rawAttributes = toMap(block);
                rawAttributes.put("type", "code_body");
                rawAttributes.put("source", "mineru_middle_json");
                rawAttributes.put("page_idx", pageIndex);
                elements.add(new ParsedPaperElement(
                        "middle-code-body-" + pageNumber + "-" + blockIndex,
                        pageNumber,
                        order,
                        ParsedPaperElementType.LIST,
                        blockText,
                        null,
                        null,
                        boundingBox(block.path("bbox"), pageNumber),
                        rawAttributes
                ));
            }
        }
        return order;
    }

    private List<JsonNode> middleBlocks(JsonNode page) {
        List<JsonNode> blocks = new ArrayList<>();
        appendBlocks(blocks, page.path("preproc_blocks"));
        appendBlocks(blocks, page.path("para_blocks"));
        return blocks;
    }

    private void appendBlocks(List<JsonNode> blocks, JsonNode blockGroups) {
        if (!blockGroups.isArray()) {
            return;
        }
        for (JsonNode group : blockGroups) {
            JsonNode nestedBlocks = group.path("blocks");
            if (nestedBlocks.isArray()) {
                nestedBlocks.forEach(blocks::add);
            } else if (group.isObject()) {
                blocks.add(group);
            }
        }
    }

    private String middleBlockText(JsonNode block) {
        List<String> lines = new ArrayList<>();
        JsonNode rawLines = block.path("lines");
        if (!rawLines.isArray()) {
            return "";
        }
        for (JsonNode line : rawLines) {
            List<String> spans = new ArrayList<>();
            JsonNode rawSpans = line.path("spans");
            if (!rawSpans.isArray()) {
                continue;
            }
            for (JsonNode span : rawSpans) {
                String content = firstNonBlank(text(span, "content"), text(span, "text"));
                if (content != null) {
                    spans.add(content);
                }
            }
            String lineText = normalizeWhitespace(String.join(" ", spans));
            if (!lineText.isBlank()) {
                lines.add(lineText);
            }
        }
        return String.join("\n", lines);
    }

    private String textKey(String text) {
        return normalizeWhitespace(text)
                .replaceAll("\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private JsonNode parseContentList(String contentListJson) throws JsonProcessingException {
        if (contentListJson == null || contentListJson.isBlank()) {
            return objectMapper.createArrayNode();
        }
        return objectMapper.readTree(contentListJson);
    }

    private ParsedPaperElement toElement(JsonNode item,
                                         int readingOrder,
                                         String currentSectionTitle,
                                         Integer currentSectionLevel) {
        if (item == null || !item.isObject()) {
            return null;
        }
        ParsedPaperElementType elementType = mapType(text(item, "type"), item);
        String elementId = firstNonBlank(text(item, "id"), String.valueOf(readingOrder));
        Integer pageNumber = pageNumber(item);
        Integer headingLevel = headingLevel(item);
        if (elementType == ParsedPaperElementType.HEADING) {
            currentSectionTitle = null;
            currentSectionLevel = headingLevel;
        }
        return new ParsedPaperElement(
                elementId,
                pageNumber,
                readingOrder,
                elementType,
                extractText(item, elementType),
                currentSectionTitle,
                elementType == ParsedPaperElementType.HEADING ? headingLevel : currentSectionLevel,
                boundingBox(item.path("bbox"), pageNumber),
                toMap(item)
        );
    }

    private ParsedPaperElementType mapType(String type, JsonNode item) {
        String normalized = normalizeKey(type);
        if ("table".equals(normalized)) {
            return ParsedPaperElementType.TABLE;
        }
        if ("image".equals(normalized) || "figure".equals(normalized) || "pic".equals(normalized)) {
            return ParsedPaperElementType.FIGURE;
        }
        if ("chart".equals(normalized)) {
            return ParsedPaperElementType.CHART;
        }
        if ("equation".equals(normalized) || "formula".equals(normalized)
                || "interline equation".equals(normalized) || "inline equation".equals(normalized)) {
            return ParsedPaperElementType.FORMULA;
        }
        if ("text".equals(normalized) && headingLevel(item) != null) {
            return ParsedPaperElementType.HEADING;
        }
        if ("title".equals(normalized)) {
            return ParsedPaperElementType.TITLE;
        }
        if ("list".equals(normalized)) {
            return ParsedPaperElementType.LIST;
        }
        return ParsedPaperElementType.PARAGRAPH;
    }

    private String extractText(JsonNode item, ParsedPaperElementType elementType) {
        return switch (elementType) {
            case TABLE -> buildTableText(tableCaption(item), htmlText(text(item, "table_body")));
            case FIGURE, CHART -> firstNonBlank(figureCaption(item, elementType),
                    text(item, "text"),
                    text(item, "image_text"),
                    text(item, "content"));
            case FORMULA -> firstNonBlank(text(item, "text"), text(item, "latex"), text(item, "formula"));
            default -> firstNonBlank(text(item, "text"), text(item, "content"));
        };
    }

    private ParsedPaperTable toTable(JsonNode item, ParsedPaperElement element, String sectionTitle) {
        String caption = tableCaption(item);
        String html = text(item, "table_body");
        String tablePlainText = htmlText(html);
        String tableText = buildTableText(caption, tablePlainText);
        return new ParsedPaperTable(
                "table-" + element.readingOrder(),
                element.elementId(),
                element.pageNumber(),
                element.readingOrder(),
                caption,
                sectionTitle,
                null,
                null,
                tableText,
                tableMarkdown(html, tablePlainText),
                element.boundingBox(),
                toMap(item)
        );
    }

    private ParsedPaperFigure toFigure(JsonNode item, ParsedPaperElement element, String sectionTitle) {
        return new ParsedPaperFigure(
                "figure-" + element.readingOrder(),
                element.elementId(),
                element.pageNumber(),
                element.readingOrder(),
                figureCaption(item, element.elementType()),
                sectionTitle,
                extractText(item, element.elementType()),
                element.boundingBox(),
                "MINERU_" + element.elementType().name(),
                "HIGH",
                toMap(item)
        );
    }

    private ParsedPaperFormula toFormula(JsonNode item, ParsedPaperElement element, String sectionTitle) {
        return new ParsedPaperFormula(
                "formula-" + element.readingOrder(),
                element.elementId(),
                element.pageNumber(),
                element.readingOrder(),
                extractText(item, ParsedPaperElementType.FORMULA),
                text(item, "context"),
                sectionTitle,
                element.boundingBox(),
                toMap(item)
        );
    }

    private String rawParserJson(String contentListJson, String middleJson, String markdown) throws JsonProcessingException {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("contentList", readJsonOrRaw(contentListJson));
        raw.put("middle", readJsonOrRaw(middleJson));
        raw.put("markdown", markdown);
        return objectMapper.writeValueAsString(raw);
    }

    private Object readJsonOrRaw(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    private List<ParsedPaperArtifactPayload> artifactPayloads(String contentListJson, String middleJson, String markdown) {
        List<ParsedPaperArtifactPayload> artifacts = new ArrayList<>();
        addArtifact(artifacts, "MINERU_CONTENT_LIST", "content_list.json", "application/json", contentListJson);
        addArtifact(artifacts, "MINERU_MIDDLE_JSON", "middle.json", "application/json", middleJson);
        addArtifact(artifacts, "MINERU_MARKDOWN", "document.md", "text/markdown", markdown);
        return artifacts;
    }

    private void addArtifact(List<ParsedPaperArtifactPayload> artifacts,
                             String type,
                             String filename,
                             String contentType,
                             String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        artifacts.add(new ParsedPaperArtifactPayload(
                type,
                filename,
                contentType,
                content.getBytes(StandardCharsets.UTF_8)
        ));
    }

    private BoundingBox boundingBox(JsonNode bboxNode, Integer pageNumber) {
        if (bboxNode == null || !bboxNode.isArray() || bboxNode.size() != 4) {
            return null;
        }
        return new BoundingBox(
                pageNumber,
                bboxNode.get(0).asDouble(),
                bboxNode.get(3).asDouble(),
                bboxNode.get(2).asDouble(),
                bboxNode.get(1).asDouble(),
                "mineru_1000",
                "top_left_1000"
        );
    }

    private Integer pageNumber(JsonNode item) {
        Integer pageIdx = integer(item, "page_idx");
        if (pageIdx == null) {
            pageIdx = integer(item, "pageIndex");
        }
        return pageIdx == null ? null : pageIdx + 1;
    }

    private Integer headingLevel(JsonNode item) {
        Integer level = integer(item, "text_level");
        if (level == null) {
            level = integer(item, "heading_level");
        }
        return level;
    }

    private Integer inferPageCount(List<ParsedPaperElement> elements, List<ParsedPaperPage> pages) {
        Integer elementPageCount = elements.stream()
                .map(ParsedPaperElement::pageNumber)
                .filter(page -> page != null && page > 0)
                .max(Integer::compareTo)
                .orElse(null);
        Integer physicalPageCount = pages.stream()
                .map(ParsedPaperPage::pageNumber)
                .filter(page -> page != null && page > 0)
                .max(Integer::compareTo)
                .orElse(null);
        if (elementPageCount == null) {
            return physicalPageCount;
        }
        if (physicalPageCount == null) {
            return elementPageCount;
        }
        return Math.max(elementPageCount, physicalPageCount);
    }

    private String inferTitle(List<ParsedPaperElement> elements, String markdown, String originalFilename) {
        return elements.stream()
                .filter(element -> element.elementType() == ParsedPaperElementType.HEADING
                        || element.elementType() == ParsedPaperElementType.TITLE)
                .map(ParsedPaperElement::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElseGet(() -> {
                    if (markdown != null) {
                        for (String line : markdown.split("\\R")) {
                            String trimmed = line.replaceFirst("^#+\\s*", "").trim();
                            if (!trimmed.isBlank()) {
                                return trimmed;
                            }
                        }
                    }
                    return originalFilename;
                });
    }

    private String tableCaption(JsonNode item) {
        return joinTextArray(item.path("table_caption"));
    }

    private String figureCaption(JsonNode item, ParsedPaperElementType elementType) {
        String imageCaption = joinTextArray(item.path("image_caption"));
        String chartCaption = chartCaption(item);
        if (elementType == ParsedPaperElementType.CHART) {
            return firstNonBlank(chartCaption, imageCaption);
        }
        return firstNonBlank(imageCaption, chartCaption);
    }

    private String chartCaption(JsonNode item) {
        List<String> values = textArray(item.path("chart_caption"));
        if (values.stream().noneMatch(this::isFullFigureCaption)) {
            return null;
        }
        return String.join(" ", values);
    }

    private String buildTableText(String caption, String tablePlainText) {
        List<String> parts = new ArrayList<>();
        if (caption != null && !caption.isBlank()) {
            parts.add("Caption: " + caption);
        }
        if (tablePlainText != null && !tablePlainText.isBlank()) {
            parts.add(tablePlainText);
        }
        return String.join("\n", parts);
    }

    private String tableMarkdown(String html, String plainText) {
        if (html == null || html.isBlank()) {
            return plainText;
        }
        String normalized = html
                .replaceAll("(?i)</tr>\\s*<tr>", "\n")
                .replaceAll("(?i)</t[dh]>\\s*<t[dh][^>]*>", " | ")
                .replaceAll("(?i)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
        return normalizeWhitespacePreserveLines(normalized);
    }

    private String htmlText(String html) {
        if (html == null) {
            return "";
        }
        return normalizeWhitespacePreserveLines(html
                .replaceAll("(?i)</tr>\\s*<tr>", "\n")
                .replaceAll("(?i)</t[dh]>\\s*<t[dh][^>]*>", "\t")
                .replaceAll("(?i)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&"));
    }

    private String joinTextArray(JsonNode node) {
        List<String> values = textArray(node);
        return values.isEmpty() ? null : String.join(" ", values);
    }

    private List<String> textArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            return node.asText().isBlank() ? List.of() : List.of(node.asText());
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private boolean isFullFigureCaption(String value) {
        String normalized = normalizeWhitespace(value);
        return normalized != null
                && (normalized.matches("(?i)^(fig\\.?|figure)\\s*(s?\\d+|[ivxlcdm]+)[a-z]?\\b.*")
                || normalized.matches("^图\\s*\\d+.*"));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace("_", " ");
    }

    private String normalizeWhitespace(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private String normalizeWhitespacePreserveLines(String value) {
        if (value == null) {
            return "";
        }
        String[] lines = value.split("\\R");
        List<String> normalized = new ArrayList<>();
        for (String line : lines) {
            String clean = normalizeWhitespace(line);
            if (clean != null && !clean.isBlank()) {
                normalized.add(clean);
            }
        }
        return String.join("\n", normalized);
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
}
