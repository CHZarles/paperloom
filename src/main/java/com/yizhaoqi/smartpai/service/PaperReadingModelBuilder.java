package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PaperReadingModelBuilder {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaperReadingModelBuildResult build(String paperId,
                                              String modelVersion,
                                              ParsedPaper parsedPaper,
                                              String userId,
                                              String orgTag,
                                              boolean isPublic) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("paperId", paperId);
        diagnostics.put("modelVersion", modelVersion);
        diagnostics.put("parserName", parsedPaper == null ? null : parsedPaper.parserName());
        diagnostics.put("parserVersion", parsedPaper == null ? null : parsedPaper.parserVersion());

        if (parsedPaper == null) {
            throw failure("PARSED_PAPER_MISSING", diagnostics);
        }
        if (parsedPaper.elements() == null || parsedPaper.elements().isEmpty()) {
            diagnostics.put("elementCount", 0);
            diagnostics.put("elementsWithPageNumber", 0);
            diagnostics.put("elementsWithText", 0);
            diagnostics.put("elementsSkippedNoPage", 0);
            diagnostics.put("elementsSkippedBlankText", 0);
            diagnostics.put("pagesWithoutText", resolvePageCount(parsedPaper));
            throw failure("PARSED_ELEMENTS_EMPTY", diagnostics);
        }

        List<ParsedPaperElement> elements = parsedPaper.elements();
        int elementsSkippedNoPage = 0;
        int elementsSkippedBlankText = 0;
        int elementsWithPageNumber = 0;
        int elementsWithText = 0;
        List<ReadableElement> readable = new ArrayList<>();
        for (ParsedPaperElement element : elements) {
            if (element == null) {
                elementsSkippedBlankText++;
                continue;
            }

            String text = normalizeText(element.text());
            boolean hasText = !text.isBlank();
            boolean hasPageNumber = element.pageNumber() != null && element.pageNumber() > 0;
            if (hasText) {
                elementsWithText++;
            }
            if (hasPageNumber) {
                elementsWithPageNumber++;
            }
            if (!hasText) {
                elementsSkippedBlankText++;
                continue;
            }
            if (!hasPageNumber) {
                elementsSkippedNoPage++;
                continue;
            }
            readable.add(new ReadableElement(element, text));
        }

        diagnostics.put("elementCount", elements.size());
        diagnostics.put("elementsWithPageNumber", elementsWithPageNumber);
        diagnostics.put("elementsWithText", elementsWithText);
        diagnostics.put("elementsSkippedNoPage", elementsSkippedNoPage);
        diagnostics.put("elementsSkippedBlankText", elementsSkippedBlankText);

        if (readable.isEmpty()) {
            diagnostics.put("pagesWithoutText", resolvePageCount(parsedPaper));
            throw failure("NO_READABLE_NUMBERED_TEXT", diagnostics);
        }

        Map<Integer, List<ReadableElement>> byPage = readable.stream()
                .sorted(Comparator
                        .comparing((ReadableElement item) -> item.element().pageNumber())
                        .thenComparing(item -> item.element().readingOrder(), Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.groupingBy(
                        item -> item.element().pageNumber(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<PaperPage> pages = new ArrayList<>();
        List<PaperLocation> locations = new ArrayList<>();
        int readableCharCount = 0;
        for (Map.Entry<Integer, List<ReadableElement>> entry : byPage.entrySet()) {
            int pageNumber = entry.getKey();
            List<ReadableElement> pageElements = entry.getValue();
            String pageText = pageElements.stream()
                    .map(ReadableElement::text)
                    .collect(Collectors.joining("\n\n"));
            String sourceSpanJson = sourceSpanJson(parsedPaper, pageNumber, pageElements);

            PaperPage page = new PaperPage();
            page.setPaperId(paperId);
            page.setModelVersion(modelVersion);
            page.setPageNumber(pageNumber);
            page.setPageText(pageText);
            page.setTextHash(sha256(pageText));
            page.setCharCount(pageText.length());
            page.setSourceSpanJson(sourceSpanJson);
            page.setParserName(parsedPaper.parserName());
            page.setParserVersion(parsedPaper.parserVersion());
            page.setUserId(userId);
            page.setOrgTag(orgTag);
            page.setPublic(isPublic);
            pages.add(page);
            readableCharCount += pageText.length();

            PaperLocation location = new PaperLocation();
            location.setLocationRef("page_ref_" + UUID.randomUUID().toString().replace("-", ""));
            location.setPaperId(paperId);
            location.setModelVersion(modelVersion);
            location.setLocationType(PaperLocationType.PAGE);
            location.setPageNumber(pageNumber);
            location.setSectionTitle(resolveSectionTitle(pageElements));
            location.setSourceSpanJson(sourceSpanJson);
            location.setContentKind("PAGE_TEXT");
            location.setUserId(userId);
            location.setOrgTag(orgTag);
            location.setPublic(isPublic);
            locations.add(location);
        }

        diagnostics.put("pageCount", resolvePageCount(parsedPaper));
        diagnostics.put("readablePageCount", pages.size());
        diagnostics.put("readableCharCount", readableCharCount);
        diagnostics.put("pagesWithoutText", Math.max(0, resolvePageCount(parsedPaper) - pages.size()));
        diagnostics.put("locationCount", locations.size());
        diagnostics.put("hasAnyBbox", readable.stream().anyMatch(item -> item.element().boundingBox() != null));
        return new PaperReadingModelBuildResult(pages, locations, writeJson(diagnostics));
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u0000", "")
                .trim();
    }

    private String sourceSpanJson(ParsedPaper paper, int pageNumber, List<ReadableElement> pageElements) {
        List<String> elementIds = pageElements.stream()
                .map(item -> item.element().elementId())
                .filter(Objects::nonNull)
                .toList();
        List<Integer> orders = pageElements.stream()
                .map(item -> item.element().readingOrder())
                .filter(Objects::nonNull)
                .toList();
        LinkedHashSet<String> sourceKinds = pageElements.stream()
                .map(item -> item.element().elementType() == null ? "UNKNOWN" : item.element().elementType().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<BoundingBox> bboxes = pageElements.stream()
                .map(item -> item.element().boundingBox())
                .filter(Objects::nonNull)
                .toList();

        Map<String, Object> sourceSpan = new LinkedHashMap<>();
        sourceSpan.put("parserName", paper.parserName());
        sourceSpan.put("parserVersion", paper.parserVersion());
        sourceSpan.put("pageNumber", pageNumber);
        sourceSpan.put("elementIds", elementIds);
        sourceSpan.put("readingOrderFrom", orders.stream().min(Integer::compareTo).orElse(null));
        sourceSpan.put("readingOrderTo", orders.stream().max(Integer::compareTo).orElse(null));
        sourceSpan.put("bbox", bboxes.isEmpty() ? null : bboxes);
        sourceSpan.put("sourceKinds", List.copyOf(sourceKinds));
        sourceSpan.put("rawArtifactRef", null);
        return writeJson(sourceSpan);
    }

    private String resolveSectionTitle(List<ReadableElement> pageElements) {
        return pageElements.stream()
                .map(item -> item.element().sectionTitle())
                .filter(sectionTitle -> sectionTitle != null && !sectionTitle.isBlank())
                .map(String::trim)
                .findFirst()
                .orElseGet(() -> pageElements.stream()
                        .filter(item -> item.element().elementType() == ParsedPaperElementType.HEADING
                                || item.element().elementType() == ParsedPaperElementType.TITLE)
                        .map(ReadableElement::text)
                        .filter(text -> !text.isBlank())
                        .findFirst()
                        .orElse(null));
    }

    private int resolvePageCount(ParsedPaper parsedPaper) {
        if (parsedPaper == null) {
            return 0;
        }
        if (parsedPaper.metadata() != null && parsedPaper.metadata().pageCount() != null) {
            return parsedPaper.metadata().pageCount();
        }
        return parsedPaper.elements() == null ? 0 : parsedPaper.elements().stream()
                .filter(Objects::nonNull)
                .map(ParsedPaperElement::pageNumber)
                .filter(pageNumber -> pageNumber != null && pageNumber > 0)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private PaperReadingModelValidationException failure(String reason, Map<String, Object> diagnostics) {
        diagnostics.put("failureReason", reason);
        return new PaperReadingModelValidationException(reason, writeJson(diagnostics));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize reading model JSON", e);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ReadableElement(ParsedPaperElement element, String text) {
    }
}
