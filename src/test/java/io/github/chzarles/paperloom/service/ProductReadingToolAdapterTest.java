package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
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
    private PaperService paperService;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperReadingModelRepository modelRepository;

    @Mock
    private PaperSectionRepository sectionRepository;

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
                paperService,
                paperRepository,
                modelRepository,
                sectionRepository,
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
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("ready-paper"))
                .thenReturn(Optional.of(paper("ready-paper", "Ready Paper", "ready.pdf")));
        when(handleService.handleForPaperId("ready-paper")).thenReturn("paper_handle_ready");
        when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));

        ProductToolResult result = adapter.searchPaperCandidates(
                "agentic eval",
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.manual(List.of("ready-paper")))
        );

        assertTrue(result.success());
        assertEquals("OK", result.data().get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertEquals(1, items.size());
        assertEquals("ready-paper", items.get(0).get("paperId"));
        assertEquals("paper_handle_ready", items.get(0).get("paperHandle"));
        assertEquals("Ready Paper", items.get(0).get("title"));
        assertEquals("ready.pdf", items.get(0).get("originalFilename"));
        assertEquals(List.of(
                "title matched all query tokens",
                "Matched paper metadata fields: title."
        ), items.get(0).get("matchReasons"));
        @SuppressWarnings("unchecked")
        Map<String, Object> constraints = (Map<String, Object>) result.data().get("constraints");
        assertEquals(false, constraints.get("previewIsSourceQuote"));
        assertEquals(false, constraints.get("paperContentClaimsAllowed"));

        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("matchedFields"));
        assertFalse(json.contains("rank"));
    }

    @Test
    void searchPaperCandidatesFailsClosedWhenGeneratedHandleResolvesToDifferentPaper() {
        when(paperCandidateSearchService.search(new PaperCandidateSearchRequest("agentic eval", "7", null, 20)))
                .thenReturn(List.of(paperCandidate("ready-paper", "Ready Paper")));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("ready-paper"))
                .thenReturn(Optional.of(paper("ready-paper", "Ready Paper", "ready.pdf")));
        when(handleService.handleForPaperId("ready-paper")).thenReturn("paper_handle_wrong");
        when(handleService.resolvePaperHandle("paper_handle_wrong")).thenReturn(Optional.of("other-paper"));

        ProductToolResult result = adapter.searchPaperCandidates(
                "agentic eval",
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertFalse(result.success());
        assertEquals("PAPER_CARD_IDENTITY_INVALID", result.data().get("status"));
        assertEquals("paper_card_identity_invalid", result.data().get("error"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertTrue(items.isEmpty());
    }

    @Test
    void searchPaperCandidatesUsesCanonicalPaperRecordInsteadOfCandidateTitle() throws Exception {
        when(paperCandidateSearchService.search(new PaperCandidateSearchRequest("agentic eval", "7", null, 20)))
                .thenReturn(List.of(paperCandidate("ready-paper", "Wrong Candidate Title")));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("ready-paper"))
                .thenReturn(Optional.of(paper("ready-paper", "Canonical Ready Paper", "canonical-ready.pdf")));
        when(handleService.handleForPaperId("ready-paper")).thenReturn("paper_handle_ready");
        when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));

        ProductToolResult result = adapter.searchPaperCandidates(
                "agentic eval",
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals("OK", result.data().get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertEquals(1, items.size());
        assertEquals("ready-paper", items.get(0).get("paperId"));
        assertEquals("paper_handle_ready", items.get(0).get("paperHandle"));
        assertEquals("Canonical Ready Paper", items.get(0).get("title"));
        assertEquals("canonical-ready.pdf", items.get(0).get("originalFilename"));
        assertFalse(objectMapper.writeValueAsString(result.data()).contains("Wrong Candidate Title"));
    }

    @Test
    void getSessionStateCountsOnlyCurrentReadyScopedPapers() throws Exception {
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(
                paper("ready-paper", "Ready Paper", "ready.pdf"),
                paper("unready-paper", "Unready Paper", "unready.pdf"),
                paper("out-of-scope-paper", "Out Of Scope", "scope.pdf")
        ));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
                .thenReturn(Optional.of(model("ready-paper", "model-v1")));
        PaperReadingModel unreadyModel = model("unready-paper", "model-v1");
        unreadyModel.setModelStatus(PaperReadingModelStatus.READING_MODEL_BUILDING);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("unready-paper"))
                .thenReturn(Optional.of(unreadyModel));

        ProductToolResult result = adapter.getSessionState(
                new ProductToolContext(
                        7L,
                        "conversation-1",
                        "generation-1",
                        SourceScope.manual(List.of("ready-paper", "unready-paper"))
                )
        );

        assertTrue(result.success());
        assertEquals("get_session_state", result.toolName());
        assertEquals(ProductToolEffect.PRODUCT_STATE, result.effect());
        assertEquals("OK", result.data().get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> searchScope = (Map<String, Object>) result.data().get("searchScope");
        assertEquals("MANUAL_SOURCE", searchScope.get("scopeMode"));
        assertEquals("Selected readable papers", searchScope.get("label"));
        assertEquals(true, searchScope.get("readablePaperCountKnown"));
        assertEquals(1, searchScope.get("readablePaperCount"));
        assertEquals(true, searchScope.get("immutable"));

        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("ready-paper"));
        assertFalse(json.contains("paperId"));
        assertFalse(json.contains("model-v1"));
        assertFalse(json.contains("modelVersion"));
    }

    @Test
    void listPapersFiltersSortsFacetsAndReturnsHandlesOnly() throws Exception {
        Paper agentPaper = paper("ready-agent", "Agentic Eval Benchmark", "agentic-eval.pdf");
        agentPaper.setAuthors("Ada Lovelace; Grace Hopper");
        agentPaper.setPublicationYear(2025);
        agentPaper.setVenue("NeurIPS");
        agentPaper.setDoi("10.1000/agent");
        agentPaper.setArxivId("2501.00001");
        agentPaper.setMergedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        Paper otherPaper = paper("ready-other", "Different Benchmark", "different.pdf");
        otherPaper.setAuthors("Ada Lovelace");
        otherPaper.setPublicationYear(2024);
        otherPaper.setVenue("ICML");
        Paper unreadyPaper = paper("unready-paper", "Agent Draft", "draft.pdf");

        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(agentPaper, otherPaper, unreadyPaper));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-agent"))
                .thenReturn(Optional.of(model("ready-agent", "model-v1")));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-other"))
                .thenReturn(Optional.of(model("ready-other", "model-v1")));
        PaperReadingModel unreadyModel = model("unready-paper", "model-v1");
        unreadyModel.setModelStatus(PaperReadingModelStatus.READING_MODEL_FAILED);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("unready-paper"))
                .thenReturn(Optional.of(unreadyModel));
        when(handleService.handleForPaperId("ready-agent")).thenReturn("paper_handle_agent");
        when(handleService.resolvePaperHandle("paper_handle_agent")).thenReturn(Optional.of("ready-agent"));

        ProductToolResult result = adapter.listPapers(
                new ReadingToolArgumentValidator.ListPaperFilters(
                        "agent",
                        "",
                        "",
                        "",
                        "Grace",
                        "10.1000/agent",
                        "2501.00001",
                        new ReadingToolArgumentValidator.YearRange(2025, 2026),
                        "neur"
                ),
                true,
                ReadingToolArgumentValidator.ListPaperSort.TITLE,
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals("list_papers", result.toolName());
        assertEquals(ProductToolEffect.PAPER_LIST, result.effect());
        assertEquals("OK", result.data().get("status"));
        assertEquals(1, result.data().get("total"));
        assertEquals(1, result.data().get("returned"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertEquals(1, items.size());
        assertEquals(1, items.get(0).get("ordinal"));
        assertEquals("ready-agent", items.get(0).get("paperId"));
        assertEquals("paper_handle_agent", items.get(0).get("paperHandle"));
        assertEquals("Agentic Eval Benchmark", items.get(0).get("title"));
        assertEquals("agentic-eval.pdf", items.get(0).get("originalFilename"));
        assertEquals(List.of("Ada Lovelace", "Grace Hopper"), items.get(0).get("authors"));
        assertEquals(2025, items.get(0).get("year"));
        assertEquals("NeurIPS", items.get(0).get("venue"));
        assertEquals(List.of(
                "Title contains \"agent\".",
                "Author metadata matches \"Grace\".",
                "DOI exactly matches the requested identifier.",
                "arXiv id exactly matches the requested identifier.",
                "Publication year is within the requested range.",
                "Venue metadata contains \"neur\"."
        ), items.get(0).get("matchReasons"));
        assertEquals(List.of(), items.get(0).get("catalogTopics"));
        assertEquals(List.of(), items.get(0).get("paperTypes"));
        @SuppressWarnings("unchecked")
        Map<String, Object> facets = (Map<String, Object>) result.data().get("facets");
        assertTrue(objectMapper.writeValueAsString(facets).contains("\"value\":2025"));
        assertTrue(objectMapper.writeValueAsString(facets).contains("\"value\":\"Grace Hopper\""));

        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("model-v1"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("abstract"));
        assertFalse(json.contains("score"));
        assertFalse(json.contains("rank"));
    }

    @Test
    void findPapersByIdentityReturnsUnambiguousCanonicalMetadataMatchOnly() throws Exception {
        Paper lora = paper("ready-lora", "LoRA: Low-Rank Adaptation of Large Language Models", "lora.pdf");
        lora.setAuthors("Edward Hu; Yelong Shen");
        lora.setPublicationYear(2021);
        lora.setVenue("ICLR");
        lora.setDoi("https://doi.org/10.48550/arXiv.2106.09685");
        lora.setArxivId("arXiv:2106.09685v2");
        lora.setAbstractText("Internal abstract must not be returned.");
        Paper unready = paper("unready-lora", "LoRA Draft", "lora-draft.pdf");
        Paper outOfScope = paper("out-of-scope-lora", "LoRA Outside", "outside.pdf");

        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(lora, unready, outOfScope));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-lora"))
                .thenReturn(Optional.of(model("ready-lora", "model-v1")));
        PaperReadingModel unreadyModel = model("unready-lora", "model-v1");
        unreadyModel.setModelStatus(PaperReadingModelStatus.READING_MODEL_BUILDING);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("unready-lora"))
                .thenReturn(Optional.of(unreadyModel));
        when(handleService.handleForPaperId("ready-lora")).thenReturn("paper_handle_lora");
        when(handleService.resolvePaperHandle("paper_handle_lora")).thenReturn(Optional.of("ready-lora"));

        ProductToolResult result = adapter.findPapersByIdentity(
                new ReadingToolArgumentValidator.IdentityHints(
                        "low-rank",
                        "",
                        "",
                        "lora.pdf",
                        "10.48550/arxiv.2106.09685",
                        "2106.09685",
                        "Hu",
                        2021
                ),
                new ProductToolContext(
                        7L,
                        "conversation-1",
                        "generation-1",
                        SourceScope.manual(List.of("ready-lora", "unready-lora"))
                )
        );

        assertTrue(result.success());
        assertEquals("find_papers_by_identity", result.toolName());
        assertEquals(ProductToolEffect.PAPER_RESOLUTION, result.effect());
        assertEquals("OK", result.data().get("status"));
        assertEquals(false, result.data().get("ambiguous"));
        assertEquals(1, result.data().get("total"));
        assertEquals(1, result.data().get("returned"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.data().get("matches");
        assertEquals(1, matches.size());
        assertEquals("ready-lora", matches.get(0).get("paperId"));
        assertEquals("paper_handle_lora", matches.get(0).get("paperHandle"));
        assertEquals("LoRA: Low-Rank Adaptation of Large Language Models", matches.get(0).get("title"));
        assertEquals("lora.pdf", matches.get(0).get("originalFilename"));
        assertEquals(List.of("Edward Hu", "Yelong Shen"), matches.get(0).get("authors"));
        assertEquals(2021, matches.get(0).get("year"));
        assertEquals("ICLR", matches.get(0).get("venue"));
        assertEquals(List.of(
                "Title contains \"low-rank\".",
                "Filename exactly matches \"lora.pdf\".",
                "DOI exactly matches the requested identifier.",
                "arXiv id exactly matches the requested identifier.",
                "Author metadata matches \"Hu\".",
                "Publication year matches 2021."
        ), matches.get(0).get("matchReasons"));
        @SuppressWarnings("unchecked")
        Map<String, Object> constraints = (Map<String, Object>) result.data().get("constraints");
        assertEquals(false, constraints.get("paperCardIsSourceQuote"));
        assertEquals(false, constraints.get("paperContentClaimsAllowed"));
        assertEquals(false, constraints.get("ambiguousMatchesAuthorizeReading"));

        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("model-v1"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("Internal abstract"));
        assertFalse(json.contains("abstract"));
        assertFalse(matches.get(0).containsKey("score"));
        assertFalse(matches.get(0).containsKey("rank"));
    }

    @Test
    void findPapersByIdentityKeepsExactFilenameMatchWhenOptionalArxivMetadataIsMissing() {
        Paper ruleArena = paper(
                "ready-rulearena",
                "RULEARENA: A Benchmark for Rule-Guided Reasoning with LLMs in Real-World Scenarios",
                "2412.08972.pdf"
        );
        ruleArena.setArxivId(null);

        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(ruleArena));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-rulearena"))
                .thenReturn(Optional.of(model("ready-rulearena", "model-v1")));
        when(handleService.handleForPaperId("ready-rulearena")).thenReturn("paper_handle_rulearena");
        when(handleService.resolvePaperHandle("paper_handle_rulearena")).thenReturn(Optional.of("ready-rulearena"));

        ProductToolResult result = adapter.findPapersByIdentity(
                new ReadingToolArgumentValidator.IdentityHints(
                        "",
                        "",
                        "",
                        "2412.08972.pdf",
                        "",
                        "2412.08972",
                        "",
                        null
                ),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals("OK", result.data().get("status"));
        assertEquals(false, result.data().get("ambiguous"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.data().get("matches");
        assertEquals(1, matches.size());
        assertEquals("ready-rulearena", matches.get(0).get("paperId"));
        assertEquals("paper_handle_rulearena", matches.get(0).get("paperHandle"));
        assertEquals(List.of("Filename exactly matches \"2412.08972.pdf\"."), matches.get(0).get("matchReasons"));
    }

    @Test
    void listPapersKeepsExactFilenameMatchForArxivStylePdfName() {
        Paper ruleArena = paper(
                "ready-rulearena",
                "RULEARENA: A Benchmark for Rule-Guided Reasoning with LLMs in Real-World Scenarios",
                "2412.08972.pdf"
        );

        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(ruleArena));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-rulearena"))
                .thenReturn(Optional.of(model("ready-rulearena", "model-v1")));
        when(handleService.handleForPaperId("ready-rulearena")).thenReturn("paper_handle_rulearena");
        when(handleService.resolvePaperHandle("paper_handle_rulearena")).thenReturn(Optional.of("ready-rulearena"));

        ProductToolResult result = adapter.listPapers(
                new ReadingToolArgumentValidator.ListPaperFilters(
                        "",
                        "",
                        "",
                        "2412.08972.pdf",
                        "",
                        "",
                        "",
                        null,
                        ""
                ),
                false,
                ReadingToolArgumentValidator.ListPaperSort.TITLE,
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals("OK", result.data().get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertEquals(1, items.size());
        assertEquals("ready-rulearena", items.get(0).get("paperId"));
        assertEquals("paper_handle_rulearena", items.get(0).get("paperHandle"));
        assertEquals("2412.08972.pdf", items.get(0).get("originalFilename"));
        assertEquals(List.of("Filename exactly matches \"2412.08972.pdf\"."), items.get(0).get("matchReasons"));
    }

    @Test
    void findPapersByIdentityReturnsAmbiguousAfterDedupAndNoMatchForContradictoryHints() {
        Paper first = paper("ready-lora-a", "LoRA: Low-Rank Adaptation", "lora-a.pdf");
        first.setAuthors("Edward Hu");
        first.setPublicationYear(2021);
        Paper firstDuplicate = paper("ready-lora-a", "Duplicate Row", "duplicate.pdf");
        Paper second = paper("ready-lora-b", "LoRA for Diffusion", "lora-b.pdf");
        second.setAuthors("Edward Hu");
        second.setPublicationYear(2021);

        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(first, firstDuplicate, second));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-lora-a"))
                .thenReturn(Optional.of(model("ready-lora-a", "model-v1")));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-lora-b"))
                .thenReturn(Optional.of(model("ready-lora-b", "model-v1")));
        when(handleService.handleForPaperId("ready-lora-a")).thenReturn("paper_handle_lora_a");
        when(handleService.handleForPaperId("ready-lora-b")).thenReturn("paper_handle_lora_b");
        when(handleService.resolvePaperHandle("paper_handle_lora_a")).thenReturn(Optional.of("ready-lora-a"));
        when(handleService.resolvePaperHandle("paper_handle_lora_b")).thenReturn(Optional.of("ready-lora-b"));

        ProductToolContext context = new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto());
        ProductToolResult ambiguous = adapter.findPapersByIdentity(
                new ReadingToolArgumentValidator.IdentityHints("", "", "", "", "", "", "Hu", 2021),
                context
        );
        ProductToolResult noMatch = adapter.findPapersByIdentity(
                new ReadingToolArgumentValidator.IdentityHints("LoRA", "Wrong Exact Title", "", "", "", "", "", null),
                context
        );

        assertTrue(ambiguous.success());
        assertEquals("AMBIGUOUS", ambiguous.data().get("status"));
        assertEquals(true, ambiguous.data().get("ambiguous"));
        assertEquals(2, ambiguous.data().get("total"));
        assertEquals(2, ambiguous.data().get("returned"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) ambiguous.data().get("matches");
        assertEquals(List.of("paper_handle_lora_b", "paper_handle_lora_a"),
                matches.stream().map(match -> match.get("paperHandle")).toList());
        assertEquals(List.of(
                "Author metadata matches \"Hu\".",
                "Publication year matches 2021."
        ), matches.get(0).get("matchReasons"));

        assertTrue(noMatch.success());
        assertEquals("NO_MATCH", noMatch.data().get("status"));
        assertEquals(false, noMatch.data().get("ambiguous"));
        assertEquals(0, noMatch.data().get("total"));
        assertEquals(0, noMatch.data().get("returned"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> noMatches = (List<Map<String, Object>>) noMatch.data().get("matches");
        assertTrue(noMatches.isEmpty());
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
        assertEquals("ready-paper", candidates.get(0).get("paperId"));
        assertEquals("section_ref_ready", candidates.get(0).get("locationRef"));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> supported = (Map<String, List<String>>) result.data().get("supportedLocationTypesByPaper");
        assertEquals(List.of("SECTION"), supported.get("paper_handle_ready"));

        ArgumentCaptor<ReadingModelGrepSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(ReadingModelGrepSearchRequest.class);
        verify(readingModelGrepSearchService).search(requestCaptor.capture());
        assertEquals(List.of("ready-paper"), requestCaptor.getValue().paperIds());

        String json = objectMapper.writeValueAsString(result.data());
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
    void listPaperLocationsReturnsCurrentReadyModelRefsForPageRangeAndTypes() throws Exception {
        PaperReadingModel readyModel = model("ready-paper", "model-v1");
        readyModel.setPageCount(12);
        PaperLocation page = location("ready-paper", "page_ref_3", PaperLocationType.PAGE);
        page.setPageNumber(3);
        page.setPageEndNumber(3);
        page.setSectionTitle("Methods");
        PaperLocation section = location("ready-paper", "section_ref_methods", PaperLocationType.SECTION);
        section.setPageNumber(3);
        section.setPageEndNumber(5);
        section.setSectionTitle("Methods");
        PaperLocation laterPage = location("ready-paper", "page_ref_9", PaperLocationType.PAGE);
        laterPage.setPageNumber(9);

        when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
                .thenReturn(Optional.of(readyModel));
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
                .thenReturn(List.of(page, section, laterPage));

        ProductToolResult result = adapter.listPaperLocations(
                List.of("paper_handle_ready"),
                new ReadingToolArgumentValidator.PageRange(3, 3),
                List.of(PaperLocationType.PAGE, PaperLocationType.SECTION),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals("list_paper_locations", result.toolName());
        assertEquals(ProductToolEffect.PAPER_DISCOVERY, result.effect());
        assertEquals("OK", result.data().get("status"));
        assertEquals(List.of("paper_handle_ready"), result.data().get("paperHandles"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locations = (List<Map<String, Object>>) result.data().get("locations");
        assertEquals(2, locations.size());
        assertEquals("page_ref_3", locations.get(0).get("locationRef"));
        assertEquals("ready-paper", locations.get(0).get("paperId"));
        assertEquals("PAGE", locations.get(0).get("locationType"));
        assertEquals("Page 3", locations.get(0).get("label"));
        assertEquals("section_ref_methods", locations.get(1).get("locationRef"));
        assertEquals("ready-paper", locations.get(1).get("paperId"));
        assertEquals("SECTION", locations.get(1).get("locationType"));
        assertEquals("Methods", locations.get(1).get("label"));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> supported =
                (Map<String, List<String>>) result.data().get("supportedLocationTypesByPaper");
        assertEquals(List.of("PAGE", "SECTION"), supported.get("paper_handle_ready"));

        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("model-v1"));
        assertFalse(json.contains("modelVersion"));
    }

    @Test
    void getPaperOutlineReturnsCurrentReadyStructureAndSectionRefsOnly() throws Exception {
        PaperReadingModel readyModel = model("ready-paper", "model-v1");
        readyModel.setPageCount(10);
        readyModel.setReadablePageCount(10);
        when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
                .thenReturn(Optional.of(readyModel));
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("ready-paper"))
                .thenReturn(Optional.of(paper("ready-paper", "Agentic Eval Benchmark", "agentic-eval.pdf")));
        when(sectionRepository.findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc("ready-paper", "model-v1"))
                .thenReturn(List.of(section("ready-paper", "model-v1", "sec-methods", "Methods", 1, 3, 5, 1)));
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
                .thenReturn(List.of(
                        location("ready-paper", "page_ref_1", PaperLocationType.PAGE),
                        sectionLocation("ready-paper", "section_ref_methods", "sec-methods", "Methods", 3, 5)
                ));

        ProductToolResult result = adapter.getPaperOutline(
                List.of("paper_handle_ready"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals("get_paper_outline", result.toolName());
        assertEquals(ProductToolEffect.PAPER_DISCOVERY, result.effect());
        assertEquals("OK", result.data().get("status"));
        assertEquals(List.of("paper_handle_ready"), result.data().get("paperHandles"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> papers = (List<Map<String, Object>>) result.data().get("papers");
        assertEquals(1, papers.size());
        assertEquals("ready-paper", papers.get(0).get("paperId"));
        assertEquals("paper_handle_ready", papers.get(0).get("paperHandle"));
        assertEquals("Agentic Eval Benchmark", papers.get(0).get("title"));
        assertEquals("agentic-eval.pdf", papers.get(0).get("originalFilename"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) papers.get(0).get("sections");
        assertEquals(1, sections.size());
        assertEquals("section_ref_methods", sections.get(0).get("sectionRef"));
        assertFalse(sections.get(0).containsKey("sectionRole"));

        String json = objectMapper.writeValueAsString(result.data());
        assertTrue(json.contains("section_ref_methods"));
        assertTrue(json.contains("Methods"));
        assertTrue(json.contains("outlineConfidence"));
        assertFalse(json.contains("model-v1"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("sectionText"));
    }

    @Test
    void listPaperLocationsReturnsCurrentLocationNotFoundForInvisibleUnreadyOrEmptyCurrentLocations() {
        when(handleService.resolvePaperHandle("paper_handle_missing")).thenReturn(Optional.empty());
        when(handleService.resolvePaperHandle("paper_handle_unready")).thenReturn(Optional.of("unready-paper"));
        when(handleService.isPaperVisibleToUser("unready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("unready-paper")).thenReturn(false);
        when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
                .thenReturn(Optional.of(model("ready-paper", "model-v1")));
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
                .thenReturn(List.of(location("ready-paper", "page_ref_1", PaperLocationType.PAGE)));

        ProductToolContext context =
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto());

        assertEquals("CURRENT_LOCATION_NOT_FOUND",
                adapter.listPaperLocations(List.of("paper_handle_missing"), null, List.of(), context)
                        .data().get("status"));
        assertEquals("CURRENT_LOCATION_NOT_FOUND",
                adapter.listPaperLocations(List.of("paper_handle_unready"), null, List.of(), context)
                        .data().get("status"));
        assertEquals("CURRENT_LOCATION_NOT_FOUND",
                adapter.listPaperLocations(
                                List.of("paper_handle_ready"),
                                new ReadingToolArgumentValidator.PageRange(4, 5),
                                List.of(PaperLocationType.PAGE),
                                context)
                        .data().get("status"));
    }

    @Test
    void listPaperLocationsRejectsPageRangeOutsideCurrentModelPageCount() {
        PaperReadingModel readyModel = model("ready-paper", "model-v1");
        readyModel.setPageCount(3);
        when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
        when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
                .thenReturn(Optional.of(readyModel));
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
                .thenReturn(List.of(location("ready-paper", "page_ref_1", PaperLocationType.PAGE)));

        ProductToolResult result = adapter.listPaperLocations(
                List.of("paper_handle_ready"),
                new ReadingToolArgumentValidator.PageRange(4, 4),
                List.of(PaperLocationType.PAGE),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertFalse(result.success());
        assertEquals("INVALID_ARGUMENT", result.data().get("status"));
        assertEquals("pageRange", result.data().get("argument"));
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

    private Paper paper(String paperId, String title, String originalFilename) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setPaperTitle(title);
        paper.setOriginalFilename(originalFilename);
        return paper;
    }

    private PaperSection section(String paperId,
                                 String modelVersion,
                                 String sectionId,
                                 String title,
                                 Integer level,
                                 Integer pageStart,
                                 Integer pageEnd,
                                 Integer displayOrder) {
        PaperSection section = new PaperSection();
        section.setPaperId(paperId);
        section.setModelVersion(modelVersion);
        section.setSectionId(sectionId);
        section.setSectionTitle(title);
        section.setSectionLevel(level);
        section.setPageNumberFrom(pageStart);
        section.setPageNumberTo(pageEnd);
        section.setDisplayOrder(displayOrder);
        section.setSectionText("Internal section text must not be copied.");
        return section;
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

    private PaperLocation sectionLocation(String paperId,
                                          String locationRef,
                                          String sectionId,
                                          String sectionTitle,
                                          Integer pageStart,
                                          Integer pageEnd) {
        PaperLocation location = location(paperId, locationRef, PaperLocationType.SECTION);
        location.setSourceObjectId(sectionId);
        location.setSectionTitle(sectionTitle);
        location.setPageNumber(pageStart);
        location.setPageEndNumber(pageEnd);
        return location;
    }
}
