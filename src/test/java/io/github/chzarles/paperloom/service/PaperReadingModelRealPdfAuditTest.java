package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.PaperLoomApplication;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.PaperPdfParser;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "paperloom.reading-model.audit", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(
        classes = PaperLoomApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:paper_reading_model_audit;MODE=MySQL;INIT=CREATE SCHEMA IF NOT EXISTS paperloom_eval;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.show-sql=false",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "elasticsearch.init.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "admin.bootstrap.enabled=false",
                "paper.bootstrap.enabled=false",
                "paper.parsing.provider=mineru",
                "paper.parsing.mineru.health-timeout-seconds=2"
        }
)
class PaperReadingModelRealPdfAuditTest {

    private static final String DEFAULT_PDFS = String.join(",",
            "data/2601.09032v1.pdf",
            "data/2408.15769.pdf",
            "data/2503.16416.pdf",
            "data/2412.08972.pdf",
            "data/2401.13178.pdf",
            "data/2503.05244.pdf",
            "data/2308.03688.pdf",
            "data/2505.04620.pdf"
    );

    @Autowired
    private PaperPdfParser paperPdfParser;

    @Autowired
    private PaperReadingModelService paperReadingModelService;

    @Autowired
    private PaperVisualAssetService paperVisualAssetService;

    @Autowired
    private PaperReadingModelRepository modelRepository;

    @Autowired
    private PaperPageRepository pageRepository;

    @Autowired
    private PaperSectionRepository sectionRepository;

    @Autowired
    private PaperLocationRepository locationRepository;

    @Autowired
    private PaperReadingElementRepository readingElementRepository;

    @Autowired
    private PaperVisualAssetRepository visualAssetRepository;

    @Autowired
    private PaperReadingElementSearchService readingElementSearchService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void realDataPdfsSatisfyReadingModelInvariantsAndEmitAuditJsonl() throws Exception {
        Path output = Path.of(System.getProperty(
                "paperloom.reading-model.audit.output",
                "target/paper-reading-model-real-pdf-audit/summary.jsonl"
        ));
        Files.createDirectories(output.getParent());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Path pdf : pdfs()) {
            rows.add(auditSafely(pdf));
            Files.write(output, rows.stream()
                    .map(this::writeJson)
                    .toList());
        }

