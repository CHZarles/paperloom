package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperPassage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperPassageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CanonicalReadingLocationServiceTest {

    private final PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
    private final PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
    private final PaperPageRepository pageRepository = mock(PaperPageRepository.class);
    private final PaperPassageRepository passageRepository = mock(PaperPassageRepository.class);
    private final PaperReadingElementRepository elementRepository = mock(PaperReadingElementRepository.class);
    private final PaperSectionRepository sectionRepository = mock(PaperSectionRepository.class);
    private final PaperRepository paperRepository = mock(PaperRepository.class);
    private final PaperVisualAssetRepository visualAssetRepository = mock(PaperVisualAssetRepository.class);
    private CanonicalReadingLocationService service;

    @BeforeEach
    void setUp() {
        service = new CanonicalReadingLocationService(
                locationRepository,
                modelRepository,
                pageRepository,
                sectionRepository,
                passageRepository,
                elementRepository,
                paperRepository,
                visualAssetRepository
        );
        when(paperRepository.findByPaperIdIn(List.of("paper-a"))).thenReturn(List.of());
    }

    @Test
    void staleLocationFromOldModelCannotBeRead() {
        PaperLocation location = pageLocation("rm-old");
        when(locationRepository.findByLocationRefIn(List.of("location_ref_a"))).thenReturn(List.of(location));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));

        CanonicalReadingLocationService.ReadBatch result = service.read(
                List.of("location_ref_a"), List.of("paper-a"));

        assertEquals(List.of(), result.items());
        assertEquals(List.of("location_ref_a"), result.missingLocationRefs());
        verifyNoInteractions(pageRepository);
    }

    @Test
    void currentPageLocationReturnsExactCanonicalContent() {
        PaperLocation location = pageLocation("rm-current");
        PaperPage page = new PaperPage();
        page.setPaperId("paper-a");
        page.setModelVersion("rm-current");
        page.setPageNumber(2);
        page.setPageText("Exact canonical page content.");
        page.setSourceSpanJson("{\"spans\":[]}");
        page.setParserName("mineru");
        page.setParserVersion("1");
        when(locationRepository.findByLocationRefIn(List.of("location_ref_a"))).thenReturn(List.of(location));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));
        when(pageRepository.findByPaperIdAndModelVersionAndPageNumberIn("paper-a", "rm-current", List.of(2)))
                .thenReturn(List.of(page));

        CanonicalReadingLocationService.ReadBatch result = service.read(
                List.of("location_ref_a"), List.of("paper-a"));

        assertEquals(1, result.items().size());
        assertEquals("Exact canonical page content.", result.items().get(0).spanText());
        assertFalse(result.items().get(0).pageScreenshotAvailable());
        assertFalse(result.items().get(0).pdfEvidenceAvailable());
        assertEquals(List.of("pdf_page_visual_evidence_unavailable"), result.items().get(0).assetWarnings());
        assertEquals(List.of(), result.missingLocationRefs());
    }

    @Test
    void currentPageLocationReportsAvailablePdfVisualEvidence() {
        PaperLocation location = pageLocation("rm-current");
        PaperPage page = new PaperPage();
        page.setPaperId("paper-a");
        page.setModelVersion("rm-current");
        page.setPageNumber(2);
        page.setPageText("Exact canonical page content.");
        page.setSourceSpanJson("{\"spans\":[]}");
        page.setParserName("mineru");
        page.setParserVersion("1");
        PaperVisualAsset asset = new PaperVisualAsset();
        asset.setAssetStatus(PaperVisualAsset.STATUS_AVAILABLE);
        when(locationRepository.findByLocationRefIn(List.of("location_ref_a"))).thenReturn(List.of(location));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));
        when(pageRepository.findByPaperIdAndModelVersionAndPageNumberIn("paper-a", "rm-current", List.of(2)))
                .thenReturn(List.of(page));
        when(visualAssetRepository.findFirstByPaperIdAndAssetTypeAndPageNumber(
                "paper-a", PaperVisualAsset.TYPE_PAGE_SCREENSHOT, 2))
                .thenReturn(Optional.of(asset));

        CanonicalReadingLocationService.ReadBatch result = service.read(
                List.of("location_ref_a"), List.of("paper-a"));

        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).pageScreenshotAvailable());
        assertTrue(result.items().get(0).pdfEvidenceAvailable());
        assertEquals(List.of(), result.items().get(0).assetWarnings());
        assertEquals(List.of(), result.missingLocationRefs());
    }

    @Test
    void currentPassageLocationReadsDurableCanonicalContent() {
        PaperLocation location = pageLocation("rm-current");
        location.setLocationType(PaperLocationType.PASSAGE);
        location.setLocationRef("passage_ref_a");
        location.setSourceObjectId("passage_ref_a");
        location.setSourceSpanJson("{\"spans\":[]}");
        when(locationRepository.findByLocationRefIn(List.of("passage_ref_a"))).thenReturn(List.of(location));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));
        String content = "Exact selected passage content.";
        PaperPassage passage = new PaperPassage();
        passage.setPaperId("paper-a");
        passage.setModelVersion("rm-current");
        passage.setPassageRef("passage_ref_a");
        passage.setContentText(content);
        passage.setSourceSpanJson("{\"spans\":[]}");
        when(passageRepository.findByPaperIdAndModelVersionAndPassageRefIn(
                "paper-a", "rm-current", List.of("passage_ref_a"))).thenReturn(List.of(passage));

        CanonicalReadingLocationService.ReadBatch result = service.read(
                List.of("passage_ref_a"),
                List.of("paper-a"));

        assertEquals(1, result.items().size());
        assertEquals(content, result.items().get(0).spanText());
        assertEquals("passage", result.items().get(0).elementType());
        verifyNoInteractions(pageRepository, sectionRepository, elementRepository);
    }

    @Test
    void readsSectionTableAndFigureFromDurableCanonicalEntities() {
        String span = "{\"spans\":[]}";
        PaperLocation sectionLocation = location("section-ref", PaperLocationType.SECTION, "section-1", span);
        PaperLocation tableLocation = location("table-ref", PaperLocationType.TABLE, "element-table", span);
        PaperLocation figureLocation = location("figure-ref", PaperLocationType.FIGURE, "element-figure", span);
        PaperSection section = new PaperSection();
        section.setPaperId("paper-a");
        section.setModelVersion("rm-current");
        section.setSectionId("section-1");
        section.setSectionText("Exact section text.");
        section.setSourceSpanJson(span);
        PaperReadingElement table = element("element-table", "table-ref", PaperLocationType.TABLE, "TABLE");
        table.setSearchableText(null);
        table.setBodyText("Exact table body.");
        PaperReadingElement figure = element("element-figure", "figure-ref", PaperLocationType.FIGURE, "IMAGE");
        figure.setSearchableText(null);
        figure.setCaptionText("Exact figure caption.");
        List<String> refs = List.of("section-ref", "table-ref", "figure-ref");
        when(locationRepository.findByLocationRefIn(refs))
                .thenReturn(List.of(sectionLocation, tableLocation, figureLocation));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));
        when(sectionRepository.findByPaperIdAndModelVersionAndSectionIdIn(
                "paper-a", "rm-current", List.of("section-1"))).thenReturn(List.of(section));
        when(elementRepository.findByPaperIdAndModelVersionAndReadingElementIdIn(
                "paper-a", "rm-current", List.of("element-table", "element-figure")))
                .thenReturn(List.of(table, figure));

        CanonicalReadingLocationService.ReadBatch result = service.read(refs, List.of("paper-a"));

        assertEquals(List.of("Exact section text.", "Exact table body.", "Exact figure caption."),
                result.items().stream().map(CanonicalReadingLocationService.CanonicalLocation::spanText).toList());
        assertEquals(List.of(), result.missingLocationRefs());
    }

    @Test
    void inconsistentPageSectionAndPassageAreMissingWhileValidContentSurvives() {
        String span = "{\"spans\":[]}";
        PaperLocation pageLocation = location("page-ref", PaperLocationType.PAGE, "page-2", span);
        pageLocation.setPageNumber(2);
        PaperLocation sectionLocation = location("section-ref", PaperLocationType.SECTION, "section-1", span);
        PaperLocation passageLocation = location("passage-ref", PaperLocationType.PASSAGE, "passage-1", span);
        PaperLocation validLocation = location("valid-ref", PaperLocationType.PASSAGE, "passage-valid", span);
        PaperPage wrongPage = new PaperPage();
        wrongPage.setPaperId("paper-a");
        wrongPage.setModelVersion("rm-current");
        wrongPage.setPageNumber(3);
        wrongPage.setPageText("Wrong page.");
        wrongPage.setSourceSpanJson(span);
        PaperSection wrongSpan = new PaperSection();
        wrongSpan.setPaperId("paper-a");
        wrongSpan.setModelVersion("rm-current");
        wrongSpan.setSectionId("section-1");
        wrongSpan.setSectionText("Wrong section.");
        wrongSpan.setSourceSpanJson("{\"spans\":[1]}");
        PaperPassage wrongAssociation = passage("passage-other", "Wrong passage.", span);
        PaperPassage valid = passage("passage-valid", "Valid passage.", span);
        List<String> refs = List.of("page-ref", "section-ref", "passage-ref", "valid-ref");
        when(locationRepository.findByLocationRefIn(refs))
                .thenReturn(List.of(pageLocation, sectionLocation, passageLocation, validLocation));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));
        when(pageRepository.findByPaperIdAndModelVersionAndPageNumberIn(
                "paper-a", "rm-current", List.of(2))).thenReturn(List.of(wrongPage));
        when(sectionRepository.findByPaperIdAndModelVersionAndSectionIdIn(
                "paper-a", "rm-current", List.of("section-1"))).thenReturn(List.of(wrongSpan));
        when(passageRepository.findByPaperIdAndModelVersionAndPassageRefIn(
                "paper-a", "rm-current", List.of("passage-1", "passage-valid")))
                .thenReturn(List.of(wrongAssociation, valid));

        CanonicalReadingLocationService.ReadBatch result = service.read(refs, List.of("paper-a"));

        assertEquals(List.of("valid-ref"), result.items().stream()
                .map(CanonicalReadingLocationService.CanonicalLocation::locationRef).toList());
        assertEquals(List.of("page-ref", "section-ref", "passage-ref"), result.missingLocationRefs());
    }

    @Test
    void inconsistentTableAndFigureAssociationsAreMissing() {
        String span = "{\"spans\":[]}";
        List<PaperLocation> locations = List.of(
                location("table-paper", PaperLocationType.TABLE, "element-paper", span),
                location("figure-model", PaperLocationType.FIGURE, "element-model", span),
                location("table-ref", PaperLocationType.TABLE, "element-ref", span),
                location("figure-type", PaperLocationType.FIGURE, "element-type", span),
                location("table-kind", PaperLocationType.TABLE, "element-kind", span),
                location("figure-kind", PaperLocationType.FIGURE, "element-figure-kind", span));
        List<String> refs = locations.stream().map(PaperLocation::getLocationRef).toList();
        PaperReadingElement wrongPaper = element("element-paper", "table-paper", PaperLocationType.TABLE, "TABLE");
        wrongPaper.setPaperId("paper-b");
        PaperReadingElement wrongModel = element("element-model", "figure-model", PaperLocationType.FIGURE, "IMAGE");
        wrongModel.setModelVersion("rm-old");
        PaperReadingElement wrongRef = element("element-ref", "other-ref", PaperLocationType.TABLE, "TABLE");
        PaperReadingElement wrongLocationType = element("element-type", "figure-type", PaperLocationType.TABLE, "TABLE");
        PaperReadingElement wrongTableKind = element("element-kind", "table-kind", PaperLocationType.TABLE, "FIGURE");
        PaperReadingElement wrongFigureKind = element(
                "element-figure-kind", "figure-kind", PaperLocationType.FIGURE, "TABLE");
        when(locationRepository.findByLocationRefIn(refs)).thenReturn(locations);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));
        when(elementRepository.findByPaperIdAndModelVersionAndReadingElementIdIn(
                "paper-a", "rm-current", locations.stream().map(PaperLocation::getSourceObjectId).toList()))
                .thenReturn(List.of(wrongPaper, wrongModel, wrongRef, wrongLocationType,
                        wrongTableKind, wrongFigureKind));

        CanonicalReadingLocationService.ReadBatch result = service.read(refs, List.of("paper-a"));

        assertEquals(List.of(), result.items());
        assertEquals(refs, result.missingLocationRefs());
    }

    private PaperLocation pageLocation(String modelVersion) {
        PaperLocation location = new PaperLocation();
        location.setLocationRef("location_ref_a");
        location.setPaperId("paper-a");
        location.setModelVersion(modelVersion);
        location.setLocationType(PaperLocationType.PAGE);
        location.setPageNumber(2);
        location.setSectionTitle("Methods");
        location.setSourceSpanJson("{\"spans\":[]}");
        return location;
    }

    private PaperLocation location(String ref, PaperLocationType type, String sourceObjectId, String sourceSpan) {
        PaperLocation location = new PaperLocation();
        location.setLocationRef(ref);
        location.setPaperId("paper-a");
        location.setModelVersion("rm-current");
        location.setLocationType(type);
        location.setPageNumber(1);
        location.setSourceObjectId(sourceObjectId);
        location.setSourceSpanJson(sourceSpan);
        return location;
    }

    private PaperPassage passage(String ref, String content, String sourceSpan) {
        PaperPassage passage = new PaperPassage();
        passage.setPaperId("paper-a");
        passage.setModelVersion("rm-current");
        passage.setPassageRef(ref);
        passage.setContentText(content);
        passage.setSourceSpanJson(sourceSpan);
        return passage;
    }

    private PaperReadingElement element(String id, String locationRef,
                                        PaperLocationType locationType, String elementType) {
        PaperReadingElement element = new PaperReadingElement();
        element.setPaperId("paper-a");
        element.setModelVersion("rm-current");
        element.setReadingElementId(id);
        element.setLocationRef(locationRef);
        element.setLocationType(locationType);
        element.setElementType(elementType);
        element.setSearchableText("canonical element text");
        return element;
    }

    private PaperReadingModel readyModel(String modelVersion) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion(modelVersion);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        return model;
    }

}
