package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingElement;
import com.yizhaoqi.smartpai.model.PaperSection;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFigure;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFormula;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperTable;
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
        return build(paperId, modelVersion, parsedPaper, null, userId, orgTag, isPublic);
    }

    public PaperReadingModelBuildResult build(String paperId,
                                              String modelVersion,
                                              ParsedPaper parsedPaper,
                                              Integer physicalPageCount,
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
            diagnostics.put("pageTextElementsOmittedNoPageCount", 0);
            diagnostics.put("pageTextElementsOmittedBlankTextCount", 0);
            diagnostics.put("pagesWithoutText", resolvePageCount(parsedPaper));
            throw failure("PARSED_ELEMENTS_EMPTY", diagnostics);
        }

        List<ParsedPaperElement> elements = parsedPaper.elements();
        int pageTextElementsOmittedNoPageCount = 0;
        int pageTextElementsOmittedBlankTextCount = 0;
        int elementsWithPageNumber = 0;
        int elementsWithText = 0;
        List<ReadableElement> readable = new ArrayList<>();
        for (ParsedPaperElement element : elements) {
            if (element == null) {
                pageTextElementsOmittedBlankTextCount++;
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
                pageTextElementsOmittedBlankTextCount++;
                continue;
            }
            if (!hasPageNumber) {
                pageTextElementsOmittedNoPageCount++;
                continue;
            }
            readable.add(new ReadableElement(element, text));
        }

        diagnostics.put("elementCount", elements.size());
        diagnostics.put("elementsWithPageNumber", elementsWithPageNumber);
        diagnostics.put("elementsWithText", elementsWithText);
        diagnostics.put("pageTextElementsOmittedNoPageCount", pageTextElementsOmittedNoPageCount);
        diagnostics.put("pageTextElementsOmittedBlankTextCount", pageTextElementsOmittedBlankTextCount);

        if (readable.isEmpty()) {
            diagnostics.put("pagesWithoutText", resolvePageCount(parsedPaper));
            throw failure("NO_READABLE_NUMBERED_TEXT", diagnostics);
        }

        List<ReadableElement> sortedReadable = readable.stream()
                .sorted(Comparator
                        .comparing((ReadableElement item) -> item.element().pageNumber())
                        .thenComparing(item -> item.element().readingOrder(), Comparator.nullsLast(Integer::compareTo)))
                .toList();
        Map<Integer, List<ReadableElement>> byPage = sortedReadable.stream()
                .collect(Collectors.groupingBy(
                        item -> item.element().pageNumber(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        int parserPageCount = resolvePageCount(parsedPaper);
        int effectivePageCount = resolvePhysicalPageCount(physicalPageCount, parserPageCount, byPage);
        List<PaperPage> pages = new ArrayList<>();
        List<PaperSection> sections = new ArrayList<>();
        List<PaperLocation> locations = new ArrayList<>();
        int readableCharCount = 0;
        int readablePageCount = 0;
        int displayOrder = 1;
        for (int pageNumber = 1; pageNumber <= effectivePageCount; pageNumber++) {
            List<ReadableElement> pageElements = byPage.getOrDefault(pageNumber, List.of());
            String pageText = pageElements.stream()
                    .map(ReadableElement::text)
                    .collect(Collectors.joining("\n\n"));
            boolean readablePage = !pageText.isBlank();
            String sourceSpanJson = sourceSpanJson(parsedPaper, pageNumber, pageElements);

            PaperPage page = new PaperPage();
            page.setPaperId(paperId);
            page.setModelVersion(modelVersion);
            page.setPageNumber(pageNumber);
            page.setPageText(pageText);
            page.setTextHash(sha256(pageText));
            page.setCharCount(pageText.length());
            page.setTextStatus(readablePage ? PaperPage.TEXT_STATUS_READABLE : PaperPage.TEXT_STATUS_TEXTLESS);
            page.setSourceSpanJson(sourceSpanJson);
            page.setParserName(parsedPaper.parserName());
            page.setParserVersion(parsedPaper.parserVersion());
            page.setUserId(userId);
            page.setOrgTag(orgTag);
            page.setPublic(isPublic);
            pages.add(page);
            if (readablePage) {
                readableCharCount += pageText.length();
                readablePageCount++;
            }

            PaperLocation location = new PaperLocation();
            location.setLocationRef("page_ref_" + UUID.randomUUID().toString().replace("-", ""));
            location.setPaperId(paperId);
            location.setModelVersion(modelVersion);
            location.setLocationType(PaperLocationType.PAGE);
            location.setPageNumber(pageNumber);
            location.setPageEndNumber(pageNumber);
            location.setDisplayOrder(displayOrder++);
            location.setSectionTitle(resolveSectionTitle(pageElements));
            location.setSourceSpanJson(sourceSpanJson);
            location.setContentKind(readablePage ? "PAGE_TEXT" : "PAGE_SURFACE");
            location.setUserId(userId);
            location.setOrgTag(orgTag);
            location.setPublic(isPublic);
            locations.add(location);
        }

        SectionLocationBuildResult structured = buildStructuredLocations(
                paperId,
                modelVersion,
                parsedPaper,
                userId,
                orgTag,
                isPublic,
                sortedReadable,
                displayOrder
        );
        sections.addAll(structured.sections());
        locations.addAll(structured.locations());
        displayOrder = structured.nextDisplayOrder();
        List<PaperReadingElement> readingElements = buildReadingElements(
                paperId,
                modelVersion,
                parsedPaper,
                userId,
                orgTag,
                isPublic
        );
        ElementLocationBuildResult elementLocations = buildElementLocations(
                paperId,
                modelVersion,
                parsedPaper,
                userId,
                orgTag,
                isPublic,
                readingElements,
                displayOrder
        );
        locations.addAll(elementLocations.locations());

        diagnostics.put("pageCount", effectivePageCount);
        diagnostics.put("physicalPageCount", effectivePageCount);
        diagnostics.put("parserMetadataPageCount", parserPageCount);
        diagnostics.put("readablePageCount", readablePageCount);
        diagnostics.put("readableCharCount", readableCharCount);
        diagnostics.put("textlessPageCount", Math.max(0, effectivePageCount - readablePageCount));
        diagnostics.put("pagesWithoutText", Math.max(0, effectivePageCount - readablePageCount));
        diagnostics.put("pageLocationCount", effectivePageCount);
        diagnostics.put("sectionCount", structured.sectionCount());
        diagnostics.put("sectionLocationCount", structured.sectionLocationCount());
        diagnostics.put("sectionLocationNotCreatedBlankTextCount", structured.sectionLocationNotCreatedBlankTextCount());
        diagnostics.put("tableCount", elementLocations.tableCount());
        diagnostics.put("tableLocationCount", elementLocations.tableLocationCount());
        diagnostics.put("tableLocationNotCreatedNoPageCount", elementLocations.tableLocationNotCreatedNoPageCount());
        diagnostics.put("tableLocationNotCreatedNoIdCount", elementLocations.tableLocationNotCreatedNoIdCount());
        diagnostics.put("tableLocationNotCreatedBlankPayloadCount", elementLocations.tableLocationNotCreatedBlankPayloadCount());
        diagnostics.put("figureCount", elementLocations.figureCount());
        diagnostics.put("figureLocationCount", elementLocations.figureLocationCount());
        diagnostics.put("figureLocationNotCreatedNoPageCount", elementLocations.figureLocationNotCreatedNoPageCount());
        diagnostics.put("figureLocationNotCreatedNoIdCount", elementLocations.figureLocationNotCreatedNoIdCount());
        diagnostics.put("figureLocationNotCreatedBlankTextCount", elementLocations.figureLocationNotCreatedBlankTextCount());
        diagnostics.put("structuredLocationCount", structured.sectionLocationCount()
                + elementLocations.tableLocationCount()
                + elementLocations.figureLocationCount());
        diagnostics.put("locationCount", locations.size());
        diagnostics.put("contentListItemCount", parsedPaper.elements() == null ? 0 : parsedPaper.elements().stream()
                .filter(Objects::nonNull)
                .count());
        diagnostics.put("readingElementCount", readingElements.size());
        diagnostics.put("retainedElementCount", readingElements.size());
        diagnostics.put("retainedElementsWithSearchableTextCount", readingElements.stream()
                .filter(element -> !isBlank(element.getSearchableText()))
                .count());
        diagnostics.put("retainedTableElementCount", readingElements.stream()
                .filter(element -> "TABLE".equals(element.getElementType()))
                .count());
        diagnostics.put("retainedFigureElementCount", readingElements.stream()
                .filter(element -> "IMAGE".equals(element.getElementType()) || "CHART".equals(element.getElementType()))
                .count());
        diagnostics.put("retainedFormulaElementCount", readingElements.stream()
                .filter(element -> "FORMULA".equals(element.getElementType()))
                .count());
        diagnostics.put("retainedPanelOnlyChartCount", readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource()))
                .count());
        diagnostics.put("attachedPanelOnlyChartCount", readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource())
                        && "ATTACHED".equals(element.getAssociationStatus()))
                .count());
        diagnostics.put("ambiguousPanelOnlyChartCount", readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource())
                        && "AMBIGUOUS".equals(element.getAssociationStatus()))
                .count());
        diagnostics.put("unattachedPanelOnlyChartCount", readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource())
                        && "UNATTACHED".equals(element.getAssociationStatus()))
                .count());
        diagnostics.put("tableElementCount", elementLocations.tableCount());
        diagnostics.put("figureElementCount", elementLocations.figureCount());
        diagnostics.put("formulaElementCount", readingElements.stream()
                .filter(element -> "FORMULA".equals(element.getElementType()))
                .count());
        diagnostics.put("visualOnlyElementCount", readingElements.stream()
                .filter(element -> isBlank(element.getSearchableText()))
                .filter(element -> !isBlank(element.getParserImagePath()) || !isBlank(element.getBboxJson()))
                .count());
        diagnostics.put("readingElementsWithoutTextCount", readingElements.stream()
                .filter(element -> isBlank(element.getSearchableText()))
                .count());
        diagnostics.put("tableLocationNotCreatedCount", elementLocations.tableCount() - elementLocations.tableLocationCount());
        diagnostics.put("figureLocationNotCreatedCount", elementLocations.figureCount() - elementLocations.figureLocationCount());
        diagnostics.put("formulaLocationDeferredCount", parsedPaper.formulas() == null ? 0 : parsedPaper.formulas().size());
        diagnostics.put("hasAnyBbox", readingElements.stream().anyMatch(element -> !isBlank(element.getBboxJson())));
        return new PaperReadingModelBuildResult(pages, sections, locations, readingElements, writeJson(diagnostics));
    }

    private SectionLocationBuildResult buildStructuredLocations(String paperId,
                                                                String modelVersion,
                                                                ParsedPaper parsedPaper,
                                                                String userId,
                                                                String orgTag,
                                                                boolean isPublic,
                                                                List<ReadableElement> sortedReadable,
                                                                int displayOrderStart) {
        List<PaperSection> sections = new ArrayList<>();
        List<PaperLocation> locations = new ArrayList<>();
        int displayOrder = displayOrderStart;

        int sectionLocationNotCreatedBlankTextCount = 0;
        for (SectionGroup group : sectionGroups(sortedReadable)) {
            String sectionText = group.elements().stream()
                    .map(ReadableElement::text)
                    .collect(Collectors.joining("\n\n"));
            if (sectionText.isBlank()) {
                sectionLocationNotCreatedBlankTextCount++;
                continue;
            }

            List<ReadableElement> elements = group.elements();
            int pageNumberFrom = elements.stream()
                    .map(item -> item.element().pageNumber())
                    .min(Integer::compareTo)
                    .orElse(1);
            int pageNumberTo = elements.stream()
                    .map(item -> item.element().pageNumber())
                    .max(Integer::compareTo)
                    .orElse(pageNumberFrom);
            List<Integer> orders = elements.stream()
                    .map(item -> item.element().readingOrder())
                    .filter(Objects::nonNull)
                    .toList();
            String sectionId = "section_" + UUID.randomUUID().toString().replace("-", "");
            String sourceSpanJson = sourceSpanJsonForElements(
                    parsedPaper,
                    PaperLocationType.SECTION,
                    sectionId,
                    pageNumberFrom,
                    pageNumberTo,
                    elements
            );

            PaperSection section = new PaperSection();
            section.setPaperId(paperId);
            section.setModelVersion(modelVersion);
            section.setSectionId(sectionId);
            section.setSectionTitle(group.title());
            section.setSectionLevel(group.level());
            section.setPageNumberFrom(pageNumberFrom);
            section.setPageNumberTo(pageNumberTo);
            section.setReadingOrderFrom(orders.stream().min(Integer::compareTo).orElse(null));
            section.setReadingOrderTo(orders.stream().max(Integer::compareTo).orElse(null));
            section.setDisplayOrder(displayOrder);
            section.setSectionText(sectionText);
            section.setTextHash(sha256(sectionText));
            section.setCharCount(sectionText.length());
            section.setSourceSpanJson(sourceSpanJson);
            section.setParserName(parsedPaper.parserName());
            section.setParserVersion(parsedPaper.parserVersion());
            section.setUserId(userId);
            section.setOrgTag(orgTag);
            section.setPublic(isPublic);
            sections.add(section);

            PaperLocation location = new PaperLocation();
            location.setLocationRef("section_ref_" + UUID.randomUUID().toString().replace("-", ""));
            location.setPaperId(paperId);
            location.setModelVersion(modelVersion);
            location.setLocationType(PaperLocationType.SECTION);
            location.setPageNumber(pageNumberFrom);
            location.setPageEndNumber(pageNumberTo);
            location.setSectionTitle(group.title());
            location.setSourceObjectId(sectionId);
            location.setDisplayOrder(displayOrder++);
            location.setSourceSpanJson(sourceSpanJson);
            location.setContentKind("SECTION_TEXT");
            location.setUserId(userId);
            location.setOrgTag(orgTag);
            location.setPublic(isPublic);
            locations.add(location);
        }

        return new SectionLocationBuildResult(
                sections,
                locations,
                sections.size(),
                sections.size(),
                sectionLocationNotCreatedBlankTextCount,
                displayOrder
        );
    }

    private List<SectionGroup> sectionGroups(List<ReadableElement> sortedReadable) {
        List<SectionGroup> groups = new ArrayList<>();
        SectionGroup current = null;
        boolean seenHeading = false;
        for (ReadableElement item : sortedReadable) {
            ParsedPaperElement element = item.element();
            boolean isHeading = element.elementType() == ParsedPaperElementType.HEADING;
            String headingText = isHeading ? item.text() : "";
            if (!headingText.isBlank()) {
                current = new SectionGroup(headingText, element.sectionLevel(), true, new ArrayList<>());
                groups.add(current);
                seenHeading = true;
            } else if (!seenHeading) {
                String sectionTitle = normalizeText(element.sectionTitle());
                if (!sectionTitle.isBlank()
                        && (current == null || !sectionTitle.equals(current.title()))) {
                    current = new SectionGroup(sectionTitle, element.sectionLevel(), false, new ArrayList<>());
                    groups.add(current);
                }
            }
            if (current != null) {
                current.elements().add(item);
            }
        }
        return groups;
    }

    private ElementLocationBuildResult buildElementLocations(String paperId,
                                                             String modelVersion,
                                                             ParsedPaper parsedPaper,
                                                             String userId,
                                                             String orgTag,
                                                             boolean isPublic,
                                                             List<PaperReadingElement> readingElements,
                                                             int displayOrderStart) {
        List<PaperLocation> locations = new ArrayList<>();
        int displayOrder = displayOrderStart;
        int tableCount = 0;
        int tableNoPageCount = 0;
        int tableNoIdCount = 0;
        int tableBlankPayloadCount = 0;
        int figureCount = 0;
        int figureNoPageCount = 0;
        int figureNoIdCount = 0;
        int figureBlankTextCount = 0;

        List<PaperReadingElement> sorted = readingElements.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(PaperReadingElement::getPageNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PaperReadingElement::getReadingOrder, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        for (PaperReadingElement element : sorted) {
            if ("TABLE".equals(element.getElementType())) {
                tableCount++;
                if (isBlank(element.getReadingElementId())) {
                    tableNoIdCount++;
                    element.setLocationNotCreatedReason("MISSING_ID");
                    continue;
                }
                if (element.getPageNumber() == null || element.getPageNumber() <= 0) {
                    tableNoPageCount++;
                    element.setLocationNotCreatedReason("MISSING_PAGE");
                    continue;
                }
                if (isBlank(element.getSearchableText())) {
                    tableBlankPayloadCount++;
                    element.setLocationNotCreatedReason(isBlank(element.getParserImagePath())
                            ? "BLANK_SEARCHABLE_TEXT"
                            : "BLANK_SEARCHABLE_TEXT_WITH_VISUAL_ASSET");
                    continue;
                }
                PaperLocation location = readingElementLocation(
                        paperId,
                        modelVersion,
                        parsedPaper,
                        userId,
                        orgTag,
                        isPublic,
                        element,
                        PaperLocationType.TABLE,
                        "table_ref_",
                        "TABLE",
                        displayOrder++
                );
                locations.add(location);
                attachOwnLocation(element, location);
            } else if ("IMAGE".equals(element.getElementType()) || "CHART".equals(element.getElementType())) {
                figureCount++;
                if (isBlank(element.getReadingElementId())) {
                    figureNoIdCount++;
                    element.setLocationNotCreatedReason("MISSING_ID");
                    continue;
                }
                if (element.getPageNumber() == null || element.getPageNumber() <= 0) {
                    figureNoPageCount++;
                    element.setLocationNotCreatedReason("MISSING_PAGE");
                    continue;
                }
                if ("chart_caption_panel_only".equals(element.getCaptionSource())) {
                    element.setLocationNotCreatedReason("PANEL_ONLY_CAPTION");
                    continue;
                }
                if (isBlank(element.getSearchableText())) {
                    figureBlankTextCount++;
                    element.setLocationNotCreatedReason(isBlank(element.getParserImagePath())
                            ? "BLANK_SEARCHABLE_TEXT"
                            : "BLANK_SEARCHABLE_TEXT_WITH_VISUAL_ASSET");
                    continue;
                }
                PaperLocation location = readingElementLocation(
                        paperId,
                        modelVersion,
                        parsedPaper,
                        userId,
                        orgTag,
                        isPublic,
                        element,
                        PaperLocationType.FIGURE,
                        "figure_ref_",
                        "FIGURE",
                        displayOrder++
                );
                locations.add(location);
                attachOwnLocation(element, location);
            }
        }

        return new ElementLocationBuildResult(
                locations,
                tableCount,
                locations.stream().filter(location -> location.getLocationType() == PaperLocationType.TABLE).count(),
                tableNoPageCount,
                tableNoIdCount,
                tableBlankPayloadCount,
                figureCount,
                locations.stream().filter(location -> location.getLocationType() == PaperLocationType.FIGURE).count(),
                figureNoPageCount,
                figureNoIdCount,
                figureBlankTextCount,
                displayOrder
        );
    }

    private PaperLocation readingElementLocation(String paperId,
                                                 String modelVersion,
                                                 ParsedPaper parsedPaper,
                                                 String userId,
                                                 String orgTag,
                                                 boolean isPublic,
                                                 PaperReadingElement element,
                                                 PaperLocationType locationType,
                                                 String refPrefix,
                                                 String contentKind,
                                                 int displayOrder) {
        PaperLocation location = new PaperLocation();
        location.setLocationRef(refPrefix + UUID.randomUUID().toString().replace("-", ""));
        location.setPaperId(paperId);
        location.setModelVersion(modelVersion);
        location.setLocationType(locationType);
        location.setPageNumber(element.getPageNumber());
        location.setPageEndNumber(element.getPageNumber());
        location.setSectionTitle(element.getSectionTitle());
        location.setSourceObjectId(element.getReadingElementId());
        location.setDisplayOrder(displayOrder);
        location.setSourceSpanJson(readingElementLocationSourceSpanJson(parsedPaper, locationType, element));
        location.setContentKind(contentKind);
        location.setUserId(userId);
        location.setOrgTag(orgTag);
        location.setPublic(isPublic);
        return location;
    }

    private List<PaperReadingElement> buildReadingElements(String paperId,
                                                           String modelVersion,
                                                           ParsedPaper parsedPaper,
                                                           String userId,
                                                           String orgTag,
                                                           boolean isPublic) {
        TypedPayloadIndex typedPayloadIndex = typedPayloadIndex(parsedPaper);
        List<PaperReadingElement> retained = new ArrayList<>();
        List<PaperReadingElement> retainedTextElements = new ArrayList<>();
        List<TableRetainedElement> tables = new ArrayList<>();
        List<FigureRetainedElement> figures = new ArrayList<>();
        LinkedHashSet<ParsedPaperTable> matchedTables = new LinkedHashSet<>();
        LinkedHashSet<ParsedPaperFigure> matchedFigures = new LinkedHashSet<>();
        LinkedHashSet<ParsedPaperFormula> matchedFormulas = new LinkedHashSet<>();

        if (parsedPaper.elements() != null) {
            for (ParsedPaperElement element : parsedPaper.elements()) {
                if (element == null) {
                    continue;
                }
                PaperReadingElement readingElement = baseReadingElement(
                        paperId,
                        modelVersion,
                        parsedPaper,
                        userId,
                        orgTag,
                        isPublic
                );
                applyElementProvenance(readingElement, element);

                ParsedPaperTable table = typedPayloadIndex.tableFor(element);
                ParsedPaperFigure figure = typedPayloadIndex.figureFor(element);
                ParsedPaperFormula formula = typedPayloadIndex.formulaFor(element);
                if (table != null) {
                    applyTablePayload(readingElement, parsedPaper, table);
                    matchedTables.add(table);
                    tables.add(new TableRetainedElement(table, readingElement, null));
                } else if (figure != null) {
                    applyFigurePayload(readingElement, parsedPaper, figure);
                    matchedFigures.add(figure);
                    figures.add(new FigureRetainedElement(figure, readingElement, null));
                } else if (formula != null) {
                    applyFormulaPayload(readingElement, parsedPaper, formula);
                    matchedFormulas.add(formula);
                } else {
                    applyTextPayload(readingElement, parsedPaper, element);
                    retainedTextElements.add(readingElement);
                }
                retained.add(readingElement);
            }
        }

        if (parsedPaper.tables() != null) {
            for (ParsedPaperTable table : parsedPaper.tables()) {
                if (table == null || matchedTables.contains(table)) {
                    continue;
                }
                PaperReadingElement readingElement = baseReadingElement(
                        paperId,
                        modelVersion,
                        parsedPaper,
                        userId,
                        orgTag,
                        isPublic
                );
                applyTablePayload(readingElement, parsedPaper, table);
                retained.add(readingElement);
                tables.add(new TableRetainedElement(table, readingElement, null));
            }
        }
        attachTableCaptionFragments(retainedTextElements, tables);

        if (parsedPaper.figures() != null) {
            for (ParsedPaperFigure figure : parsedPaper.figures()) {
                if (figure == null || matchedFigures.contains(figure)) {
                    continue;
                }
                PaperReadingElement readingElement = baseReadingElement(
                        paperId,
                        modelVersion,
                        parsedPaper,
                        userId,
                        orgTag,
                        isPublic
                );
                applyFigurePayload(readingElement, parsedPaper, figure);
                retained.add(readingElement);
                figures.add(new FigureRetainedElement(figure, readingElement, null));
            }
        }
        attachPanelOnlyFigures(figures);

        if (parsedPaper.formulas() != null) {
            for (ParsedPaperFormula formula : parsedPaper.formulas()) {
                if (formula == null || matchedFormulas.contains(formula)) {
                    continue;
                }
                PaperReadingElement readingElement = baseReadingElement(
                        paperId,
                        modelVersion,
                        parsedPaper,
                        userId,
                        orgTag,
                        isPublic
                );
                applyFormulaPayload(readingElement, parsedPaper, formula);
                retained.add(readingElement);
            }
        }

        return retained;
    }

    private PaperReadingElement baseReadingElement(String paperId,
                                                   String modelVersion,
                                                   ParsedPaper parsedPaper,
                                                   String userId,
                                                   String orgTag,
                                                   boolean isPublic) {
        PaperReadingElement readingElement = new PaperReadingElement();
        readingElement.setPaperId(paperId);
        readingElement.setModelVersion(modelVersion);
        readingElement.setReadingElementId("reading_element_" + UUID.randomUUID().toString().replace("-", ""));
        readingElement.setParserName(parsedPaper.parserName());
        readingElement.setParserVersion(parsedPaper.parserVersion());
        readingElement.setUserId(userId);
        readingElement.setOrgTag(orgTag);
        readingElement.setPublic(isPublic);
        return readingElement;
    }

    private void applyElementProvenance(PaperReadingElement readingElement, ParsedPaperElement element) {
        readingElement.setContentListIndex(element.readingOrder());
        readingElement.setParserElementId(element.elementId());
        readingElement.setSourceObjectId(element.elementId());
        readingElement.setElementType(readingElementType(element));
        readingElement.setPageNumber(element.pageNumber());
        readingElement.setReadingOrder(element.readingOrder());
        readingElement.setSectionTitle(element.sectionTitle());
        readingElement.setAssociationStatus("SELF");
        readingElement.setAttachmentRole("NONE");
        readingElement.setCaptionSource("not_applicable");
        readingElement.setParserImagePath(parserImagePath(element.rawAttributes()));
        readingElement.setBboxJson(element.boundingBox() == null ? null : writeJson(element.boundingBox()));
        readingElement.setRawAttributesJson(rawAttributesJson(element.rawAttributes()));
    }

    private void applyTextPayload(PaperReadingElement readingElement,
                                  ParsedPaper parsedPaper,
                                  ParsedPaperElement element) {
        readingElement.setLocationNotCreatedReason("PAGE_TEXT_RETAINED_ONLY");
        readingElement.setBodyText(normalizeText(element.text()));
        readingElement.setSearchableText(normalizeText(element.text()));
        readingElement.setSourceSpanJson(readingElementSourceSpanJson(parsedPaper, readingElement));
        readingElement.setStructuredPayloadJson(writeJson(Map.of(
                "parserType", element.elementType() == null ? "UNKNOWN" : element.elementType().name()
        )));
    }

    private void applyTablePayload(PaperReadingElement readingElement,
                                   ParsedPaper parsedPaper,
                                   ParsedPaperTable table) {
        readingElement.setContentListIndex(table.readingOrder());
        readingElement.setParserElementId(table.elementId());
        readingElement.setSourceObjectId(table.tableId());
        readingElement.setElementType("TABLE");
        readingElement.setPageNumber(table.pageNumber());
        readingElement.setReadingOrder(table.readingOrder());
        readingElement.setSectionTitle(table.sectionTitle());
        readingElement.setAssociationStatus("SELF");
        readingElement.setAttachmentRole("NONE");
        readingElement.setCaptionSource(isBlank(table.caption()) ? "no_caption" : "table_caption");
        readingElement.setCaptionText(normalizeText(table.caption()));
        readingElement.setBodyText(firstNonBlank(table.tableMarkdown(), table.tableText()));
        readingElement.setSearchableText(combineUnique(readingElement.getCaptionText(), readingElement.getBodyText()));
        readingElement.setParserImagePath(parserImagePath(table.rawAttributes()));
        readingElement.setBboxJson(table.boundingBox() == null ? null : writeJson(table.boundingBox()));
        readingElement.setRawAttributesJson(rawAttributesJson(table.rawAttributes()));
        readingElement.setStructuredPayloadJson(tableStructuredPayloadJson(table));
        readingElement.setSourceSpanJson(readingElementSourceSpanJson(parsedPaper, readingElement));
    }

    private void applyFigurePayload(PaperReadingElement readingElement,
                                    ParsedPaper parsedPaper,
                                    ParsedPaperFigure figure) {
        String captionSource = figureCaptionSource(figure);
        String captionText = firstNonBlank(figure.caption(), rawCaptionText(figure.rawAttributes(), captionSource));
        String bodyText = sameNormalizedText(captionText, figure.figureText()) ? "" : normalizeText(figure.figureText());
        readingElement.setContentListIndex(figure.readingOrder());
        readingElement.setParserElementId(figure.elementId());
        readingElement.setSourceObjectId(figure.figureId());
        readingElement.setElementType(figureElementType(figure));
        readingElement.setPageNumber(figure.pageNumber());
        readingElement.setReadingOrder(figure.readingOrder());
        readingElement.setSectionTitle(figure.sectionTitle());
        readingElement.setAssociationStatus("SELF");
        readingElement.setAttachmentRole("NONE");
        readingElement.setCaptionSource(captionSource);
        readingElement.setCaptionText(captionText);
        readingElement.setBodyText(bodyText);
        readingElement.setSearchableText(combineUnique(captionText, bodyText));
        readingElement.setParserImagePath(parserImagePath(figure.rawAttributes()));
        readingElement.setBboxJson(figure.boundingBox() == null ? null : writeJson(figure.boundingBox()));
        readingElement.setRawAttributesJson(rawAttributesJson(figure.rawAttributes()));
        readingElement.setStructuredPayloadJson(figureStructuredPayloadJson(figure));
        readingElement.setSourceSpanJson(readingElementSourceSpanJson(parsedPaper, readingElement));
    }

    private void applyFormulaPayload(PaperReadingElement readingElement,
                                     ParsedPaper parsedPaper,
                                     ParsedPaperFormula formula) {
        readingElement.setContentListIndex(formula.readingOrder());
        readingElement.setParserElementId(formula.elementId());
        readingElement.setSourceObjectId(formula.formulaId());
        readingElement.setElementType("FORMULA");
        readingElement.setPageNumber(formula.pageNumber());
        readingElement.setReadingOrder(formula.readingOrder());
        readingElement.setSectionTitle(formula.sectionTitle());
        readingElement.setAssociationStatus("SELF");
        readingElement.setAttachmentRole("NONE");
        readingElement.setLocationNotCreatedReason("FORMULA_LOCATION_DEFERRED");
        readingElement.setCaptionSource("not_applicable");
        readingElement.setBodyText(combineUnique(formula.latex(), formula.contextText()));
        readingElement.setSearchableText(combineUnique(formula.latex(), formula.contextText()));
        readingElement.setParserImagePath(parserImagePath(formula.rawAttributes()));
        readingElement.setBboxJson(formula.boundingBox() == null ? null : writeJson(formula.boundingBox()));
        readingElement.setRawAttributesJson(rawAttributesJson(formula.rawAttributes()));
        readingElement.setStructuredPayloadJson(formulaStructuredPayloadJson(formula));
        readingElement.setSourceSpanJson(readingElementSourceSpanJson(parsedPaper, readingElement));
    }

    private TypedPayloadIndex typedPayloadIndex(ParsedPaper parsedPaper) {
        Map<String, ParsedPaperTable> tablesByElementId = new LinkedHashMap<>();
        Map<String, ParsedPaperTable> tablesBySignature = new LinkedHashMap<>();
        Map<String, ParsedPaperFigure> figuresByElementId = new LinkedHashMap<>();
        Map<String, ParsedPaperFigure> figuresBySignature = new LinkedHashMap<>();
        Map<String, ParsedPaperFormula> formulasByElementId = new LinkedHashMap<>();
        Map<String, ParsedPaperFormula> formulasBySignature = new LinkedHashMap<>();
        if (parsedPaper.tables() != null) {
            for (ParsedPaperTable table : parsedPaper.tables()) {
                if (table == null) {
                    continue;
                }
                putByKey(tablesByElementId, table.elementId(), table);
                putByKey(tablesBySignature, typedElementSignature("TABLE", table.pageNumber(), table.readingOrder()), table);
            }
        }
        if (parsedPaper.figures() != null) {
            for (ParsedPaperFigure figure : parsedPaper.figures()) {
                if (figure == null) {
                    continue;
                }
                putByKey(figuresByElementId, figure.elementId(), figure);
                putByKey(figuresBySignature, typedElementSignature("FIGURE", figure.pageNumber(), figure.readingOrder()), figure);
                putByKey(figuresBySignature, typedElementSignature("IMAGE", figure.pageNumber(), figure.readingOrder()), figure);
                putByKey(figuresBySignature, typedElementSignature("CHART", figure.pageNumber(), figure.readingOrder()), figure);
            }
        }
        if (parsedPaper.formulas() != null) {
            for (ParsedPaperFormula formula : parsedPaper.formulas()) {
                if (formula == null) {
                    continue;
                }
                putByKey(formulasByElementId, formula.elementId(), formula);
                putByKey(formulasBySignature, typedElementSignature("FORMULA", formula.pageNumber(), formula.readingOrder()), formula);
            }
        }
        return new TypedPayloadIndex(
                tablesByElementId,
                tablesBySignature,
                figuresByElementId,
                figuresBySignature,
                formulasByElementId,
                formulasBySignature
        );
    }

    private <T> void putByKey(Map<String, T> map, String key, T value) {
        if (!isBlank(key)) {
            map.putIfAbsent(key, value);
        }
    }

    private String typedElementSignature(String elementType, Integer pageNumber, Integer readingOrder) {
        if (isBlank(elementType) || pageNumber == null || readingOrder == null) {
            return null;
        }
        return elementType + ":" + pageNumber + ":" + readingOrder;
    }

    private String readingElementType(ParsedPaperElement element) {
        String rawType = rawType(element.rawAttributes());
        if ("page footnote".equals(rawType)) {
            return "FOOTNOTE";
        }
        if ("aside text".equals(rawType)) {
            return "ASIDE";
        }
        if ("code".equals(rawType)) {
            return "CODE";
        }
        if (element.elementType() == null) {
            return "UNKNOWN";
        }
        return switch (element.elementType()) {
            case TITLE -> "TITLE";
            case HEADING -> "HEADING";
            case TABLE -> "TABLE";
            case FIGURE, IMAGE -> "IMAGE";
            case CHART -> "CHART";
            case FORMULA -> "FORMULA";
            case LIST, LIST_ITEM -> "LIST";
            case HEADER, FOOTER -> element.elementType().name();
            case UNKNOWN -> "UNKNOWN";
            default -> element.elementType().name();
        };
    }

    private void attachOwnLocation(PaperReadingElement readingElement, PaperLocation location) {
        if (location == null) {
            return;
        }
        readingElement.setLocationRef(location.getLocationRef());
        readingElement.setLocationType(location.getLocationType());
        readingElement.setLocationNotCreatedReason(null);
    }

    private String figureElementType(ParsedPaperFigure figure) {
        String rawType = rawType(figure.rawAttributes());
        if ("chart".equals(rawType) || (figure.detectionSource() != null && figure.detectionSource().contains("CHART"))) {
            return "CHART";
        }
        return "IMAGE";
    }

    private String figureCaptionSource(ParsedPaperFigure figure) {
        Map<String, Object> rawAttributes = figure.rawAttributes();
        if (hasNonBlankRawText(rawAttributes, "image_caption")) {
            return "image_caption";
        }
        List<String> chartCaptions = rawTextValues(rawAttributes, "chart_caption");
        if (!chartCaptions.isEmpty()) {
            return chartCaptions.stream().anyMatch(this::isFullFigureCaption)
                    ? "chart_caption_full"
                    : "chart_caption_panel_only";
        }
        if (!isBlank(figure.figureText()) || !isBlank(rawText(rawAttributes, "text"))
                || !isBlank(rawText(rawAttributes, "image_text")) || !isBlank(rawText(rawAttributes, "content"))) {
            return "fallback_text";
        }
        return "no_caption";
    }

    private String rawCaptionText(Map<String, Object> rawAttributes, String captionSource) {
        if ("image_caption".equals(captionSource)) {
            return String.join(" ", rawTextValues(rawAttributes, "image_caption"));
        }
        if ("chart_caption_full".equals(captionSource) || "chart_caption_panel_only".equals(captionSource)) {
            return String.join(" ", rawTextValues(rawAttributes, "chart_caption"));
        }
        return firstNonBlank(rawText(rawAttributes, "text"), rawText(rawAttributes, "image_text"), rawText(rawAttributes, "content"));
    }

    private void attachTableCaptionFragments(List<PaperReadingElement> textElements, List<TableRetainedElement> tables) {
        List<TableRetainedElement> parents = tables.stream()
                .filter(item -> item.element() != null)
                .toList();
        for (PaperReadingElement child : textElements) {
            if (!isTableCaptionFragment(child)) {
                continue;
            }
            List<TableRetainedElement> exactCaptionCandidates = parents.stream()
                    .filter(parent -> sameKnownPage(parent.table().pageNumber(), child.getPageNumber()))
                    .filter(parent -> sameNormalizedText(parent.table().caption(), child.getSearchableText()))
                    .toList();
            if (!exactCaptionCandidates.isEmpty()) {
                attachTableFragmentToBestParent(child, exactCaptionCandidates);
                continue;
            }
            List<TableRetainedElement> nearbyCandidates = parents.stream()
                    .filter(parent -> sameKnownPage(parent.table().pageNumber(), child.getPageNumber()))
                    .filter(parent -> withinReadingOrderWindow(parent.table().readingOrder(), child.getReadingOrder(), 5))
                    .toList();
            if (nearbyCandidates.isEmpty()) {
                child.setAssociationStatus("UNATTACHED");
                child.setAttachmentRole("TABLE_CAPTION");
                child.setLocationNotCreatedReason("NOT_NAVIGATION_BOUNDARY");
                continue;
            }
            attachTableFragmentToBestParent(child, nearbyCandidates);
        }
    }

    private boolean isTableCaptionFragment(PaperReadingElement element) {
        if (!"CAPTION".equals(element.getElementType()) && !rawTypeIsCaption(element.getRawAttributesJson())) {
            return false;
        }
        String normalized = normalizeWhitespace(element.getSearchableText());
        return normalized != null
                && (normalized.matches("(?i)^table\\s*(s?\\d+|[ivxlcdm]+)[a-z]?\\b.*")
                || normalized.matches("^表\\s*\\d+.*"));
    }

    private boolean rawTypeIsCaption(String rawAttributesJson) {
        if (rawAttributesJson == null || rawAttributesJson.isBlank()) {
            return false;
        }
        try {
            Object rawType = objectMapper.readTree(rawAttributesJson).path("type").asText(null);
            String normalized = normalizeKey(rawType == null ? null : String.valueOf(rawType));
            return normalized.contains("caption");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void attachTableFragmentToBestParent(PaperReadingElement child, List<TableRetainedElement> candidates) {
        Integer childOrder = child.getReadingOrder();
        int bestDistance = candidates.stream()
                .mapToInt(parent -> readingOrderDistance(parent.table().readingOrder(), childOrder))
                .min()
                .orElse(Integer.MAX_VALUE);
        List<TableRetainedElement> nearest = candidates.stream()
                .filter(parent -> readingOrderDistance(parent.table().readingOrder(), childOrder) == bestDistance)
                .toList();
        if (nearest.size() != 1) {
            child.setAssociationStatus("AMBIGUOUS");
            child.setAttachmentRole("TABLE_CAPTION");
            child.setLocationNotCreatedReason("NOT_NAVIGATION_BOUNDARY");
            return;
        }
        TableRetainedElement parent = nearest.get(0);
        child.setAssociationStatus("ATTACHED");
        child.setAttachmentRole("TABLE_CAPTION");
        child.setParentReadingElementId(parent.element().getReadingElementId());
        child.setLocationNotCreatedReason("NOT_NAVIGATION_BOUNDARY");
    }

    private void attachPanelOnlyFigures(List<FigureRetainedElement> figures) {
        List<FigureRetainedElement> parents = figures.stream()
                .filter(item -> item.element() != null)
                .filter(item -> !"chart_caption_panel_only".equals(item.element().getCaptionSource()))
                .filter(this::isPotentialPanelParent)
                .toList();
        for (FigureRetainedElement child : figures) {
            if (!"chart_caption_panel_only".equals(child.element().getCaptionSource())) {
                continue;
            }
            List<FigureRetainedElement> containingPanelCandidates = parents.stream()
                    .filter(parent -> sameKnownPage(parent.figure().pageNumber(), child.figure().pageNumber()))
                    .filter(parent -> rawChartCaptionContainsFullCaptionAndPanelLabel(parent.figure(), child.element().getCaptionText()))
                    .toList();
            if (!containingPanelCandidates.isEmpty()) {
                attachPanelOnlyFigureToBestParent(child, containingPanelCandidates);
                continue;
            }
            List<FigureRetainedElement> candidates = parents.stream()
                    .filter(parent -> sameKnownPage(parent.figure().pageNumber(), child.figure().pageNumber()))
                    .filter(parent -> withinReadingOrderWindow(parent.figure().readingOrder(), child.figure().readingOrder(), 5))
                    .toList();
            if (candidates.isEmpty()) {
                child.element().setAssociationStatus("UNATTACHED");
                child.element().setAttachmentRole("PANEL_LABEL");
                continue;
            }
            attachPanelOnlyFigureToBestParent(child, candidates);
        }
    }

    private boolean rawChartCaptionContainsFullCaptionAndPanelLabel(ParsedPaperFigure parent, String panelLabel) {
        String normalizedPanelLabel = normalizeWhitespace(panelLabel);
        if (normalizedPanelLabel == null || normalizedPanelLabel.isBlank()) {
            return false;
        }
        List<String> captions = rawTextValues(parent.rawAttributes(), "chart_caption");
        return captions.stream().anyMatch(this::isFullFigureCaption)
                && captions.stream()
                .map(this::normalizeWhitespace)
                .filter(Objects::nonNull)
                .anyMatch(value -> value.equals(normalizedPanelLabel) || value.contains(normalizedPanelLabel));
    }

    private void attachPanelOnlyFigureToBestParent(FigureRetainedElement child, List<FigureRetainedElement> candidates) {
        Integer childOrder = child.figure().readingOrder();
        int bestDistance = candidates.stream()
                .mapToInt(parent -> readingOrderDistance(parent.figure().readingOrder(), childOrder))
                .min()
                .orElse(Integer.MAX_VALUE);
        List<FigureRetainedElement> nearest = candidates.stream()
                .filter(parent -> readingOrderDistance(parent.figure().readingOrder(), childOrder) == bestDistance)
                .toList();
        if (nearest.size() != 1) {
            child.element().setAssociationStatus("AMBIGUOUS");
            child.element().setAttachmentRole("PANEL_LABEL");
            return;
        }
        FigureRetainedElement parent = nearest.get(0);
        child.element().setAssociationStatus("ATTACHED");
        child.element().setAttachmentRole("PANEL_LABEL");
        child.element().setParentReadingElementId(parent.element().getReadingElementId());
    }

    private boolean isPotentialPanelParent(FigureRetainedElement item) {
        String captionSource = item.element().getCaptionSource();
        return "image_caption".equals(captionSource)
                || "chart_caption_full".equals(captionSource)
                || item.location() != null;
    }

    private boolean withinReadingOrderWindow(Integer parentOrder, Integer childOrder, int window) {
        return readingOrderDistance(parentOrder, childOrder) <= window;
    }

    private boolean sameKnownPage(Integer left, Integer right) {
        return left != null && right != null && left.equals(right);
    }

    private int readingOrderDistance(Integer left, Integer right) {
        if (left == null || right == null) {
            return Integer.MAX_VALUE;
        }
        return Math.abs(left - right);
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
        sourceSpan.put("pageNumberFrom", pageNumber);
        sourceSpan.put("pageNumberTo", pageNumber);
        sourceSpan.put("locationType", PaperLocationType.PAGE.name());
        sourceSpan.put("sourceObjectId", null);
        sourceSpan.put("elementIds", elementIds);
        sourceSpan.put("readingOrderFrom", orders.stream().min(Integer::compareTo).orElse(null));
        sourceSpan.put("readingOrderTo", orders.stream().max(Integer::compareTo).orElse(null));
        sourceSpan.put("bbox", bboxes.isEmpty() ? null : bboxes);
        sourceSpan.put("sourceKinds", List.copyOf(sourceKinds));
        sourceSpan.put("rawArtifactRef", null);
        return writeJson(sourceSpan);
    }

    private String sourceSpanJsonForElements(ParsedPaper paper,
                                             PaperLocationType locationType,
                                             String sourceObjectId,
                                             int pageNumberFrom,
                                             int pageNumberTo,
                                             List<ReadableElement> elements) {
        List<String> elementIds = elements.stream()
                .map(item -> item.element().elementId())
                .filter(Objects::nonNull)
                .toList();
        List<Integer> orders = elements.stream()
                .map(item -> item.element().readingOrder())
                .filter(Objects::nonNull)
                .toList();
        LinkedHashSet<String> sourceKinds = elements.stream()
                .map(item -> item.element().elementType() == null ? "UNKNOWN" : item.element().elementType().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<BoundingBox> bboxes = elements.stream()
                .map(item -> item.element().boundingBox())
                .filter(Objects::nonNull)
                .toList();

        Map<String, Object> sourceSpan = new LinkedHashMap<>();
        sourceSpan.put("parserName", paper.parserName());
        sourceSpan.put("parserVersion", paper.parserVersion());
        sourceSpan.put("pageNumber", pageNumberFrom);
        sourceSpan.put("pageNumberFrom", pageNumberFrom);
        sourceSpan.put("pageNumberTo", pageNumberTo);
        sourceSpan.put("locationType", locationType.name());
        sourceSpan.put("sourceObjectId", sourceObjectId);
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

    private String combineUnique(String... values) {
        if (values == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            String normalized = normalizeText(value);
            if (normalized.isBlank() || parts.contains(normalized)) {
                continue;
            }
            parts.add(normalized);
        }
        return String.join("\n", parts);
    }

    private boolean sameNormalizedText(String left, String right) {
        return !isBlank(left) && normalizeText(left).equals(normalizeText(right));
    }

    private String rawAttributesJson(Map<String, Object> rawAttributes) {
        return writeJson(rawAttributes == null ? Map.of() : rawAttributes);
    }

    private String tableStructuredPayloadJson(ParsedPaperTable table) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tableId", table.tableId());
        payload.put("caption", normalizeText(table.caption()));
        payload.put("tableText", normalizeText(table.tableText()));
        payload.put("tableMarkdown", normalizeText(table.tableMarkdown()));
        payload.put("rowCount", table.rowCount());
        payload.put("columnCount", table.columnCount());
        return writeJson(payload);
    }

    private String figureStructuredPayloadJson(ParsedPaperFigure figure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("figureId", figure.figureId());
        payload.put("caption", normalizeText(figure.caption()));
        payload.put("figureText", normalizeText(figure.figureText()));
        payload.put("detectionSource", figure.detectionSource());
        payload.put("confidence", figure.confidence());
        return writeJson(payload);
    }

    private String formulaStructuredPayloadJson(ParsedPaperFormula formula) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formulaId", formula.formulaId());
        payload.put("latex", normalizeText(formula.latex()));
        payload.put("contextText", normalizeText(formula.contextText()));
        return writeJson(payload);
    }

    private String rawType(Map<String, Object> rawAttributes) {
        return normalizeKey(rawText(rawAttributes, "type"));
    }

    private String parserImagePath(Map<String, Object> rawAttributes) {
        return firstNonBlank(rawText(rawAttributes, "img_path"), rawText(rawAttributes, "image_path"));
    }

    private boolean hasNonBlankRawText(Map<String, Object> rawAttributes, String field) {
        return rawTextValues(rawAttributes, field).stream().anyMatch(value -> !isBlank(value));
    }

    private String rawText(Map<String, Object> rawAttributes, String field) {
        if (rawAttributes == null) {
            return null;
        }
        Object value = rawAttributes.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private List<String> rawTextValues(Map<String, Object> rawAttributes, String field) {
        if (rawAttributes == null) {
            return List.of();
        }
        Object value = rawAttributes.get(field);
        if (value == null) {
            return List.of();
        }
        if (value instanceof String stringValue) {
            String normalized = normalizeText(stringValue);
            return normalized.isBlank() ? List.of() : List.of(normalized);
        }
        if (value instanceof Iterable<?> items) {
            List<String> values = new ArrayList<>();
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                String normalized = normalizeText(String.valueOf(item));
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
            return values;
        }
        return List.of();
    }

    private boolean isFullFigureCaption(String value) {
        String normalized = normalizeWhitespace(value);
        return normalized != null
                && (normalized.matches("(?i)^(fig\\.?|figure)\\s*(s?\\d+|[ivxlcdm]+)[a-z]?\\b.*")
                || normalized.matches("^图\\s*\\d+.*"));
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace("_", " ");
    }

    private String normalizeWhitespace(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalizeText(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String readingElementSourceSpanJson(ParsedPaper paper, PaperReadingElement element) {
        Map<String, Object> sourceSpan = new LinkedHashMap<>();
        sourceSpan.put("parserName", paper.parserName());
        sourceSpan.put("parserVersion", paper.parserVersion());
        sourceSpan.put("readingElementId", element.getReadingElementId());
        sourceSpan.put("parserElementId", element.getParserElementId());
        sourceSpan.put("sourceObjectId", element.getSourceObjectId());
        sourceSpan.put("elementType", element.getElementType());
        sourceSpan.put("contentListIndex", element.getContentListIndex());
        sourceSpan.put("pageNumber", element.getPageNumber());
        sourceSpan.put("readingOrder", element.getReadingOrder());
        sourceSpan.put("bbox", readJsonOrNull(element.getBboxJson()));
        sourceSpan.put("parserImagePath", element.getParserImagePath());
        sourceSpan.put("rawArtifactRef", null);
        return writeJson(sourceSpan);
    }

    private String readingElementLocationSourceSpanJson(ParsedPaper paper,
                                                        PaperLocationType locationType,
                                                        PaperReadingElement element) {
        Map<String, Object> sourceSpan = new LinkedHashMap<>();
        sourceSpan.put("parserName", paper.parserName());
        sourceSpan.put("parserVersion", paper.parserVersion());
        sourceSpan.put("pageNumber", element.getPageNumber());
        sourceSpan.put("pageNumberFrom", element.getPageNumber());
        sourceSpan.put("pageNumberTo", element.getPageNumber());
        sourceSpan.put("locationType", locationType.name());
        sourceSpan.put("targetReadingElementId", element.getReadingElementId());
        sourceSpan.put("sourceObjectId", element.getReadingElementId());
        sourceSpan.put("parserSourceObjectId", element.getSourceObjectId());
        sourceSpan.put("parserElementId", element.getParserElementId());
        sourceSpan.put("elementIds", isBlank(element.getParserElementId()) ? List.of() : List.of(element.getParserElementId()));
        sourceSpan.put("readingOrderFrom", element.getReadingOrder());
        sourceSpan.put("readingOrderTo", element.getReadingOrder());
        sourceSpan.put("bbox", readJsonOrNull(element.getBboxJson()));
        sourceSpan.put("sourceKinds", List.of(element.getElementType()));
        sourceSpan.put("parserImagePath", element.getParserImagePath());
        sourceSpan.put("rawArtifactRef", null);
        return writeJson(sourceSpan);
    }

    private Object readJsonOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int resolvePhysicalPageCount(Integer physicalPageCount,
                                         int parserPageCount,
                                         Map<Integer, List<ReadableElement>> byPage) {
        int maxReadablePage = byPage.keySet().stream()
                .filter(page -> page != null && page > 0)
                .max(Integer::compareTo)
                .orElse(0);
        if (physicalPageCount != null && physicalPageCount > 0) {
            return Math.max(physicalPageCount, maxReadablePage);
        }
        return maxReadablePage > 0 ? maxReadablePage : parserPageCount;
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

    private record SectionGroup(String title,
                                Integer level,
                                boolean startedByHeading,
                                List<ReadableElement> elements) {
    }

    private record FigureRetainedElement(ParsedPaperFigure figure,
                                         PaperReadingElement element,
                                         PaperLocation location) {
    }

    private record TableRetainedElement(ParsedPaperTable table,
                                        PaperReadingElement element,
                                        PaperLocation location) {
    }

    private record TypedPayloadIndex(
            Map<String, ParsedPaperTable> tablesByElementId,
            Map<String, ParsedPaperTable> tablesBySignature,
            Map<String, ParsedPaperFigure> figuresByElementId,
            Map<String, ParsedPaperFigure> figuresBySignature,
            Map<String, ParsedPaperFormula> formulasByElementId,
            Map<String, ParsedPaperFormula> formulasBySignature
    ) {
        ParsedPaperTable tableFor(ParsedPaperElement element) {
            ParsedPaperTable byElementId = tablesByElementId.get(element.elementId());
            if (byElementId != null) {
                return byElementId;
            }
            return tablesBySignature.get(signature(element));
        }

        ParsedPaperFigure figureFor(ParsedPaperElement element) {
            ParsedPaperFigure byElementId = figuresByElementId.get(element.elementId());
            if (byElementId != null) {
                return byElementId;
            }
            return figuresBySignature.get(signature(element));
        }

        ParsedPaperFormula formulaFor(ParsedPaperElement element) {
            ParsedPaperFormula byElementId = formulasByElementId.get(element.elementId());
            if (byElementId != null) {
                return byElementId;
            }
            return formulasBySignature.get(signature(element));
        }

        private String signature(ParsedPaperElement element) {
            if (element == null || element.elementType() == null
                    || element.pageNumber() == null || element.readingOrder() == null) {
                return null;
            }
            String elementType = switch (element.elementType()) {
                case FIGURE, IMAGE -> "FIGURE";
                case CHART -> "CHART";
                default -> element.elementType().name();
            };
            return elementType + ":" + element.pageNumber() + ":" + element.readingOrder();
        }
    }

    private record SectionLocationBuildResult(
            List<PaperSection> sections,
            List<PaperLocation> locations,
            int sectionCount,
            int sectionLocationCount,
            int sectionLocationNotCreatedBlankTextCount,
            int nextDisplayOrder
    ) {
    }

    private record ElementLocationBuildResult(
            List<PaperLocation> locations,
            int tableCount,
            long tableLocationCount,
            int tableLocationNotCreatedNoPageCount,
            int tableLocationNotCreatedNoIdCount,
            int tableLocationNotCreatedBlankPayloadCount,
            int figureCount,
            long figureLocationCount,
            int figureLocationNotCreatedNoPageCount,
            int figureLocationNotCreatedNoIdCount,
            int figureLocationNotCreatedBlankTextCount,
            int nextDisplayOrder
    ) {
    }

}
