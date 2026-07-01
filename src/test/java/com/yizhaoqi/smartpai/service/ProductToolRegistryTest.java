package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesExactlyTheConfirmedFirstPhaseProductCatalog() {
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        List<String> names = registry.listTools().stream()
                .map(AgentToolRegistry.AgentTool::name)
                .toList();

        assertEquals(List.of(
                "answer_without_product_state",
                "get_system_state",
                "get_session_scope",
                "list_papers",
                "find_papers",
                "resolve_papers",
                "get_paper_metadata",
                "retrieve_evidence",
                "inspect_reference",
                "inspect_page"
        ), names);
        assertFalse(names.contains("get_library_status"));
        assertFalse(names.contains("discover_papers"));
        assertFalse(names.contains("raw_search"));
        assertFalse(names.contains("sql_query"));
        assertFalse(names.contains("es_query"));
    }

    @Test
    void retrieveEvidenceSchemaExposesProductSemanticsNotRawRetrievalControls() throws Exception {
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        AgentToolRegistry.AgentTool tool = registry.listTools().stream()
                .filter(candidate -> "retrieve_evidence".equals(candidate.name()))
                .findFirst()
                .orElseThrow();

        String schema = objectMapper.writeValueAsString(tool.parameters());

        assertTrue(schema.contains("question"));
        assertTrue(schema.contains("subQuestions"));
        assertTrue(schema.contains("paperConstraints"));
        assertTrue(schema.contains("paperRef"));

        assertFalse(schema.contains("paperIds"));
        assertFalse(schema.contains("topK"));
        assertFalse(schema.contains("searchMode"));
        assertFalse(schema.contains("rerank"));
        assertFalse(schema.contains("pageWindow"));
        assertFalse(schema.contains("esQuery"));
        assertFalse(schema.contains("sql"));
    }

    @Test
    void getSystemStateReturnsProductSemanticStatusesOnly() throws Exception {
        PaperLibraryStatusService statusService = mock(PaperLibraryStatusService.class);
        when(statusService.statusFor(eq("7"), any()))
                .thenReturn(new PaperLibraryStatus(
                        5,
                        2,
                        1,
                        1,
                        1,
                        2,
                        List.of("paper_search metadata pending")
                ));
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper, statusService, null, null);

        ProductToolResult result = registry.execute(
                "get_system_state",
                Map.of("include", List.of("library", "processing", "session")),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.manual(List.of("p1", "p2")))
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.PRODUCT_STATE, result.effect());
        assertEquals(5, result.data().get("productPaperCount"));
        assertEquals(2, result.data().get("searchablePaperCount"));
        assertEquals(2, result.data().get("indexingPendingCount"));

        @SuppressWarnings("unchecked")
        Map<String, Object> byStatus = (Map<String, Object>) result.data().get("papersByProcessingStatus");
        assertEquals(2, byStatus.get("AVAILABLE"));
        assertEquals(2, byStatus.get("PROCESSING"));
        assertEquals(1, byStatus.get("FAILED"));
        assertEquals(0, byStatus.get("NOT_IN_SCOPE"));
        assertEquals(0, byStatus.get("NOT_VISIBLE"));

        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("MinerU"));
        assertFalse(json.contains("OCR"));
        assertFalse(json.contains("Elasticsearch"));
        assertFalse(json.contains("Kafka"));
    }

    @Test
    void answerWithoutProductStateExecutesDirectlyWithoutPrimitiveRegistry() {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        ProductToolResult result = registry.execute(
                "answer_without_product_state",
                Map.of("reason", "smalltalk", "answerDraft", "你好。"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.NO_PRODUCT_STATE, result.effect());
        assertEquals("smalltalk", result.data().get("reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> constraints = (Map<String, Object>) result.data().get("constraints");
        assertEquals(false, constraints.get("mayIncludePaperCounts"));
        assertEquals(false, constraints.get("mayIncludeEvidenceClaims"));
        verify(primitive, never()).execute(any(), any(), any());
    }

    @Test
    void getSessionScopeExecutesDirectlyWithoutPrimitiveRegistry() throws Exception {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        ProductToolResult result = registry.execute(
                "get_session_scope",
                Map.of(),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.manual(List.of("paper-a", "paper-b")))
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.SESSION_SCOPE, result.effect());
        assertEquals("MANUAL_SOURCE", result.data().get("scopeMode"));
        assertEquals(true, result.data().get("scopeLocked"));
        assertEquals(2, result.data().get("sourcePaperCount"));
        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("paper-a"));
        assertFalse(json.contains("paper-b"));
        assertFalse(json.contains("MinerU"));
        assertFalse(json.contains("Elasticsearch"));
        verify(primitive, never()).execute(any(), any(), any());
    }

    @Test
    void getSessionScopeDoesNotEmitNullValuesForAutoScope() {
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        ProductToolResult result = registry.execute(
                "get_session_scope",
                Map.of(),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.SESSION_SCOPE, result.effect());
        assertEquals("AUTO_SOURCE", result.data().get("scopeMode"));
        assertEquals(false, result.data().get("sourcePaperCountKnown"));
        assertFalse(result.data().containsKey("sourcePaperCount"));
    }

    @Test
    void executionRejectsRawPaperIdsAndRetrievalKnobs() {
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        ProductToolResult result = registry.execute(
                "retrieve_evidence",
                Map.of(
                        "question", "What is LoRA?",
                        "paperIds", List.of("raw-paper-id"),
                        "topK", 20
                ),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertFalse(result.success());
        assertEquals("forbidden_product_tool_argument", result.data().get("error"));
    }

    @Test
    void executionRejectsNestedRawPaperIdsAndRetrievalKnobs() {
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        ProductToolResult rawIdResult = registry.execute(
                "retrieve_evidence",
                Map.of(
                        "question", "What is LoRA?",
                        "paperConstraints", List.of(Map.of(
                                "paperRef", "P1",
                                "paperId", "raw-paper-id"
                        ))
                ),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );
        ProductToolResult retrievalKnobResult = registry.execute(
                "retrieve_evidence",
                Map.of(
                        "question", "What is LoRA?",
                        "options", Map.of("topK", 20)
                ),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertFalse(rawIdResult.success());
        assertEquals("forbidden_product_tool_argument", rawIdResult.data().get("error"));
        assertEquals("paperId", rawIdResult.data().get("argument"));
        assertFalse(retrievalKnobResult.success());
        assertEquals("forbidden_product_tool_argument", retrievalKnobResult.data().get("error"));
        assertEquals("topK", retrievalKnobResult.data().get("argument"));
    }

    @Test
    void executionRejectsLegacyPrimitiveBudgetAndPageWindowKnobs() {
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        ProductToolResult budgetResult = registry.execute(
                "retrieve_evidence",
                Map.of(
                        "question", "What is LoRA?",
                        "budgetProfile", "deep_audit"
                ),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );
        ProductToolResult pageWindowResult = registry.execute(
                "inspect_page",
                Map.of(
                        "paperRef", "P1",
                        "pageNumber", 3,
                        "windowRadius", 4
                ),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertFalse(budgetResult.success());
        assertEquals("forbidden_product_tool_argument", budgetResult.data().get("error"));
        assertEquals("budgetProfile", budgetResult.data().get("argument"));
        assertFalse(pageWindowResult.success());
        assertEquals("forbidden_product_tool_argument", pageWindowResult.data().get("error"));
        assertEquals("windowRadius", pageWindowResult.data().get("argument"));
    }

    @Test
    void executionRejectsPluralResolvedPaperIdsFromPublicToolInput() {
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper);

        ProductToolResult result = registry.execute(
                "retrieve_evidence",
                Map.of(
                        "question", "What is LoRA?",
                        "resolvedPaperIds", List.of("raw-paper-id")
                ),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertFalse(result.success());
        assertEquals("forbidden_product_tool_argument", result.data().get("error"));
        assertEquals("resolvedPaperIds", result.data().get("argument"));
    }

    @Test
    void retrieveEvidenceResolvesPaperRefConstraintsThroughPersistentRegistryBeforeDirectExecution() {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        EvidenceToolExecutor evidenceToolExecutor = mock(EvidenceToolExecutor.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("P1"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER)
        )).thenReturn(Optional.of(new ConversationReferenceRegistry.ResolvedReference(
                "conversation-1",
                "AUTO_SOURCE:1",
                "generation-1",
                "P1",
                com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER,
                "raw-paper-id",
                Map.of("paperRef", "P1", "paperId", "raw-paper-id"),
                Map.of("paperRef", "P1", "title", "LoRA")
        )));
        when(evidenceToolExecutor.execute(eq("7"), eq("conversation-1"), any(), any()))
                .thenReturn(new EvidenceToolResult(
                        PlannerActionType.SEARCH_EVIDENCE,
                        new EvidenceLedger(
                                List.of(new PaperSource("raw-paper-id", "LoRA", "lora.pdf")),
                                List.of(new EvidenceItem(
                                        "ev_1",
                                        "raw-paper-id",
                                        "LoRA",
                                        "lora.pdf",
                                        3,
                                        42,
                                        "TEXT",
                                        "Method",
                                        "Low-rank adaptation",
                                        null,
                                        0.9
                                )),
                                new LedgerDiagnostics(1, 1, 1, "EXHAUSTED")
                        ),
                        ""
                ));
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                null,
                null,
                evidenceToolExecutor,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "retrieve_evidence",
                Map.of(
                        "question", "LoRA 的方法是什么",
                        "paperConstraints", List.of(Map.of("paperRef", "P1"))
                ),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto())
        );

        assertTrue(result.success());
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<PlannerAction> actionCaptor = ArgumentCaptor.forClass(PlannerAction.class);
        ArgumentCaptor<SourceScope> scopeCaptor = ArgumentCaptor.forClass(SourceScope.class);
        verify(evidenceToolExecutor).execute(eq("7"), eq("conversation-1"), actionCaptor.capture(), scopeCaptor.capture());
        assertEquals(List.of("raw-paper-id"), actionCaptor.getValue().paperIds());
        assertEquals(List.of("raw-paper-id"), scopeCaptor.getValue().paperIds());
        String json = assertDoesNotThrowJson(result.data());
        assertFalse(json.contains("raw-paper-id"));
        assertEquals("raw-paper-id", result.evidencePayloads().get("ev_1").get("paperId"));
        verify(primitive, never()).execute(eq("retrieve_evidence"), argsCaptor.capture(), any());
    }

    @Test
    void inspectReferenceUsesPersistentReferenceRegistry() {
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("citation_generation-1_1"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.CITATION)
        )).thenReturn(Optional.of(new ConversationReferenceRegistry.ResolvedReference(
                "conversation-1",
                "AUTO_SOURCE:1",
                "generation-1",
                "citation_generation-1_1",
                com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.CITATION,
                "ev_1",
                Map.of("evidenceRef", "ev_1"),
                Map.of("referenceNumber", 1, "paperTitle", "LoRA", "pageNumber", 3)
        )));
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper, null, null, null, referenceRegistry);

        ProductToolResult result = registry.execute(
                "inspect_reference",
                Map.of("citationRef", "citation_generation-1_1"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.REFERENCE, result.effect());
        assertEquals(true, result.data().get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> reference = (Map<String, Object>) result.data().get("reference");
        assertEquals("LoRA", reference.get("paperTitle"));
        assertEquals(3, reference.get("pageNumber"));
    }

    @Test
    void getPaperMetadataUsesPersistentPaperRefsWithoutPrimitiveRegistry() throws Exception {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("P1"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER)
        )).thenReturn(Optional.of(new ConversationReferenceRegistry.ResolvedReference(
                "conversation-1",
                "AUTO_SOURCE:1",
                "generation-1",
                "P1",
                com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER,
                "raw-paper-id",
                Map.of("paperRef", "P1", "paperId", "raw-paper-id"),
                Map.of(
                        "paperRef", "P1",
                        "paperId", "raw-paper-id",
                        "title", "LoRA",
                        "originalFilename", "lora.pdf"
                )
        )));
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper, null, null, null, referenceRegistry);

        ProductToolResult result = registry.execute(
                "get_paper_metadata",
                Map.of("paperRefs", List.of("P1")),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.PAPER_METADATA, result.effect());
        assertEquals(1, result.data().get("total"));
        String json = objectMapper.writeValueAsString(result.data());
        assertTrue(json.contains("LoRA"));
        assertFalse(json.contains("raw-paper-id"));
        assertFalse(json.contains("\"paperId\""));
        verify(primitive, never()).execute(any(), any(), any());
    }

    @Test
    void getPaperMetadataReportsMissingRefsWithoutGuessing() {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("P_missing"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER)
        )).thenReturn(Optional.empty());
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper, null, null, null, referenceRegistry);

        ProductToolResult result = registry.execute(
                "get_paper_metadata",
                Map.of("paperRefs", List.of("P_missing")),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.PAPER_METADATA, result.effect());
        assertEquals(0, result.data().get("total"));
        assertEquals(List.of("P_missing"), result.data().get("missingPaperRefs"));
        verify(primitive, never()).execute(any(), any(), any());
    }

    @Test
    void inspectReferenceDoesNotExposeRawPaperOrChunkIdsToLlm() throws Exception {
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("citation_generation-1_1"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.CITATION)
        )).thenReturn(Optional.of(new ConversationReferenceRegistry.ResolvedReference(
                "conversation-1",
                "AUTO_SOURCE:1",
                "generation-1",
                "citation_generation-1_1",
                com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.CITATION,
                "raw-chunk-id-42",
                Map.of("evidenceRef", "ev_1", "chunkId", 42),
                Map.of(
                        "referenceNumber", 1,
                        "paperTitle", "LoRA",
                        "pageNumber", 3,
                        "paperId", "raw-paper-id",
                        "chunkId", 42,
                        "evidenceRef", "ev_1"
                )
        )));
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper, null, null, null, referenceRegistry);

        ProductToolResult result = registry.execute(
                "inspect_reference",
                Map.of("citationRef", "citation_generation-1_1"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("raw-paper-id"));
        assertFalse(json.contains("raw-chunk-id-42"));
        assertFalse(json.contains("\"paperId\""));
        assertFalse(json.contains("\"chunkId\""));
        assertFalse(json.contains("\"sourceEntityId\""));
    }

    @Test
    void directEvidenceRawIdsAreHiddenFromProductToolDataButKeptForHarnessPayload() {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        EvidenceToolExecutor evidenceToolExecutor = mock(EvidenceToolExecutor.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(evidenceToolExecutor.execute(eq("7"), eq("conversation-1"), any(), any()))
                .thenReturn(new EvidenceToolResult(
                        PlannerActionType.SEARCH_EVIDENCE,
                        new EvidenceLedger(
                                List.of(new PaperSource("raw-paper-id", "LoRA", "lora.pdf")),
                                List.of(new EvidenceItem(
                                        "ev_1",
                                        "raw-paper-id",
                                        "LoRA",
                                        "lora.pdf",
                                        null,
                                        42,
                                        "TEXT",
                                        "Method",
                                        "Low-rank adaptation",
                                        null,
                                        0.9
                                )),
                                new LedgerDiagnostics(1, 1, 1, "EXHAUSTED")
                        ),
                        ""
                ));
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                null,
                null,
                evidenceToolExecutor,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "retrieve_evidence",
                Map.of("question", "LoRA 的方法是什么"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.data().get("evidence");
        assertFalse(evidence.get(0).containsKey("paperId"));
        assertFalse(evidence.get(0).containsKey("chunkId"));
        assertEquals("raw-paper-id", result.evidencePayloads().get("ev_1").get("paperId"));
        assertEquals(42, result.evidencePayloads().get("ev_1").get("chunkId"));
        verify(primitive, never()).execute(eq("retrieve_evidence"), any(), any());
    }

    @Test
    void listPapersExecutesDirectlyPersistsPaperRefsAndHidesRawIds() throws Exception {
        Paper paper = paper("raw-paper-id", "LoRA", "lora.pdf", Paper.VECTORIZATION_STATUS_COMPLETED);
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(paper));
        when(searchabilityService.isSearchable(paper)).thenReturn(true);
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                paperService,
                searchabilityService,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "list_papers",
                Map.of(),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.manual(List.of("raw-paper-id")))
        );

        assertTrue(result.success());
        String json = objectMapper.writeValueAsString(result.data());
        assertTrue(json.contains("paper_"));
        assertFalse(json.contains("raw-paper-id"));
        assertFalse(json.contains("\"paperId\""));

        ArgumentCaptor<ConversationReferenceRegistry.ReferenceInput> captor =
                ArgumentCaptor.forClass(ConversationReferenceRegistry.ReferenceInput.class);
        verify(referenceRegistry).save(captor.capture());
        ConversationReferenceRegistry.ReferenceInput saved = captor.getValue();
        assertEquals("conversation-1", saved.conversationId());
        assertEquals("generation-1", saved.turnId());
        assertTrue(saved.refId().startsWith("paper_"));
        assertEquals(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER, saved.refType());
        assertEquals("raw-paper-id", saved.sourceEntityId());
        assertEquals("raw-paper-id", saved.sourcePayload().get("paperId"));
        assertFalse(objectMapper.writeValueAsString(saved.displayPayload()).contains("raw-paper-id"));
        verify(primitive, never()).execute(eq("list_papers"), any(), any());
    }

    @Test
    void findPapersUsesSemanticPaperDiscoveryForRecommendationTopics() throws Exception {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        PaperQueryPlanner.RetrievalPlan plan = new PaperQueryPlanner.RetrievalPlan(
                "agent eval",
                "agent eval",
                PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH,
                List.of("agent eval"),
                List.of(),
                List.of()
        );
        SearchResult survey = paperSearchResult(
                "paper-survey",
                "Evaluation and Benchmarking of LLM Agents: A Survey",
                "2507.21504.pdf",
                "Title and abstract indicate this is a survey of LLM agent evaluation and benchmarks.",
                12.5
        );
        SearchResult mcpEval = paperSearchResult(
                "paper-mcpeval",
                "MCPEval: Automatic MCP-based Deep Evaluation for AI Agent Models",
                "2507.12806.pdf",
                "Metadata and retrieved candidate text indicate MCP-based evaluation for AI agent models.",
                10.0
        );
        when(retrievalService.discoverPapers(
                eq("agent eval"),
                eq("7"),
                any(),
                eq(List.of("paper-survey", "paper-mcpeval"))
        )).thenReturn(new PaperRetrievalService.RetrievalResult(
                plan,
                List.of(survey, mcpEval),
                Map.of("agent eval", 2),
                new PaperRetrievalService.RetrievalDiagnostics(
                        2,
                        2,
                        2,
                        PaperRetrievalService.StopReason.EXHAUSTED
                )
        ));
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                null,
                null,
                retrievalService,
                null,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "find_papers",
                Map.of("query", "agent eval", "limit", 10),
                new ProductToolContext(7L, "conversation-1", "generation-1",
                        SourceScope.manual(List.of("paper-survey", "paper-mcpeval")))
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.PAPER_DISCOVERY, result.effect());
        assertEquals(2, result.data().get("total"));
        assertEquals(2, result.data().get("returned"));
        assertEquals("semantic_paper_search", result.data().get("selectionBasis"));
        assertEquals(false, result.data().get("citationsAvailable"));
        assertEquals(true, result.data().get("requiresEvidenceToolForPaperClaims"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> papers = (List<Map<String, Object>>) result.data().get("papers");
        String json = objectMapper.writeValueAsString(papers);
        assertTrue(json.contains("Evaluation and Benchmarking of LLM Agents"));
        assertTrue(json.contains("MCPEval"));
        assertTrue(json.contains("paper_"));
        assertFalse(json.contains("paper-survey"));
        assertFalse(json.contains("paper-mcpeval"));
        verify(retrievalService).discoverPapers(eq("agent eval"), eq("7"), any(),
                eq(List.of("paper-survey", "paper-mcpeval")));
        verify(referenceRegistry, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void listPapersFilteredNoMatchDoesNotHideWholeScopePaperCount() {
        Paper lora = paper("paper-lora", "LoRA: Low-Rank Adaptation of Large Language Models", "lora.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED);
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(lora));
        when(searchabilityService.isSearchable(lora)).thenReturn(true);
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                paperService,
                searchabilityService,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "list_papers",
                Map.of("titleQuery", "quantum planning"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(0, result.data().get("total"));
        assertEquals(0, result.data().get("filteredTotal"));
        assertEquals(1, result.data().get("scopePaperCount"));
        assertEquals(true, result.data().get("filtered"));
        verify(referenceRegistry, never()).save(any());
    }

    @Test
    void paperRefPersistenceFailureFailsTheDirectListPapersToolResult() {
        Paper paper = paper("raw-paper-id", "LoRA", "lora.pdf", Paper.VECTORIZATION_STATUS_COMPLETED);
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(paper));
        when(searchabilityService.isSearchable(paper)).thenReturn(true);
        when(referenceRegistry.save(any())).thenThrow(new RuntimeException("db down"));
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                paperService,
                searchabilityService,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "list_papers",
                Map.of(),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertFalse(result.success());
        assertEquals("reference_registry_persistence_failed", result.data().get("error"));
        verify(primitive, never()).execute(eq("list_papers"), any(), any());
    }

    @Test
    void resolvePapersResolvesPersistentPaperRefsDirectly() throws Exception {
        Paper paper = paper("raw-paper-id", "LoRA", "lora.pdf", Paper.VECTORIZATION_STATUS_COMPLETED);
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(paper));
        when(searchabilityService.isSearchable(paper)).thenReturn(true);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("paper_known"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER)
        )).thenReturn(Optional.of(new ConversationReferenceRegistry.ResolvedReference(
                "conversation-1",
                "AUTO_SOURCE:1",
                "generation-1",
                "paper_known",
                com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER,
                "raw-paper-id",
                Map.of("paperRef", "paper_known", "paperId", "raw-paper-id"),
                Map.of("paperRef", "paper_known", "title", "LoRA")
        )));
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                paperService,
                searchabilityService,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "resolve_papers",
                Map.of("paperRefs", List.of("paper_known")),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.PAPER_RESOLUTION, result.effect());
        assertEquals(true, result.data().get("resolved"));
        assertEquals(false, result.data().get("ambiguous"));
        String json = objectMapper.writeValueAsString(result.data());
        assertTrue(json.contains("LoRA"));
        assertTrue(json.contains("paper_"));
        assertFalse(json.contains("raw-paper-id"));
        assertFalse(json.contains("\"paperId\""));
        verify(primitive, never()).execute(eq("resolve_papers"), any(), any());
    }

    @Test
    void resolvePapersReturnsAmbiguousTitleQueryCandidatesWithoutGuessing() throws Exception {
        Paper first = paper("raw-paper-id-1", "LoRA", "lora.pdf", Paper.VECTORIZATION_STATUS_COMPLETED);
        Paper second = paper("raw-paper-id-2", "LoRA Follow Up", "lora-followup.pdf", Paper.VECTORIZATION_STATUS_COMPLETED);
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(first, second));
        when(searchabilityService.isSearchable(first)).thenReturn(true);
        when(searchabilityService.isSearchable(second)).thenReturn(true);
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                paperService,
                searchabilityService,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "resolve_papers",
                Map.of("titleQuery", "LoRA"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(false, result.data().get("resolved"));
        assertEquals(true, result.data().get("ambiguous"));
        assertEquals(2, result.data().get("total"));
        String json = objectMapper.writeValueAsString(result.data());
        assertTrue(json.contains("LoRA Follow Up"));
        assertFalse(json.contains("raw-paper-id-1"));
        assertFalse(json.contains("raw-paper-id-2"));
        verify(referenceRegistry, org.mockito.Mockito.times(2)).save(any());
        verify(primitive, never()).execute(eq("resolve_papers"), any(), any());
    }

    @Test
    void resolvePapersSupportsExplicitTitleRegexInsideLockedScope() throws Exception {
        Paper inScope = paper("raw-paper-id-1", "LoRA", "lora.pdf", Paper.VECTORIZATION_STATUS_COMPLETED);
        Paper outOfScope = paper("raw-paper-id-2", "LoRA Follow Up", "lora-followup.pdf", Paper.VECTORIZATION_STATUS_COMPLETED);
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(inScope, outOfScope));
        when(searchabilityService.isSearchable(inScope)).thenReturn(true);
        when(searchabilityService.isSearchable(outOfScope)).thenReturn(true);
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                paperService,
                searchabilityService,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "resolve_papers",
                Map.of("titleRegex", "^LoRA"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.manual(List.of("raw-paper-id-1")))
        );

        assertTrue(result.success());
        assertEquals(true, result.data().get("resolved"));
        assertEquals(false, result.data().get("ambiguous"));
        assertEquals(1, result.data().get("total"));
        String json = objectMapper.writeValueAsString(result.data());
        assertTrue(json.contains("LoRA"));
        assertFalse(json.contains("LoRA Follow Up"));
        assertFalse(json.contains("raw-paper-id-1"));
        assertFalse(json.contains("raw-paper-id-2"));
        verify(referenceRegistry).save(any());
        verify(primitive, never()).execute(eq("resolve_papers"), any(), any());
    }

    @Test
    void resolvePapersReportsMissingTitleQueryWithoutFallback() {
        Paper paper = paper("raw-paper-id", "LoRA", "lora.pdf", Paper.VECTORIZATION_STATUS_COMPLETED);
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(paper));
        when(searchabilityService.isSearchable(paper)).thenReturn(true);
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                paperService,
                searchabilityService,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "resolve_papers",
                Map.of("titleQuery", "QASPER"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(false, result.data().get("resolved"));
        assertEquals(false, result.data().get("ambiguous"));
        assertEquals(0, result.data().get("total"));
        assertEquals("no_matching_papers", result.data().get("reason"));
        verify(referenceRegistry, never()).save(any());
        verify(primitive, never()).execute(eq("resolve_papers"), any(), any());
    }

    @Test
    void evidencePagesReturnedByDirectEvidenceToolArePersistedAsPageRefs() throws Exception {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        EvidenceToolExecutor evidenceToolExecutor = mock(EvidenceToolExecutor.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(evidenceToolExecutor.execute(eq("7"), eq("conversation-1"), any(), any()))
                .thenReturn(new EvidenceToolResult(
                        PlannerActionType.SEARCH_EVIDENCE,
                        new EvidenceLedger(
                                List.of(new PaperSource("raw-paper-id", "LoRA", "lora.pdf")),
                                List.of(new EvidenceItem(
                                        "ev_1",
                                        "raw-paper-id",
                                        "LoRA",
                                        "lora.pdf",
                                        3,
                                        42,
                                        "TEXT",
                                        "Method",
                                        "LoRA freezes the pretrained weights.",
                                        null,
                                        0.9
                                )),
                                new LedgerDiagnostics(1, 1, 1, "EXHAUSTED")
                        ),
                        ""
                ));
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                null,
                null,
                evidenceToolExecutor,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "retrieve_evidence",
                Map.of("question", "LoRA 的方法是什么"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.manual(List.of("raw-paper-id")))
        );

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.data().get("evidence");
        assertTrue(String.valueOf(evidence.get(0).get("pageRef")).startsWith("page_paper_"));
        String llmVisibleJson = objectMapper.writeValueAsString(result.data());
        assertFalse(llmVisibleJson.contains("raw-paper-id"));
        assertFalse(llmVisibleJson.contains("\"paperId\""));
        assertFalse(llmVisibleJson.contains("\"chunkId\""));

        ArgumentCaptor<ConversationReferenceRegistry.ReferenceInput> captor =
                ArgumentCaptor.forClass(ConversationReferenceRegistry.ReferenceInput.class);
        verify(referenceRegistry, org.mockito.Mockito.times(2)).save(captor.capture());
        ConversationReferenceRegistry.ReferenceInput savedPage = captor.getAllValues().stream()
                .filter(input -> com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAGE.equals(input.refType()))
                .findFirst()
                .orElseThrow();
        assertEquals("conversation-1", savedPage.conversationId());
        assertEquals("generation-1", savedPage.turnId());
        assertTrue(savedPage.refId().startsWith("page_paper_"));
        assertEquals("raw-paper-id:page:3", savedPage.sourceEntityId());
        assertEquals("raw-paper-id", savedPage.sourcePayload().get("paperId"));
        assertEquals(3, savedPage.sourcePayload().get("pageNumber"));
        assertFalse(objectMapper.writeValueAsString(savedPage.displayPayload()).contains("raw-paper-id"));
        verify(primitive, never()).execute(eq("retrieve_evidence"), any(), any());
    }

    @Test
    void inspectPageUsesPersistentPageRefWithoutExposingRawIds() throws Exception {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("page_P1_3"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAGE)
        )).thenReturn(Optional.of(new ConversationReferenceRegistry.ResolvedReference(
                "conversation-1",
                "MANUAL_SOURCE:1",
                "generation-1",
                "page_P1_3",
                com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAGE,
                "raw-paper-id:page:3",
                Map.of("paperId", "raw-paper-id", "pageNumber", 3, "evidenceRef", "ev_1"),
                Map.of(
                        "pageRef", "page_P1_3",
                        "evidenceRef", "ev_1",
                        "paperRef", "P1",
                        "paperId", "raw-paper-id",
                        "paperTitle", "LoRA",
                        "pageNumber", 3,
                        "chunkId", 42,
                        "matchedText", "LoRA freezes the pretrained weights."
                )
        )));
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper, null, null, null, referenceRegistry);

        ProductToolResult result = registry.execute(
                "inspect_page",
                Map.of("pageRef", "page_P1_3"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.manual(List.of("raw-paper-id")))
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.PAGE, result.effect());
        assertEquals(true, result.data().get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) result.data().get("page");
        assertEquals("page_P1_3", page.get("pageRef"));
        assertEquals("LoRA", page.get("paperTitle"));
        assertEquals(3, page.get("pageNumber"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.data().get("evidence");
        assertEquals("ev_1", evidence.get(0).get("evidenceRef"));
        assertEquals("raw-paper-id", result.evidencePayloads().get("ev_1").get("paperId"));
        String json = objectMapper.writeValueAsString(result.data());
        assertFalse(json.contains("raw-paper-id"));
        assertFalse(json.contains("\"paperId\""));
        assertFalse(json.contains("\"chunkId\""));
        verify(primitive, never()).execute(eq("inspect_page"), any(), any());
    }

    @Test
    void inspectPageWithPaperRefResolvesPersistentPaperRefBeforeDirectPageInspection() {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        EvidenceToolExecutor evidenceToolExecutor = mock(EvidenceToolExecutor.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("P1"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER)
        )).thenReturn(Optional.of(new ConversationReferenceRegistry.ResolvedReference(
                "conversation-1",
                "MANUAL_SOURCE:1",
                "generation-1",
                "P1",
                com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAPER,
                "raw-paper-id",
                Map.of("paperId", "raw-paper-id", "paperRef", "P1"),
                Map.of("paperRef", "P1", "title", "LoRA")
        )));
        when(evidenceToolExecutor.execute(eq("7"), eq("conversation-1"), any(), any()))
                .thenReturn(new EvidenceToolResult(
                        PlannerActionType.INSPECT_PAGE,
                        new EvidenceLedger(
                                List.of(new PaperSource("raw-paper-id", "LoRA", "lora.pdf")),
                                List.of(new EvidenceItem(
                                        "ev_page",
                                        "raw-paper-id",
                                        "LoRA",
                                        "lora.pdf",
                                        3,
                                        42,
                                        "TEXT",
                                        "Method",
                                        "page context",
                                        null,
                                        0.9
                                )),
                                new LedgerDiagnostics(1, 1, 1, "EXHAUSTED")
                        ),
                        ""
                ));
        ProductToolRegistry registry = new ProductToolRegistry(
                objectMapper,
                null,
                null,
                null,
                null,
                null,
                evidenceToolExecutor,
                referenceRegistry
        );

        ProductToolResult result = registry.execute(
                "inspect_page",
                Map.of("paperRef", "P1", "pageNumber", 3),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.manual(List.of("raw-paper-id")))
        );

        assertTrue(result.success());
        ArgumentCaptor<PlannerAction> actionCaptor = ArgumentCaptor.forClass(PlannerAction.class);
        ArgumentCaptor<SourceScope> scopeCaptor = ArgumentCaptor.forClass(SourceScope.class);
        verify(evidenceToolExecutor).execute(eq("7"), eq("conversation-1"), actionCaptor.capture(), scopeCaptor.capture());
        assertEquals(List.of("raw-paper-id"), actionCaptor.getValue().paperIds());
        assertEquals(3, actionCaptor.getValue().pageNumber());
        assertEquals(List.of("raw-paper-id"), scopeCaptor.getValue().paperIds());
        String json = assertDoesNotThrowJson(result.data());
        assertFalse(json.contains("raw-paper-id"));
        assertEquals("raw-paper-id", result.evidencePayloads().get("ev_page").get("paperId"));
        verify(primitive, never()).execute(eq("inspect_page"), any(), any());
    }

    @Test
    void inspectPageWithMissingPageRefDoesNotFallbackToPrimitivePageGuessing() {
        PaperConversationToolRegistry primitive = mock(PaperConversationToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        when(referenceRegistry.resolve(
                eq("conversation-1"),
                any(),
                eq("page_missing"),
                eq(com.yizhaoqi.smartpai.model.PaperConversationReference.RefType.PAGE)
        )).thenReturn(Optional.empty());
        ProductToolRegistry registry = new ProductToolRegistry(objectMapper, null, null, null, referenceRegistry);

        ProductToolResult result = registry.execute(
                "inspect_page",
                Map.of("pageRef", "page_missing"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto())
        );

        assertTrue(result.success());
        assertEquals(ProductToolEffect.PAGE, result.effect());
        assertEquals(false, result.data().get("found"));
        assertEquals("reference_not_found", result.data().get("reason"));
        verify(primitive, never()).execute(eq("inspect_page"), any(), any());
    }

    private String assertDoesNotThrowJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private Paper paper(String paperId, String title, String filename, String vectorizationStatus) {
        Paper paper = new Paper();
        paper.setId(Math.abs((long) paperId.hashCode()));
        paper.setPaperId(paperId);
        paper.setPaperTitle(title);
        paper.setOriginalFilename(filename);
        paper.setVectorizationStatus(vectorizationStatus);
        paper.setStatus(Paper.STATUS_COMPLETED);
        return paper;
    }

    private SearchResult paperSearchResult(String paperId,
                                           String title,
                                           String filename,
                                           String matchedText,
                                           double score) {
        SearchResult result = new SearchResult(
                paperId,
                0,
                matchedText,
                score,
                "7",
                "default",
                false,
                title,
                filename,
                null,
                null,
                "PAPER_METADATA",
                matchedText,
                "PAPER",
                "title/abstract",
                null,
                null,
                "paper_search",
                null
        );
        result.setRankReason("literature-search:" + title);
        result.setRetrievalRoute("PAPER_LEVEL");
        return result;
    }
}
