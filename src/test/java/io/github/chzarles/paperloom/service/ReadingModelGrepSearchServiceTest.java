package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ReadingModelGrepSearchServiceTest {

    @Mock
    private PaperReadingModelRepository modelRepository;

    @Mock
    private PaperReadingElementRepository elementRepository;

    @Mock
    private PaperLocationRepository locationRepository;

    @Mock
    private PaperPageRepository pageRepository;

    @Mock
    private PaperSectionRepository sectionRepository;

    private ReadingModelGrepSearchService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ReadingModelGrepSearchService(
                modelRepository,
                elementRepository,
                locationRepository,
                pageRepository,
                sectionRepository
        );
    }

    @Test
    void elementHitsResolveToOwnLocations() {
        PaperReadingElement table = element("table-el", "TABLE", 1);
        table.setLocationRef("table-ref");
        table.setBodyText("Agentic eval scores by model.");
        table.setSearchableText("Agentic eval scores by model.");
        PaperReadingElement figure = element("figure-el", "IMAGE", 2);
        figure.setLocationRef("figure-ref");
        figure.setCaptionText("Agentic eval accuracy chart.");

        givenCurrentModel("paper-a", List.of(table, figure), List.of(), List.of(),
                List.of(
                        location("paper-a", "table-ref", PaperLocationType.TABLE, 1, null),
                        location("paper-a", "figure-ref", PaperLocationType.FIGURE, 2, null)
                ));

        List<ReadingLocationCandidate> candidates = search("paper-a", "agentic eval");

        assertEquals(List.of("table-ref", "figure-ref"),
                candidates.stream().map(ReadingLocationCandidate::locationRef).toList());
        assertEquals(PaperLocationType.TABLE, candidates.get(0).locationType());
        assertEquals("OWN_LOCATION", candidates.get(0).routingSource());
        assertTrue(candidates.get(0).matchedFields().contains("bodyText"));
        assertEquals(PaperLocationType.FIGURE, candidates.get(1).locationType());
        assertTrue(candidates.get(1).matchedFields().contains("captionText"));
    }

    @Test
    void panelOnlyElementHitResolvesToParentFigureLocation() {
        PaperReadingElement parent = element("figure-parent", "IMAGE", 3);
        parent.setLocationRef("figure-ref");
        PaperReadingElement child = element("panel-child", "CHART", 3);
        child.setParentReadingElementId("figure-parent");
        child.setSearchableText("(a) Recall on agentic eval tasks.");

        givenCurrentModel("paper-panel", List.of(parent, child), List.of(), List.of(),
                List.of(location("paper-panel", "figure-ref", PaperLocationType.FIGURE, 3, null)));

        List<ReadingLocationCandidate> candidates = search("paper-panel", "recall");

        assertEquals(1, candidates.size());
        assertEquals("figure-ref", candidates.get(0).locationRef());
        assertEquals(PaperLocationType.FIGURE, candidates.get(0).locationType());
        assertEquals("panel-child", candidates.get(0).readingElementId());
        assertEquals("PARENT_LOCATION", candidates.get(0).routingSource());
        assertEquals(List.of("panel-child"), candidates.get(0).matchedReadingElementIds());
    }

    @Test
    void formulaElementHitResolvesToContainingPageLocation() {
        PaperReadingElement formula = element("formula-el", "FORMULA", 4);
        formula.setSearchableText("loss = alpha + beta");

        givenCurrentModel("paper-formula", List.of(formula), List.of(), List.of(page("paper-formula", 4, "")),
                List.of(location("paper-formula", "page-ref-4", PaperLocationType.PAGE, 4, null)));

        List<ReadingLocationCandidate> candidates = search("paper-formula", "alpha");

        assertEquals(1, candidates.size());
        assertEquals("page-ref-4", candidates.get(0).locationRef());
        assertEquals(PaperLocationType.PAGE, candidates.get(0).locationType());
        assertEquals("PAGE_LOCATION", candidates.get(0).routingSource());
    }

    @Test
    void sectionAndPageHitsResolveToTheirLocations() {
        PaperSection section = section("paper-reading", "section-1", 5,
                "Evaluation", "Agentic judge agreement improves.");
        PaperPage page = page("paper-reading", 6, "Appendix includes ablation details.");

        givenCurrentModel("paper-reading", List.of(), List.of(section), List.of(page),
                List.of(
                        location("paper-reading", "section-ref", PaperLocationType.SECTION, 5, "section-1"),
                        location("paper-reading", "page-ref-6", PaperLocationType.PAGE, 6, null)
                ));

        List<ReadingLocationCandidate> sectionCandidates = search("paper-reading", "judge agreement");
        List<ReadingLocationCandidate> pageCandidates = search("paper-reading", "ablation");

        assertEquals("section-ref", sectionCandidates.get(0).locationRef());
        assertEquals(PaperLocationType.SECTION, sectionCandidates.get(0).locationType());
        assertEquals("SECTION_LOCATION", sectionCandidates.get(0).routingSource());
        assertTrue(sectionCandidates.get(0).matchedFields().contains("sectionText"));

        assertEquals("page-ref-6", pageCandidates.get(0).locationRef());
        assertEquals(PaperLocationType.PAGE, pageCandidates.get(0).locationType());
        assertEquals("PAGE_LOCATION", pageCandidates.get(0).routingSource());
        assertTrue(pageCandidates.get(0).matchedFields().contains("pageText"));
    }

    @Test
    void parserAndDebugFieldsDoNotMatch() {
        PaperReadingElement element = element("debug-el", "TABLE", 1);
        element.setParserElementId("secret-parser-id");
        element.setSourceObjectId("secret-source-id");
        element.setRawAttributesJson("{\"debug\":\"secret\"}");
        element.setStructuredPayloadJson("{\"payload\":\"secret\"}");
        element.setSourceSpanJson("{\"span\":\"secret\"}");
        element.setBboxJson("{\"bbox\":\"secret\"}");
        element.setParserImagePath("/secret/path.png");
        element.setLocationRef("secret-location-ref");
        element.setSearchableText("visible text");

        givenCurrentModel("paper-debug", List.of(element), List.of(), List.of(),
                List.of(location("paper-debug", "secret-location-ref", PaperLocationType.TABLE, 1, null)));

        assertEquals(List.of(), search("paper-debug", "secret"));
    }

    @Test
    void unresolvedHitsAreDroppedAndLocationFiltersAreAppliedBeforeLimit() {
        PaperReadingElement table = element("table-el", "TABLE", 1);
        table.setLocationRef("table-ref");
        table.setSearchableText("Agentic eval evidence.");
        PaperReadingElement figure = element("figure-el", "IMAGE", 2);
        figure.setLocationRef("figure-ref");
        figure.setSearchableText("Agentic eval evidence.");
        PaperReadingElement unresolved = element("unresolved-el", "FORMULA", 3);
        unresolved.setSearchableText("Agentic eval evidence.");

        givenCurrentModel("paper-filters", List.of(table, figure, unresolved), List.of(), List.of(),
                List.of(
                        location("paper-filters", "table-ref", PaperLocationType.TABLE, 1, null),
                        location("paper-filters", "figure-ref", PaperLocationType.FIGURE, 2, null)
                ));

        List<ReadingLocationCandidate> candidates = service.search(new ReadingModelGrepSearchRequest(
                List.of("paper-filters"),
                "agentic eval",
                List.of(PaperLocationType.FIGURE),
                2,
                2,
                1
        ));

        assertEquals(1, candidates.size());
        assertEquals("figure-ref", candidates.get(0).locationRef());
    }

    private List<ReadingLocationCandidate> search(String paperId, String queryText) {
        return service.search(new ReadingModelGrepSearchRequest(
                List.of(paperId),
                queryText,
                List.of(),
                null,
                null,
                60
        ));
    }

    private void givenCurrentModel(String paperId,
                                   List<PaperReadingElement> elements,
                                   List<PaperSection> sections,
                                   List<PaperPage> pages,
                                   List<PaperLocation> locations) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion("v1");
        model.setCurrent(true);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);

        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId)).thenReturn(Optional.of(model));
        when(elementRepository.findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(paperId, "v1"))
                .thenReturn(elements);
        when(sectionRepository.findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc(paperId, "v1"))
                .thenReturn(sections);
        when(pageRepository.findByPaperIdAndModelVersionOrderByPageNumberAsc(paperId, "v1"))
                .thenReturn(pages);
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(paperId, "v1"))
                .thenReturn(locations);
    }

    private PaperReadingElement element(String readingElementId, String elementType, int pageNumber) {
        PaperReadingElement element = new PaperReadingElement();
        element.setPaperId("paper");
        element.setModelVersion("v1");
        element.setReadingElementId(readingElementId);
        element.setElementType(elementType);
        element.setPageNumber(pageNumber);
        element.setAssociationStatus("ROOT");
        element.setUserId("u1");
        return element;
    }

    private PaperLocation location(String paperId,
                                   String locationRef,
                                   PaperLocationType locationType,
                                   int pageNumber,
                                   String sourceObjectId) {
        PaperLocation location = new PaperLocation();
        location.setPaperId(paperId);
        location.setModelVersion("v1");
        location.setLocationRef(locationRef);
        location.setLocationType(locationType);
        location.setPageNumber(pageNumber);
        location.setSourceObjectId(sourceObjectId);
        location.setSourceSpanJson("{}");
        location.setContentKind(locationType.name());
        location.setUserId("u1");
        return location;
    }

    private PaperSection section(String paperId, String sectionId, int pageNumber, String title, String text) {
        PaperSection section = new PaperSection();
        section.setPaperId(paperId);
        section.setModelVersion("v1");
        section.setSectionId(sectionId);
        section.setSectionTitle(title);
        section.setSectionText(text);
        section.setPageNumberFrom(pageNumber);
        section.setPageNumberTo(pageNumber);
        section.setDisplayOrder(1);
        section.setSourceSpanJson("{}");
        section.setTextHash("hash");
        section.setCharCount(text.length());
        section.setUserId("u1");
        return section;
    }

    private PaperPage page(String paperId, int pageNumber, String text) {
        PaperPage page = new PaperPage();
        page.setPaperId(paperId);
        page.setModelVersion("v1");
        page.setPageNumber(pageNumber);
        page.setPageText(text);
        page.setTextHash("hash");
        page.setCharCount(text.length());
        page.setTextStatus(PaperPage.TEXT_STATUS_READABLE);
        page.setSourceSpanJson("{}");
        page.setUserId("u1");
        return page;
    }
}
