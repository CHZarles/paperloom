package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingTurnFrameTest {

    @Test
    void preservesNullablePersistedPatchWithoutTreatingItAsAPlanningSignal() {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("selectedPaper", null);
        patch.put("selectedLocation", Map.of("locationRef", "page_ref_abc"));
        patch.put("selectedSourceQuote", null);
        patch.put("latestShortlist", List.of());

        ReadingTurnFrame frame = ReadingTurnFrame.from(new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "Explain this citation",
                SourceScope.auto(),
                List.of(),
                Map.of("readingStatePatch", patch),
                ProductModelContext.defaults()
        ));

        assertTrue(frame.persistedReadingStatePatch().containsKey("selectedPaper"));
        assertEquals("", frame.readingAction());
        assertDoesNotThrow(() -> new ReadingTurnState(frame));
    }

    @Test
    void acceptsOnlyStructuredClickedLocationRefsForReadLocationAction() {
        ReadingTurnFrame frame = ReadingTurnFrame.from(new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "读取这个位置",
                SourceScope.auto(),
                List.of(),
                Map.of(
                        "readingTurnAnchors", Map.of(
                                "clickedLocationRefs", List.of(" page_ref_abc ", "not_a_ref", "section_ref_methods")
                        ),
                        "readingTurnAction", "read_location"
                ),
                ProductModelContext.defaults()
        ));

        assertEquals("READ_LOCATION", frame.readingAction());
        assertEquals(java.util.Set.of("page_ref_abc", "section_ref_methods"), frame.clickedLocationRefs());
        ReadingTurnState state = new ReadingTurnState(frame);
        assertTrue(state.disclosedLocationRefs.contains("page_ref_abc"));
        assertTrue(state.disclosedLocationRefs.contains("section_ref_methods"));
        assertTrue(!state.disclosedLocationRefs.contains("not_a_ref"));
    }

    @Test
    void acceptsTraceSourceQuoteActionForStructuredCitationFocus() {
        ReadingTurnFrame frame = ReadingTurnFrame.from(new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "Explain this citation",
                SourceScope.auto(),
                List.of(),
                Map.of(
                        "readingTurnAnchors", Map.of(
                                "clickedSourceQuoteRefs", List.of(" source_quote_abc ", "not_a_ref")
                        ),
                        "readingTurnAction", "trace_source_quote"
                ),
                ProductModelContext.defaults()
        ));

        assertEquals("TRACE_SOURCE_QUOTE", frame.readingAction());
        assertEquals(java.util.Set.of("source_quote_abc"), frame.clickedSourceQuoteRefs());
        ReadingTurnState state = new ReadingTurnState(frame);
        assertTrue(state.traceableSourceQuoteRefs.contains("source_quote_abc"));
        assertTrue(!state.traceableSourceQuoteRefs.contains("not_a_ref"));
    }
}