        assertTrue(rows.stream().allMatch(row -> "PASS".equals(row.get("status"))), () -> writeJson(rows));
    }

    private Map<String, Object> auditSafely(Path pdf) {
        long startedAt = System.nanoTime();
        try {
            return audit(pdf, startedAt);
        } catch (Exception | AssertionError e) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", "FAIL");
            row.put("pdf", pdf.toString());
            row.put("sizeBytes", sizeOrNull(pdf));
            row.put("durationSeconds", durationSecondsSince(startedAt));
            row.put("failureClass", e.getClass().getName());
            row.put("failureMessage", e.getMessage());
            return row;
        }
    }

    private Map<String, Object> audit(Path pdf, long startedAt) throws Exception {
        assertTrue(Files.exists(pdf), "missing audit PDF: " + pdf);
        byte[] pdfBytes = Files.readAllBytes(pdf);
        int pdfPageCount = pdfPageCount(pdf);
        ParsedPaper parsedPaper;
        try (InputStream inputStream = Files.newInputStream(pdf)) {
            parsedPaper = paperPdfParser.parse(inputStream, pdf.getFileName().toString());
        }
        String paperId = paperIdFor(pdf);
        PaperReadingModel model = paperReadingModelService.replaceFromParsedPaper(
                paperId,
                parsedPaper,
                pdfPageCount,
                "audit-user",
                "audit-org",
                false
        );
        assertEquals(PaperReadingModelStatus.READING_MODEL_READY, model.getModelStatus(), "model not ready for " + pdf);
        assertTrue(model.isCurrent(), "model not current for " + pdf);
        paperVisualAssetService.replaceVisualAssets(
                paperId,
                model.getModelVersion(),
                pdfBytes,
                parsedPaper,
                "audit-user",
                "audit-org",
                false
        );

        List<PaperPage> pages = pageRepository.findByPaperIdAndModelVersionOrderByPageNumberAsc(
                paperId,
                model.getModelVersion()
        );
        List<PaperLocation> locations = locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                paperId,
                model.getModelVersion()
        );
        List<PaperSection> sections = sectionRepository.findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc(
                paperId,
                model.getModelVersion()
        );
        List<PaperReadingElement> readingElements = readingElementRepository.findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(
                paperId,
                model.getModelVersion()
        );
        List<PaperVisualAsset> visualAssets = visualAssetRepository.findByPaperId(paperId).stream()
                .filter(asset -> model.getModelVersion().equals(asset.getModelVersion()))
                .toList();
        PaperReadingModel current = modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId).orElseThrow();

        JsonNode diagnostics = objectMapper.readTree(model.getDiagnosticsJson());
        validateReadableModel(pdf, parsedPaper, diagnostics, pages, sections, locations);
        validateModelSummary(pdf, parsedPaper, model, current, pages);
        assertEquals(safeSize(parsedPaper.elements()), diagnostics.path("contentListItemCount").asInt(),
                "content-list diagnostic mismatch for " + pdf);
        assertEquals(diagnostics.path("contentListItemCount").asInt(), readingElements.size(),
                "content-list item count must equal retained reading element count for " + pdf);
        validateRetainedElements(pdf, parsedPaper, locations, readingElements);
        VisualEvidenceSummary visualEvidence = validateVisualEvidence(pdf, readingElements, visualAssets);
        RouteSummary routeSummary = validateRoutes(pdf, locations, readingElements);
        validateRetainedSearchInputs(pdf, paperId, model.getModelVersion(), readingElements);

        long pageLocationCount = countLocations(locations, PaperLocationType.PAGE);
        long sectionLocationCount = countLocations(locations, PaperLocationType.SECTION);
        long tableLocationCount = countLocations(locations, PaperLocationType.TABLE);
        long figureLocationCount = countLocations(locations, PaperLocationType.FIGURE);
        long retainedPanelOnlyChartCount = countReadingElementsByCaptionSource(readingElements, "chart_caption_panel_only");
        long attachedPanelOnlyChartCount = readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource()))
                .filter(element -> "ATTACHED".equals(element.getAssociationStatus()))
                .count();
        long ambiguousPanelOnlyChartCount = readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource()))
                .filter(element -> "AMBIGUOUS".equals(element.getAssociationStatus()))
                .count();
        long unattachedPanelOnlyChartCount = readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource()))
                .filter(element -> "UNATTACHED".equals(element.getAssociationStatus()))
                .count();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", "PASS");
        row.put("pdf", pdf.toString());
        row.put("paperId", paperId);
        row.put("sizeBytes", Files.size(pdf));
        row.put("durationSeconds", durationSecondsSince(startedAt));
        row.put("parserName", parsedPaper.parserName());
        row.put("parserVersion", parsedPaper.parserVersion());
        row.put("modelStatus", model.getModelStatus().name());
        row.put("modelVersion", model.getModelVersion());
        row.put("currentModelVersion", current.getModelVersion());
        row.put("pdfRendererPageCount", pdfPageCount);
        row.put("parserMetadataPageCount", parsedPaper.metadata() == null ? null : parsedPaper.metadata().pageCount());
        row.put("paperPageCount", pages.size());
        row.put("elementCount", safeSize(parsedPaper.elements()));
        row.put("tableCount", safeSize(parsedPaper.tables()));
        row.put("figureCount", safeSize(parsedPaper.figures()));
        row.put("formulaCount", safeSize(parsedPaper.formulas()));
        row.put("artifactCount", safeSize(parsedPaper.artifacts()));
        row.put("contentListItemCount", diagnostics.path("contentListItemCount").asInt());
        row.put("readingElementCount", readingElements.size());
        row.put("contentListImageReferenceCount", visualEvidence.contentListImageReferenceCount());
        row.put("parserImageAvailableCount", visualEvidence.parserImageAvailableCount());
        row.put("parserImageMissingCount", visualEvidence.parserImageMissingCount());
        row.put("parserImageStorageFailedCount", visualEvidence.parserImageStorageFailedCount());
        row.put("parserImageRepresentedCount", visualEvidence.parserImageRepresentedCount());
        row.put("visualOnlyElementCount", visualEvidence.visualOnlyElementCount());
        row.put("visualOnlyElementWithAssetOrGapCount", visualEvidence.visualOnlyElementWithAssetOrGapCount());
        row.put("visualAssetCount", visualAssets.size());
        row.put("routeOwnLocationCount", routeSummary.ownLocationCount());
        row.put("routeParentLocationCount", routeSummary.parentLocationCount());
        row.put("routePageLocationCount", routeSummary.pageLocationCount());
        row.put("routeUnresolvedCount", routeSummary.unresolvedCount());
        row.put("readablePageCount", pages.stream()
                .filter(page -> PaperPage.TEXT_STATUS_READABLE.equals(page.getTextStatus()))
                .count());
        row.put("textlessPageCount", pages.stream()
                .filter(page -> PaperPage.TEXT_STATUS_TEXTLESS.equals(page.getTextStatus()))
                .count());
        row.put("pageLocationCount", pageLocationCount);
        row.put("sectionCount", sections.size());
        row.put("sectionLocationCount", sectionLocationCount);
        row.put("tableLocationCount", tableLocationCount);
        row.put("figureLocationCount", figureLocationCount);
        row.put("structuredLocationCount", sectionLocationCount + tableLocationCount + figureLocationCount);
        row.put("locationCount", locations.size());
        row.put("retainedElementCount", readingElements.size());
        row.put("retainedElementsWithSearchableTextCount", readingElements.stream()
                .filter(element -> element.getSearchableText() != null && !element.getSearchableText().isBlank())
                .count());
        row.put("retainedTableElementCount", countReadingElementsByType(readingElements, "TABLE"));
        row.put("retainedFigureElementCount", countReadingElementsByType(readingElements, "IMAGE")
                + countReadingElementsByType(readingElements, "CHART"));
        row.put("retainedFormulaElementCount", countReadingElementsByType(readingElements, "FORMULA"));
        row.put("retainedPanelOnlyChartCount", retainedPanelOnlyChartCount);
        row.put("attachedPanelOnlyChartCount", attachedPanelOnlyChartCount);
        row.put("ambiguousPanelOnlyChartCount", ambiguousPanelOnlyChartCount);
        row.put("unattachedPanelOnlyChartCount", unattachedPanelOnlyChartCount);
        row.put("tableLocationNotCreatedCount", safeSize(parsedPaper.tables()) - tableLocationCount);
        row.put("figureLocationNotCreatedCount", safeSize(parsedPaper.figures()) - figureLocationCount);
        row.put("formulaLocationDeferredCount", safeSize(parsedPaper.formulas()));
        row.put("panelOnlyChartSearchableExamples", readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource()))
                .map(PaperReadingElement::getSearchableText)
                .filter(text -> text != null && !text.isBlank())
                .limit(5)
                .toList());
        row.put("readableCharCount", pages.stream()
                .mapToInt(page -> page.getCharCount() == null ? 0 : page.getCharCount())
                .sum());
        row.put("pageTextElementsOmittedNoPageCount", diagnostics.path("pageTextElementsOmittedNoPageCount").asInt());
        row.put("pageTextElementsOmittedBlankTextCount", diagnostics.path("pageTextElementsOmittedBlankTextCount").asInt());
        row.put("tableLocationNotCreatedNoPageCount", diagnostics.path("tableLocationNotCreatedNoPageCount").asInt());
        row.put("tableLocationNotCreatedNoIdCount", diagnostics.path("tableLocationNotCreatedNoIdCount").asInt());
        row.put("tableLocationNotCreatedBlankPayloadCount", diagnostics.path("tableLocationNotCreatedBlankPayloadCount").asInt());
        row.put("figureLocationNotCreatedNoPageCount", diagnostics.path("figureLocationNotCreatedNoPageCount").asInt());
        row.put("figureLocationNotCreatedNoIdCount", diagnostics.path("figureLocationNotCreatedNoIdCount").asInt());
        row.put("figureLocationNotCreatedBlankTextCount", diagnostics.path("figureLocationNotCreatedBlankTextCount").asInt());
        row.put("pagesWithoutText", diagnostics.path("pagesWithoutText").asInt());
        row.put("hasAnyBbox", diagnostics.path("hasAnyBbox").asBoolean());
        row.put("firstPageChars", pages.get(0).getCharCount());
        row.put("lastPageNumber", pages.get(pages.size() - 1).getPageNumber());
        return row;
    }

    private VisualEvidenceSummary validateVisualEvidence(Path pdf,
                                                         List<PaperReadingElement> readingElements,
                                                         List<PaperVisualAsset> visualAssets) {
        List<PaperReadingElement> imageReferencedElements = readingElements.stream()
                .filter(element -> !isBlank(element.getParserImagePath()))
                .toList();
        List<PaperVisualAsset> parserImageAssets = visualAssets.stream()
                .filter(asset -> PaperVisualAsset.TYPE_PARSER_IMAGE.equals(asset.getAssetType()))
                .toList();
        long parserImageAvailableCount = parserImageAssets.stream()
                .filter(asset -> PaperVisualAsset.STATUS_AVAILABLE.equals(asset.getAssetStatus()))
                .count();
        long parserImageMissingCount = parserImageAssets.stream()
                .filter(asset -> PaperVisualAsset.STATUS_MISSING_IN_ARTIFACT.equals(asset.getAssetStatus()))
                .count();
        long parserImageStorageFailedCount = parserImageAssets.stream()
                .filter(asset -> PaperVisualAsset.STATUS_STORAGE_FAILED.equals(asset.getAssetStatus()))
                .count();

        for (PaperReadingElement element : imageReferencedElements) {
            List<PaperVisualAsset> matches = parserImageAssets.stream()
                    .filter(asset -> element.getReadingElementId().equals(asset.getReadingElementId()))
                    .toList();
            assertEquals(1, matches.size(), "parser image ref not represented exactly once for " + pdf);
            PaperVisualAsset asset = matches.get(0);
            assertEquals(element.getParserImagePath(), asset.getParserImagePath(),
                    "parser image path mismatch for " + pdf);
            assertVisualAssetStatusCoherent(pdf, asset);
        }

        Set<String> readingElementIdsWithAssets = visualAssets.stream()
                .map(PaperVisualAsset::getReadingElementId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        List<PaperReadingElement> visualOnlyElements = readingElements.stream()
                .filter(element -> isBlank(element.getSearchableText()))
                .filter(element -> !isBlank(element.getParserImagePath()) || !isBlank(element.getBboxJson()))
                .filter(element -> "TABLE".equals(element.getElementType())
                        || "IMAGE".equals(element.getElementType())
                        || "CHART".equals(element.getElementType()))
                .toList();
        long visualOnlyWithAssetOrGap = visualOnlyElements.stream()
                .filter(element -> readingElementIdsWithAssets.contains(element.getReadingElementId()))
                .count();
        assertEquals(visualOnlyElements.size(), visualOnlyWithAssetOrGap,
                "visual-only table/figure/chart elements must have an asset or visual gap for " + pdf);
        assertEquals(imageReferencedElements.size(),
                parserImageAvailableCount + parserImageMissingCount + parserImageStorageFailedCount,
                "parser image refs must be represented by available/missing/storage-failed rows for " + pdf);

        return new VisualEvidenceSummary(
                imageReferencedElements.size(),
                parserImageAvailableCount,
                parserImageMissingCount,
                parserImageStorageFailedCount,
                parserImageAvailableCount + parserImageMissingCount + parserImageStorageFailedCount,
                visualOnlyElements.size(),
                visualOnlyWithAssetOrGap
        );
    }

    private RouteSummary validateRoutes(Path pdf,
                                        List<PaperLocation> locations,
                                        List<PaperReadingElement> readingElements) {
        Set<String> locationRefs = locations.stream()
                .map(PaperLocation::getLocationRef)
                .filter(ref -> ref != null && !ref.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> pageLocationPages = locations.stream()
                .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                .map(PaperLocation::getPageNumber)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, PaperReadingElement> elementsByReadingElementId = readingElements.stream()
                .filter(element -> !isBlank(element.getReadingElementId()))
                .collect(java.util.stream.Collectors.toMap(
                        PaperReadingElement::getReadingElementId,
                        element -> element,
                        (left, right) -> left
                ));

        int own = 0;
        int parent = 0;
        int page = 0;
        int unresolved = 0;
        for (PaperReadingElement element : readingElements) {
            if (element.getPageNumber() == null || element.getPageNumber() <= 0) {
                continue;
            }
            if (!isBlank(element.getLocationRef()) && locationRefs.contains(element.getLocationRef())) {
                own++;
                continue;
            }
            PaperReadingElement parentElement = isBlank(element.getParentReadingElementId())
                    ? null
                    : elementsByReadingElementId.get(element.getParentReadingElementId());
            if (parentElement != null
                    && !isBlank(parentElement.getLocationRef())
                    && locationRefs.contains(parentElement.getLocationRef())) {
                parent++;
                continue;
            }
            if (pageLocationPages.contains(element.getPageNumber())) {
                page++;
                continue;
            }
            unresolved++;
        }
        assertEquals(0, unresolved, "page-numbered retained elements must route by own, parent, or PAGE location for " + pdf);
        return new RouteSummary(own, parent, page, unresolved);
    }

    private void assertVisualAssetStatusCoherent(Path pdf, PaperVisualAsset asset) {
        assertTrue(asset.getAssetStatus() != null && !asset.getAssetStatus().isBlank(),
                "visual asset missing status for " + pdf);
        if (PaperVisualAsset.STATUS_AVAILABLE.equals(asset.getAssetStatus())) {
            assertTrue(asset.getObjectKey() != null && !asset.getObjectKey().isBlank(),
                    "available visual asset missing object key for " + pdf);
        } else {
            assertTrue(asset.getObjectKey() == null || asset.getObjectKey().isBlank(),
                    "failed/missing visual asset should not have object key for " + pdf);
            assertTrue(asset.getFailureReason() != null && !asset.getFailureReason().isBlank(),
                    "failed/missing visual asset missing reason for " + pdf);
        }
    }

    private void validateRetainedElements(Path pdf,
                                          ParsedPaper parsedPaper,
                                          List<PaperLocation> locations,
                                          List<PaperReadingElement> readingElements) throws Exception {
        assertTrue(readingElements.size() >= safeSize(parsedPaper.elements()),
                "retained element count lower than parsed element count for " + pdf);
        assertEquals(safeSize(parsedPaper.tables()), countReadingElementsByType(readingElements, "TABLE"),
                "retained table count mismatch for " + pdf);
        assertEquals(safeSize(parsedPaper.figures()),
                countReadingElementsByType(readingElements, "IMAGE") + countReadingElementsByType(readingElements, "CHART"),
                "retained figure/chart count mismatch for " + pdf);
        assertEquals(safeSize(parsedPaper.formulas()), countReadingElementsByType(readingElements, "FORMULA"),
                "retained formula count mismatch for " + pdf);

        Set<String> retainedReadingElementIds = readingElements.stream()
                .map(PaperReadingElement::getReadingElementId)
                .filter(readingElementId -> readingElementId != null && !readingElementId.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, PaperReadingElement> retainedElementsById = readingElements.stream()
                .filter(element -> element.getReadingElementId() != null && !element.getReadingElementId().isBlank())
                .collect(java.util.stream.Collectors.toMap(PaperReadingElement::getReadingElementId, element -> element, (left, right) -> left));
        Set<String> locationRefs = locations.stream()
                .map(PaperLocation::getLocationRef)
                .filter(locationRef -> locationRef != null && !locationRef.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> pageLocationPages = locations.stream()
                .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                .map(PaperLocation::getPageNumber)
                .collect(java.util.stream.Collectors.toSet());
        Map<Integer, String> pageLocationRefsByPage = locations.stream()
                .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                .collect(java.util.stream.Collectors.toMap(PaperLocation::getPageNumber, PaperLocation::getLocationRef));
        for (PaperLocation location : locations) {
            if (location.getLocationType() == PaperLocationType.TABLE || location.getLocationType() == PaperLocationType.FIGURE) {
                assertTrue(retainedReadingElementIds.contains(location.getSourceObjectId()),
                        "typed location missing retained element for " + pdf);
            }
        }

        for (PaperReadingElement element : readingElements) {
            assertTrue(element.getReadingElementId() != null && !element.getReadingElementId().isBlank(),
                    "missing retained element id for " + pdf);
            assertTrue(element.getAssociationStatus() != null && !element.getAssociationStatus().isBlank(),
                    "missing association status for " + pdf);
            assertTrue(element.getRawAttributesJson() != null && !element.getRawAttributesJson().isBlank(),
                    "missing raw attributes for " + pdf);
            objectMapper.readTree(element.getRawAttributesJson());
            boolean hasRetainedText = (element.getCaptionText() != null && !element.getCaptionText().isBlank())
                    || (element.getBodyText() != null && !element.getBodyText().isBlank());
            if (hasRetainedText) {
                assertTrue(element.getSearchableText() != null && !element.getSearchableText().isBlank(),
                        "retained text not searchable for " + pdf);
            }
            if ("ATTACHED".equals(element.getAssociationStatus())) {
                assertTrue(element.getParentReadingElementId() != null && !element.getParentReadingElementId().isBlank(),
                        "attached element missing parent element id for " + pdf);
                assertTrue(retainedReadingElementIds.contains(element.getParentReadingElementId()),
                        "attached element parent id missing from retained elements for " + pdf);
                PaperReadingElement parent = retainedElementsById.get(element.getParentReadingElementId());
                if (parent != null && parent.getLocationRef() != null && !parent.getLocationRef().isBlank()) {
                    assertTrue(locationRefs.contains(parent.getLocationRef()),
                            "attached element parent location ref missing from locations for " + pdf);
                }
            }
            if (element.getLocationRef() != null && !element.getLocationRef().isBlank()) {
                assertTrue(locationRefs.contains(element.getLocationRef()),
                        "retained element own location ref missing from locations for " + pdf);
            }
            String parentLocationRef = parentLocationRef(element, retainedElementsById);
            if ((element.getLocationRef() == null || element.getLocationRef().isBlank())
                    && (parentLocationRef == null || parentLocationRef.isBlank())
                    && element.getPageNumber() != null
                    && pageLocationPages.contains(element.getPageNumber())) {
                assertFalse(pageLocationRefsByPage.get(element.getPageNumber()).isBlank(),
                        "non-location retained element cannot fall back to containing PAGE location for " + pdf);
            }
        }
    }

    private void validateRetainedSearchInputs(Path pdf,
                                              String paperId,
                                              String modelVersion,
                                              List<PaperReadingElement> readingElements) {
        readingElements.stream()
                .filter(element -> "chart_caption_panel_only".equals(element.getCaptionSource()))
                .filter(element -> element.getSearchableText() != null && !element.getSearchableText().isBlank())
                .limit(5)
                .forEach(element -> assertElementSearchableAndRouted(pdf, paperId, modelVersion, element));
        readingElements.stream()
                .filter(element -> "TABLE".equals(element.getElementType()))
                .filter(element -> element.getSearchableText() != null && !element.getSearchableText().isBlank())
                .findFirst()
                .ifPresent(element -> assertElementSearchableAndRouted(pdf, paperId, modelVersion, element));
        readingElements.stream()
                .filter(element -> "FORMULA".equals(element.getElementType()))
                .filter(element -> element.getSearchableText() != null && !element.getSearchableText().isBlank())
                .findFirst()
                .ifPresent(element -> assertElementSearchableAndRouted(pdf, paperId, modelVersion, element));

        if ("2412.08972.pdf".equals(pdf.getFileName().toString())) {
            assertSearchablePanelExample(pdf, paperId, modelVersion, "(a) Recall", "Recall");
            assertSearchablePanelExample(pdf, paperId, modelVersion, "(b) Precision", "Precision");
        }
    }

    private void assertElementSearchableAndRouted(Path pdf,
                                                  String paperId,
                                                  String modelVersion,
                                                  PaperReadingElement expectedElement) {
        String queryText = searchProbe(expectedElement.getSearchableText());
        assertFalse(queryText.isBlank(), "blank retained search probe for " + pdf);
        PaperReadingElementSearchResult match = readingElementSearchService.search(paperId, modelVersion, queryText)
                .stream()
                .filter(result -> expectedElement.getReadingElementId().equals(result.element().getReadingElementId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("retained element not found by search for " + pdf
                        + ": " + expectedElement.getReadingElementId()));
        if (expectedElement.getLocationRef() != null && !expectedElement.getLocationRef().isBlank()) {
            assertEquals("OWN_LOCATION", match.routingSource(), "own-location search routing mismatch for " + pdf);
        } else if ("PARENT_LOCATION".equals(match.routingSource())) {
            assertEquals("PARENT_LOCATION", match.routingSource(), "parent-location search routing mismatch for " + pdf);
        } else if (expectedElement.getPageNumber() != null) {
            assertEquals(PaperLocationType.PAGE, match.routedLocationType(), "page fallback search routing mismatch for " + pdf);
        }
    }

    private void assertSearchablePanelExample(Path pdf,
                                              String paperId,
                                              String modelVersion,
                                              String expectedText,
                                              String queryText) {
        PaperReadingElementSearchResult match = readingElementSearchService.search(paperId, modelVersion, queryText)
                .stream()
                .filter(result -> expectedText.equals(result.element().getSearchableText()))
                .filter(result -> "chart_caption_panel_only".equals(result.element().getCaptionSource()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing searchable panel example " + expectedText + " for " + pdf));
        if ("ATTACHED".equals(match.element().getAssociationStatus())
                && "PARENT_LOCATION".equals(match.routingSource())) {
            assertEquals("PARENT_LOCATION", match.routingSource(), "panel example should route to parent figure for " + pdf);
            assertEquals(PaperLocationType.FIGURE, match.routedLocationType(), "panel example parent route type mismatch for " + pdf);
        }
    }

    private String parentLocationRef(PaperReadingElement element, Map<String, PaperReadingElement> retainedElementsById) {
        if (element == null || element.getParentReadingElementId() == null || element.getParentReadingElementId().isBlank()) {
            return null;
        }
        PaperReadingElement parent = retainedElementsById.get(element.getParentReadingElementId());
        return parent == null ? null : parent.getLocationRef();
    }

    private String searchProbe(String searchableText) {
        if (searchableText == null) {
            return "";
        }
        String trimmed = searchableText.trim();
        if (trimmed.length() <= 64) {
            return trimmed;
        }
        return trimmed.substring(0, 64).trim();
    }

    private void validateModelSummary(Path pdf,
                                      ParsedPaper parsedPaper,
                                      PaperReadingModel model,
                                      PaperReadingModel current,
                                      List<PaperPage> pages) {
        int readableCharCount = pages.stream()
                .mapToInt(page -> page.getCharCount() == null ? 0 : page.getCharCount())
                .sum();
        int readablePageCount = (int) pages.stream()
                .filter(page -> PaperPage.TEXT_STATUS_READABLE.equals(page.getTextStatus()))
                .count();
        assertEquals(model.getModelVersion(), current.getModelVersion(), "current model mismatch for " + pdf);
        assertEquals(parsedPaper.parserName(), model.getParserName(), "parser name mismatch for " + pdf);
        assertEquals(parsedPaper.parserVersion(), model.getParserVersion(), "parser version mismatch for " + pdf);
        assertEquals(pages.size(), model.getPageCount(), "physical page count mismatch for " + pdf);
        assertEquals(readablePageCount, model.getReadablePageCount(), "readable page count mismatch for " + pdf);
        assertEquals(readableCharCount, model.getReadableCharCount(), "readable char count mismatch for " + pdf);
    }

    private void validateReadableModel(Path pdf,
                                       ParsedPaper parsedPaper,
                                       JsonNode diagnostics,
                                       List<PaperPage> pages,
                                       List<PaperSection> sections,
                                       List<PaperLocation> locations) throws Exception {
        assertFalse(pages.isEmpty(), "no physical pages for " + pdf);
        List<PaperLocation> pageLocations = locationsOfType(locations, PaperLocationType.PAGE);
        List<PaperLocation> sectionLocations = locationsOfType(locations, PaperLocationType.SECTION);
        List<PaperLocation> tableLocations = locationsOfType(locations, PaperLocationType.TABLE);
        List<PaperLocation> figureLocations = locationsOfType(locations, PaperLocationType.FIGURE);
        assertEquals(pages.size(), pageLocations.size(), "page/location mismatch for " + pdf);
        assertEquals(sections.size(), sectionLocations.size(), "section/location mismatch for " + pdf);
        assertTrue(tableLocations.size() <= safeSize(parsedPaper.tables()), "too many TABLE locations for " + pdf);
        assertTrue(figureLocations.size() <= safeSize(parsedPaper.figures()), "too many FIGURE locations for " + pdf);
        assertEquals(pageLocations.size(), diagnostics.path("pageLocationCount").asLong(), "diagnostic PAGE count mismatch for " + pdf);
        assertEquals(sectionLocations.size(), diagnostics.path("sectionLocationCount").asLong(), "diagnostic SECTION count mismatch for " + pdf);
        assertEquals(tableLocations.size(), diagnostics.path("tableLocationCount").asLong(), "diagnostic TABLE count mismatch for " + pdf);
        assertEquals(figureLocations.size(), diagnostics.path("figureLocationCount").asLong(), "diagnostic FIGURE count mismatch for " + pdf);

        Set<Integer> locationPages = new HashSet<>();
        Set<String> locationRefs = new HashSet<>();
        for (PaperLocation location : locations) {
            assertTrue(location.getLocationRef().startsWith(prefixFor(location.getLocationType())), "bad location ref for " + pdf);
            assertTrue(locationRefs.add(location.getLocationRef()), "duplicate location ref for " + pdf);
            if (location.getLocationType() == PaperLocationType.PAGE) {
                locationPages.add(location.getPageNumber());
            } else {
                assertTrue(location.getSourceObjectId() != null && !location.getSourceObjectId().isBlank(),
                        "missing source object id for " + pdf);
            }
            JsonNode sourceSpan = objectMapper.readTree(location.getSourceSpanJson());
            assertEquals(location.getLocationType().name(), sourceSpan.path("locationType").asText(),
                    "location type source span mismatch for " + pdf);
        }

        for (PaperPage page : pages) {
            assertTrue(locationPages.contains(page.getPageNumber()), "missing PAGE location for " + pdf);
            JsonNode sourceSpan = objectMapper.readTree(page.getSourceSpanJson());
            assertEquals(page.getPageNumber().intValue(), sourceSpan.path("pageNumber").asInt(),
                    "source span page mismatch for " + pdf);
            if (PaperPage.TEXT_STATUS_READABLE.equals(page.getTextStatus())) {
                assertFalse(page.getPageText().isBlank(), "readable page has blank text for " + pdf);
                assertFalse(sourceSpan.path("elementIds").isEmpty(), "missing source element ids for " + pdf);
                assertFalse(sourceSpan.path("sourceKinds").isEmpty(), "missing source kinds for " + pdf);
            } else {
                assertEquals("", page.getPageText(), "textless page should have empty page text for " + pdf);
            }
        }
    }

    private int pdfPageCount(Path pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            return document.getNumberOfPages();
        }
    }

    private List<PaperLocation> locationsOfType(List<PaperLocation> locations, PaperLocationType type) {
        return locations.stream()
                .filter(location -> location.getLocationType() == type)
                .toList();
    }

    private long countLocations(List<PaperLocation> locations, PaperLocationType type) {
        return locations.stream()
                .filter(location -> location.getLocationType() == type)
                .count();
    }

    private long countReadingElementsByType(List<PaperReadingElement> readingElements, String type) {
        return readingElements.stream()
                .filter(element -> type.equals(element.getElementType()))
                .count();
    }

    private long countReadingElementsByCaptionSource(List<PaperReadingElement> readingElements, String captionSource) {
        return readingElements.stream()
                .filter(element -> captionSource.equals(element.getCaptionSource()))
                .count();
    }

    private String prefixFor(PaperLocationType type) {
        return switch (type) {
            case PAGE -> "page_ref_";
            case SECTION -> "section_ref_";
            case TABLE -> "table_ref_";
            case FIGURE -> "figure_ref_";
        };
    }

    private String paperIdFor(Path pdf) {
        String baseName = pdf.getFileName().toString()
                .replace(".pdf", "")
                .replaceAll("[^A-Za-z0-9]", "");
        return ("audit" + baseName).substring(0, Math.min(32, ("audit" + baseName).length()));
    }

    private List<Path> pdfs() {
        String value = System.getProperty("paperloom.reading-model.audit.pdfs", DEFAULT_PDFS);
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(Path::of)
                .toList();
    }

    private int safeSize(List<?> items) {
        return items == null ? 0 : items.size();
    }

    private Long sizeOrNull(Path pdf) {
        try {
            return Files.exists(pdf) ? Files.size(pdf) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private double durationSecondsSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000_000.0;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write audit JSON", e);
        }
    }

    private record VisualEvidenceSummary(
            long contentListImageReferenceCount,
            long parserImageAvailableCount,
            long parserImageMissingCount,
            long parserImageStorageFailedCount,
            long parserImageRepresentedCount,
            long visualOnlyElementCount,
            long visualOnlyElementWithAssetOrGapCount
    ) {
    }

    private record RouteSummary(
            long ownLocationCount,
            long parentLocationCount,
            long pageLocationCount,
            long unresolvedCount
    ) {
    }
}
