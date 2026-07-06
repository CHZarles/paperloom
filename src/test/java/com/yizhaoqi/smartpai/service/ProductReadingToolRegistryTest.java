package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        List<AgentToolRegistry.AgentTool> tools = registry.listTools();
        List<String> names = tools.stream().map(AgentToolRegistry.AgentTool::name).toList();
        String schemaJson = objectMapper.writeValueAsString(tools.stream()
                .map(AgentToolRegistry.AgentTool::parameters)
                .toList());

        assertEquals(List.of(
                "search_paper_candidates",
                "find_reading_locations",
                "read_locations",
                "trace_source_quotes"
        ), names);
        assertTrue(schemaJson.contains("\"additionalProperties\":false"));
        assertFalse(schemaJson.contains("limit"));
        assertFalse(schemaJson.contains("topK"));
        assertFalse(schemaJson.contains("pageSize"));
        assertFalse(schemaJson.contains("maxChars"));
        assertFalse(schemaJson.contains("modelVersion"));
        assertFalse(schemaJson.contains("indexName"));
        assertFalse(schemaJson.contains("chunkRef"));
        assertFalse(names.contains("find_papers"));
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

        assertFalse(unsupported.success());
        assertEquals("unsupported_reading_tool", unsupported.data().get("error"));
        assertFalse(forbidden.success());
        assertEquals("INVALID_ARGUMENT", forbidden.data().get("status"));
        assertEquals("limit", forbidden.data().get("argument"));
    }

    @Test
    void delegatesValidatedSearchLocationAndReadCalls() {
        ProductToolContext context = new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto());
        ProductToolResult searchResult = new ProductToolResult(
                "search_paper_candidates",
                true,
                Map.of("status", "NO_MATCH", "items", List.of()),
                ProductToolEffect.PAPER_DISCOVERY
        );
        ProductToolResult locationResult = new ProductToolResult(
                "find_reading_locations",
                true,
                Map.of("status", "NO_MATCH", "candidates", List.of()),
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
        when(adapter.searchPaperCandidates("agentic eval", context)).thenReturn(searchResult);
        when(adapter.findReadingLocations(
                List.of("paper_handle_abc"),
                "methods",
                List.of(),
                context
        )).thenReturn(locationResult);
        when(adapter.readLocations(List.of("page_ref_abc"), context)).thenReturn(readResult);
        when(adapter.traceSourceQuotes(List.of("source_quote_abc"), context)).thenReturn(traceResult);

        assertEquals(searchResult, registry.execute("search_paper_candidates", Map.of("queryText", "agentic eval"), context));
        assertEquals(locationResult, registry.execute("find_reading_locations", Map.of(
                "paperHandles", List.of("paper_handle_abc"),
                "queryText", "methods"
        ), context));
        assertEquals(readResult, registry.execute("read_locations", Map.of(
                "locationRefs", List.of("page_ref_abc")
        ), context));
        assertEquals(traceResult, registry.execute("trace_source_quotes", Map.of(
                "sourceQuoteRefs", List.of("source_quote_abc")
        ), context));
        verify(adapter).searchPaperCandidates("agentic eval", context);
        verify(adapter).findReadingLocations(List.of("paper_handle_abc"), "methods", List.of(), context);
        verify(adapter).readLocations(List.of("page_ref_abc"), context);
        verify(adapter).traceSourceQuotes(List.of("source_quote_abc"), context);
    }
}
