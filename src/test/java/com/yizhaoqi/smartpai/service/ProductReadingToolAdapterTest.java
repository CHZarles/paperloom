package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingToolAdapterTest {

    @Mock
    private PaperCandidateSearchService paperCandidateSearchService;

    @Mock
    private ReadingModelGrepSearchService readingModelGrepSearchService;

    @Mock
    private PaperReadingModelRepository modelRepository;

    @Mock
    private PaperLocationRepository locationRepository;

    @Mock
    private ProductPaperHandleService handleService;

    @Mock
    private ProductReadingLocationReadService readService;

    @Mock
    private ProductReadingSourceQuoteTraceService traceService;

    private ProductReadingToolAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        adapter = new ProductReadingToolAdapter(
                paperCandidateSearchService,
                readingModelGrepSearchService,
                modelRepository,
                locationRepository,
                handleService,
                new ReadingToolOutputMapper(),
                readService,
                traceService
        );
    }

    @Test
    void searchPaperCandidatesFiltersToVisibleInScopeReadyPapersAndReturnsHandlesOnly() throws Exception {
        when(paperCandidateSearchService.search(new PaperCandidateSearchRequest("agentic eval", "7", null, 20)))
                .thenReturn(List.of(
                        paperCandidate("ready-paper", "Ready Paper"),
                        paperCandidate("not-ready-paper", "Not Ready"),
                        paperCandidate("out-of-scope-paper", "Out Of Scope")
                ));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.manual(List.of("ready-paper"))))
                .thenReturn(true);
        when(handleService.isPaperVisibleToUser("not-ready-paper", 7L, SourceScope.manual(List.of("ready-paper"))))
                .thenReturn(true);
        when(handleService.isPaperVisibleToUser("out-of-scope-paper", 7L, SourceScope.manual(List.of("ready-paper"))))
                .thenReturn(false);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("not-ready-paper")).thenReturn(false);
        when(handleService.handleForPaperId("ready-paper")).thenReturn("paper_handle_ready");

        ProductToolResult result = adapter.searchPaperCandidates(
                "agentic eval",
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.manual(List.of("ready-paper")))
        );

        assertTrue(result.success());
        assertEquals("OK", result.data().get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertEquals(1, items.size());
        assertEquals("paper_handle_ready", items.get(0).get("paperHandle"));
        assertEquals("Ready Paper", items.get(0).get("title"));
        @SuppressWarnings("unchecked")
        Map<String, Object> constraints = (Map<String, Object>) result.data().get("constraints");
        assertEquals(false, constraints.get("previewIsSourceQuote"));
        assertEquals(false, constraints.get("paperContentClaimsAllowed"));

        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("ready-paper"));
        assertFalse(json.contains("paperId"));
        assertFalse(json.contains("matchedFields"));
        assertFalse(json.contains("matchReason"));
        assertFalse(json.contains("rank"));
    }

    @Test
    void findReadingLocationsResolvesHandlesBeforeCallingGrepAndStripsInternalFields() throws Exception {
        when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
                .thenReturn(Optional.of(model("ready-paper", "model-v1")));
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
                .thenReturn(List.of(location("ready-paper", "section_ref_ready", PaperLocationType.SECTION)));
        when(readingModelGrepSearchService.search(new ReadingModelGrepSearchRequest(
                List.of("ready-paper"),
                "agentic eval",
                List.of(PaperLocationType.SECTION),
                null,
                null,
                60
        ))).thenReturn(List.of(new ReadingLocationCandidate(
                "ready-paper",
                "model-v1",
                "section_ref_ready",
                PaperLocationType.SECTION,
                3,
                4,
                "Experiments",
                "reading-el-1",
                "Agentic eval appears here.",
                "ELEMENT",
                "OWN_LOCATION",
                List.of("searchableText"),
                List.of("reading-el-1")
        )));

        ProductToolResult result = adapter.findReadingLocations(
                List.of("paper_handle_ready"),
                "agentic eval",
                List.of(PaperLocationType.SECTION),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals("OK", result.data().get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.data().get("candidates");
        assertEquals(1, candidates.size());
        assertEquals("paper_handle_ready", candidates.get(0).get("paperHandle"));
        assertEquals("section_ref_ready", candidates.get(0).get("locationRef"));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> supported = (Map<String, List<String>>) result.data().get("supportedLocationTypesByPaper");
        assertEquals(List.of("SECTION"), supported.get("paper_handle_ready"));

        ArgumentCaptor<ReadingModelGrepSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(ReadingModelGrepSearchRequest.class);
        verify(readingModelGrepSearchService).search(requestCaptor.capture());
        assertEquals(List.of("ready-paper"), requestCaptor.getValue().paperIds());

        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("ready-paper"));
        assertFalse(json.contains("paperId"));
        assertFalse(json.contains("model-v1"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("reading-el-1"));
        assertFalse(json.contains("readingElementId"));
        assertFalse(json.contains("matchedFields"));
        assertFalse(json.contains("routingSource"));
    }

    @Test
    void findReadingLocationsDistinguishesMissingStructureFromNoTextMatch() {
        when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
                .thenReturn(Optional.of(model("ready-paper", "model-v1")));
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
                .thenReturn(List.of(location("ready-paper", "page_ref_ready", PaperLocationType.PAGE)));

        ProductToolResult missingType = adapter.findReadingLocations(
                List.of("paper_handle_ready"),
                "agentic eval",
                List.of(PaperLocationType.TABLE),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(missingType.success());
        assertEquals("NO_MATCHING_LOCATION_TYPE", missingType.data().get("status"));
        verify(readingModelGrepSearchService, never()).search(new ReadingModelGrepSearchRequest(
                List.of("ready-paper"),
                "agentic eval",
                List.of(PaperLocationType.TABLE),
                null,
                null,
                60
        ));

        ProductToolResult noMatch = adapter.findReadingLocations(
                List.of("paper_handle_ready"),
                "agentic eval",
                List.of(PaperLocationType.PAGE),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(noMatch.success());
        assertEquals("NO_MATCH", noMatch.data().get("status"));
    }

    @Test
    void unavailableHandleAndUnreadableModelReturnNavigationStatusesWithoutGrep() {
        when(handleService.resolvePaperHandle("paper_handle_missing")).thenReturn(Optional.empty());
        when(handleService.resolvePaperHandle("paper_handle_unready")).thenReturn(Optional.of("unready-paper"));
        when(handleService.isPaperVisibleToUser("unready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("unready-paper")).thenReturn(false);

        ProductToolResult missingHandle = adapter.findReadingLocations(
                List.of("paper_handle_missing"),
                "agentic eval",
                List.of(),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );
        ProductToolResult unready = adapter.findReadingLocations(
                List.of("paper_handle_unready"),
                "agentic eval",
                List.of(),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(missingHandle.success());
        assertEquals("PAPER_HANDLE_UNAVAILABLE", missingHandle.data().get("status"));
        assertTrue(unready.success());
        assertEquals("READING_MODEL_UNAVAILABLE", unready.data().get("status"));
        verify(readingModelGrepSearchService, never()).search(new ReadingModelGrepSearchRequest(
                List.of("unready-paper"),
                "agentic eval",
                List.of(),
                null,
                null,
                60
        ));
    }

    @Test
    void readLocationsDelegatesToReadServiceAndReturnsCiteableSourceQuotesOnly() throws Exception {
        ProductToolContext context = new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto());
        when(readService.readLocations(List.of("page_ref_ready"), context))
                .thenReturn(new ProductReadingLocationReadService.ReadResult(
                        List.of(Map.of(
                                "sourceQuoteRef", "source_quote_abc",
                                "locationRef", "page_ref_ready",
                                "paperHandle", "paper_handle_ready",
                                "paperTitle", "Ready Paper",
                                "contentKind", "TEXT",
                                "content", "Source quote content with score."
                        )),
                        List.of(Map.of(
                                "locationRef", "page_ref_ready",
                                "status", "OK"
                        ))
                ));

        ProductToolResult result = adapter.readLocations(List.of("page_ref_ready"), context);

        assertTrue(result.success());
        assertEquals(ProductToolEffect.EVIDENCE, result.effect());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceQuotes = (List<Map<String, Object>>) result.data().get("sourceQuotes");
        assertEquals("source_quote_abc", sourceQuotes.get(0).get("sourceQuoteRef"));
        assertEquals("Source quote content with score.", sourceQuotes.get(0).get("content"));
        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("paperId"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("readingElementId"));
        assertFalse(json.contains("splitPolicyVersion"));
        assertFalse(json.contains("contentHash"));
    }

    @Test
    void traceSourceQuotesDelegatesToTraceServiceAndReturnsCiteableSourceQuotesOnly() throws Exception {
        ProductToolContext context = new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto());
        when(traceService.traceSourceQuotes(List.of("source_quote_abc"), context))
                .thenReturn(new ProductReadingSourceQuoteTraceService.TraceResult(
                        List.of(Map.of(
                                "sourceQuoteRef", "source_quote_abc",
                                "locationRef", "page_ref_old",
                                "paperHandle", "paper_handle_ready",
                                "paperTitle", "Ready Paper",
                                "contentKind", "TEXT",
                                "content", "Traced source quote content with score."
                        )),
                        List.of(Map.of(
                                "sourceQuoteRef", "source_quote_abc",
                                "status", "OK"
                        ))
                ));

        ProductToolResult result = adapter.traceSourceQuotes(List.of("source_quote_abc"), context);

        assertTrue(result.success());
        assertEquals(ProductToolEffect.EVIDENCE, result.effect());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceQuotes = (List<Map<String, Object>>) result.data().get("sourceQuotes");
        assertEquals("source_quote_abc", sourceQuotes.get(0).get("sourceQuoteRef"));
        assertEquals("Traced source quote content with score.", sourceQuotes.get(0).get("content"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traceStatus = (List<Map<String, Object>>) result.data().get("traceStatus");
        assertEquals("OK", traceStatus.get(0).get("status"));
        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("paperId"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("readingElementId"));
        assertFalse(json.contains("splitPolicyVersion"));
        assertFalse(json.contains("contentHash"));
    }

    private PaperCandidate paperCandidate(String paperId, String title) {
        return new PaperCandidate(
                paperId,
                title,
                "Ada Lovelace",
                2025,
                "NeurIPS",
                "Agentic eval preview",
                List.of("title"),
                "title matched all query tokens",
                10
        );
    }

    private PaperReadingModel model(String paperId, String modelVersion) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion(modelVersion);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        return model;
    }

    private PaperLocation location(String paperId, String locationRef, PaperLocationType locationType) {
        PaperLocation location = new PaperLocation();
        location.setPaperId(paperId);
        location.setModelVersion("model-v1");
        location.setLocationRef(locationRef);
        location.setLocationType(locationType);
        location.setPageNumber(3);
        return location;
    }
}
