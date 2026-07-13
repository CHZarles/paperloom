package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingToolRegistryTest {

    @Mock
    private ProductReadingToolAdapter adapter;

    private ProductReadingToolRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new ProductReadingToolRegistry(adapter, new ReadingToolArgumentValidator());
    }

    @Test
    void exposesOnlySourceQuoteReadingToolsWithClosedSchemas() throws Exception {
        List<ToolDefinition> tools = registry.listTools();
        List<String> names = tools.stream().map(ToolDefinition::name).toList();
        String schemaJson = objectMapper.writeValueAsString(tools.stream()
                .map(ToolDefinition::parameters)
                .toList());

        assertEquals(List.of(
                "get_session_state",
                "list_papers",
                "search_paper_candidates",
                "find_papers_by_identity",
                "get_paper_outline",
                "list_paper_locations",
                "find_reading_locations",
                "read_locations",
                "trace_source_quotes"
        ), names);
        ToolDefinition sessionTool = tools.stream()
                .filter(tool -> "get_session_state".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(((Map<?, ?>) sessionTool.parameters().get("properties")).isEmpty());
        ToolDefinition listTool = tools.stream()
                .filter(tool -> "list_papers".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> paperListProperties =
                (Map<String, Object>) listTool.parameters().get("properties");
        assertTrue(paperListProperties.containsKey("filters"));
        assertTrue(paperListProperties.containsKey("includeFacets"));
        assertTrue(paperListProperties.containsKey("sort"));
        ToolDefinition identityTool = tools.stream()
                .filter(tool -> "find_papers_by_identity".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> identityProperties =
                (Map<String, Object>) identityTool.parameters().get("properties");
        assertEquals(List.of("identityHints"), identityTool.parameters().get("required"));
        assertTrue(identityProperties.containsKey("identityHints"));
        @SuppressWarnings("unchecked")
        Map<String, Object> identityHints =
                (Map<String, Object>) identityProperties.get("identityHints");
        assertEquals(false, identityHints.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> identityHintProperties =
                (Map<String, Object>) identityHints.get("properties");
        assertTrue(identityHintProperties.containsKey("titleContains"));
        assertTrue(identityHintProperties.containsKey("doiExact"));
        assertTrue(identityHintProperties.containsKey("arxivIdExact"));
        assertTrue(identityHintProperties.containsKey("year"));
        assertFalse(identityHintProperties.containsKey("queryText"));
        assertFalse(identityHintProperties.containsKey("paperHandle"));
        ToolDefinition locationListTool = tools.stream()
                .filter(tool -> "list_paper_locations".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> listProperties =
                (Map<String, Object>) locationListTool.parameters().get("properties");
        assertTrue(listProperties.containsKey("pageRange"));
        assertTrue(listProperties.containsKey("locationTypes"));
        assertFalse(listProperties.containsKey("queryText"));
        assertFalse(listProperties.containsKey("query"));
        ToolDefinition locationSearchTool = tools.stream()
                .filter(tool -> "find_reading_locations".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> locationSearchProperties =
                (Map<String, Object>) locationSearchTool.parameters().get("properties");
        assertTrue(locationSearchProperties.containsKey("queryPlan"));
        assertFalse(locationSearchProperties.containsKey("queryText"));
        assertEquals(List.of("paperHandles", "queryPlan"), locationSearchTool.parameters().get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> queryPlanSchema =
                (Map<String, Object>) locationSearchProperties.get("queryPlan");
        assertEquals(
                List.of("queryText", "intent", "sourceLanguage", "retrievalLanguage", "sectionRoles"),
                queryPlanSchema.get("required")
        );
        assertEquals(false, queryPlanSchema.get("additionalProperties"));
        assertTrue(schemaJson.contains("\"additionalProperties\":false"));
        assertFalse(schemaJson.contains("limit"));
        assertFalse(schemaJson.contains("topK"));
        assertFalse(schemaJson.contains("pageSize"));
        assertFalse(schemaJson.contains("maxChars"));
        assertFalse(schemaJson.contains("modelVersion"));
        assertFalse(schemaJson.contains("indexName"));
        assertFalse(schemaJson.contains("chunkRef"));
        assertFalse(names.contains("retrieve_evidence"));
        assertFalse(names.contains("inspect_reference"));
    }

    @Test
    void rejectsUnsupportedToolsAndForbiddenArguments() {
        ProductToolResult unsupported = registry.execute(
                "retrieve_evidence",
                Map.of("queryText", "agentic eval"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );
        ProductToolResult forbidden = registry.execute(
                "search_paper_candidates",
                Map.of("queryText", "agentic eval", "limit", 10),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );
        ProductToolResult invalidIdentity = registry.execute(
                "find_papers_by_identity",
                Map.of("identityHints", Map.of("year", 2021)),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertFalse(unsupported.success());
        assertEquals("unsupported_reading_tool", unsupported.data().get("error"));
        assertFalse(forbidden.success());
        assertEquals("INVALID_ARGUMENT", forbidden.data().get("status"));
        assertEquals("limit", forbidden.data().get("argument"));
        assertFalse(invalidIdentity.success());
        assertEquals("INVALID_ARGUMENT", invalidIdentity.data().get("status"));
        assertEquals("identityHints", invalidIdentity.data().get("argument"));
    }

    @Test
    void delegatesValidatedSearchIdentityLocationAndReadCalls() {
        ProductToolContext context = new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto());
        ProductToolResult searchResult = new ProductToolResult(
                "search_paper_candidates",
                true,
                Map.of("status", "NO_MATCH", "items", List.of()),
                ProductToolEffect.PAPER_DISCOVERY
        );
        ProductToolResult identityResult = new ProductToolResult(
                "find_papers_by_identity",
                true,
                Map.of("status", "OK", "matches", List.of()),
                ProductToolEffect.PAPER_RESOLUTION
        );
        ProductToolResult sessionResult = new ProductToolResult(
                "get_session_state",
                true,
                Map.of("status", "OK", "searchScope", Map.of("readablePaperCount", 1)),
                ProductToolEffect.PRODUCT_STATE
        );
        ProductToolResult paperListResult = new ProductToolResult(
                "list_papers",
                true,
                Map.of("status", "OK", "items", List.of()),
                ProductToolEffect.PAPER_LIST
        );
        ProductToolResult locationResult = new ProductToolResult(
                "find_reading_locations",
                true,
                Map.of("status", "NO_MATCH", "candidates", List.of()),
                ProductToolEffect.PAPER_DISCOVERY
        );
        ProductToolResult listedLocationsResult = new ProductToolResult(
                "list_paper_locations",
                true,
                Map.of("status", "OK", "locations", List.of()),
                ProductToolEffect.PAPER_DISCOVERY
        );
        ProductToolResult outlineResult = new ProductToolResult(
                "get_paper_outline",
                true,
                Map.of("status", "OK", "papers", List.of()),
                ProductToolEffect.PAPER_DISCOVERY
        );
        ProductToolResult readResult = new ProductToolResult(
                "read_locations",
                true,
                Map.of("sourceQuotes", List.of(), "readStatus", List.of()),
                ProductToolEffect.EVIDENCE
        );
        ProductToolResult traceResult = new ProductToolResult(
                "trace_source_quotes",
                true,
                Map.of("sourceQuotes", List.of(), "traceStatus", List.of()),
                ProductToolEffect.EVIDENCE
        );
        ReadingToolArgumentValidator.ListPaperFilters filters = new ReadingToolArgumentValidator.ListPaperFilters(
                "agent",
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                ""
        );
        when(adapter.getSessionState(context)).thenReturn(sessionResult);
        when(adapter.listPapers(
                filters,
                true,
                ReadingToolArgumentValidator.ListPaperSort.TITLE,
                context
        )).thenReturn(paperListResult);
        when(adapter.searchPaperCandidates("agentic eval", context)).thenReturn(searchResult);
        when(adapter.findPapersByIdentity(new ReadingToolArgumentValidator.IdentityHints(
                "LoRA",
                "",
                "",
                "",
                "10.48550/arxiv.2106.09685",
                "2106.09685",
                "",
                2021
        ), context)).thenReturn(identityResult);
        when(adapter.findReadingLocations(
                List.of("paper_handle_abc"),
                "methods",
                List.of(),
                context
        )).thenReturn(locationResult);
        when(adapter.listPaperLocations(
                List.of("paper_handle_abc"),
                new ReadingToolArgumentValidator.PageRange(3, 3),
                List.of(PaperLocationType.PAGE),
                context
        )).thenReturn(listedLocationsResult);
        when(adapter.getPaperOutline(List.of("paper_handle_abc"), context)).thenReturn(outlineResult);
        when(adapter.readLocations(List.of("page_ref_abc"), context)).thenReturn(readResult);
        when(adapter.traceSourceQuotes(List.of("source_quote_abc"), context)).thenReturn(traceResult);

        assertEquals(sessionResult, registry.execute("get_session_state", Map.of(), context));
        assertEquals(paperListResult, registry.execute("list_papers", Map.of(
                "filters", Map.of("titleContains", "agent"),
                "includeFacets", true,
                "sort", "TITLE"
        ), context));
        assertEquals(searchResult, registry.execute("search_paper_candidates", Map.of("queryText", "agentic eval"), context));
        assertEquals(identityResult, registry.execute("find_papers_by_identity", Map.of(
                "identityHints", Map.of(
                        "titleContains", " LoRA ",
                        "doiExact", "https://doi.org/10.48550/arXiv.2106.09685",
                        "arxivIdExact", "arXiv:2106.09685v1",
                        "year", 2021
                )
        ), context));
        assertEquals(outlineResult, registry.execute("get_paper_outline", Map.of(
                "paperHandles", List.of("paper_handle_abc")
        ), context));
        assertEquals(listedLocationsResult, registry.execute("list_paper_locations", Map.of(
                "paperHandles", List.of("paper_handle_abc"),
                "pageRange", Map.of("from", 3, "to", 3),
                "locationTypes", List.of("PAGE")
        ), context));
        assertEquals(locationResult, registry.execute("find_reading_locations", Map.of(
                "paperHandles", List.of("paper_handle_abc"),
                "queryPlan", Map.of(
                        "queryText", "methods",
                        "intent", "METHOD",
                        "sourceLanguage", "zh",
                        "retrievalLanguage", "en",
                        "sectionRoles", List.of("METHOD")
                )
        ), context));
        assertEquals(readResult, registry.execute("read_locations", Map.of(
                "locationRefs", List.of("page_ref_abc")
        ), context));
        assertEquals(traceResult, registry.execute("trace_source_quotes", Map.of(
                "sourceQuoteRefs", List.of("source_quote_abc")
        ), context));
        verify(adapter).getSessionState(context);
        verify(adapter).listPapers(
                filters,
                true,
                ReadingToolArgumentValidator.ListPaperSort.TITLE,
                context
        );
        verify(adapter).searchPaperCandidates("agentic eval", context);
        verify(adapter).findPapersByIdentity(new ReadingToolArgumentValidator.IdentityHints(
                "LoRA",
                "",
                "",
                "",
                "10.48550/arxiv.2106.09685",
                "2106.09685",
                "",
                2021
        ), context);
        verify(adapter).getPaperOutline(List.of("paper_handle_abc"), context);
        verify(adapter).listPaperLocations(
                List.of("paper_handle_abc"),
                new ReadingToolArgumentValidator.PageRange(3, 3),
                List.of(PaperLocationType.PAGE),
                context
        );
        verify(adapter).findReadingLocations(List.of("paper_handle_abc"), "methods", List.of(), context);
        verify(adapter).readLocations(List.of("page_ref_abc"), context);
        verify(adapter).traceSourceQuotes(List.of("source_quote_abc"), context);
    }
}
