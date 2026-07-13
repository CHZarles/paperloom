package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReadingToolOutputMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReadingToolOutputMapper mapper = new ReadingToolOutputMapper();

    @Test
    void mapsPaperCandidateToCanonicalPaperCardWithHiddenIdentity() throws Exception {
        PaperCandidate candidate = new PaperCandidate(
                "raw-paper-id",
                "Agentic Eval Benchmark",
                "Ada Lovelace",
                2025,
                "NeurIPS",
                "Agentic eval preview",
                List.of("title", "abstract"),
                "title matched all query tokens",
                10
        );

        Map<String, Object> card = mapper.paperCard(candidate, "paper_handle_abc", 1);
        String json = objectMapper.writeValueAsString(card);

        assertEquals(1, card.get("ordinal"));
        assertEquals("raw-paper-id", card.get("paperId"));
        assertEquals("paper_handle_abc", card.get("paperHandle"));
        assertEquals("Agentic Eval Benchmark", card.get("title"));
        assertEquals("", card.get("originalFilename"));
        assertEquals(2025, card.get("year"));
        assertEquals("Agentic eval preview", card.get("preview"));
        assertEquals(List.of("title matched all query tokens"), card.get("matchReasons"));
        assertFalse(json.contains("matchedFields"));
        assertFalse(json.contains("rank"));
    }

    @Test
    void mapsReadingLocationCandidateWithoutInternalFields() throws Exception {
        ReadingLocationCandidate candidate = new ReadingLocationCandidate(
                "raw-paper-id",
                "model-v1",
                "section_ref_abc",
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
        );

        Map<String, Object> card = mapper.locationCard(candidate, "paper_handle_abc", 2);
        String json = objectMapper.writeValueAsString(card);

        assertEquals(2, card.get("ordinal"));
        assertEquals("paper_handle_abc", card.get("paperHandle"));
        assertEquals("section_ref_abc", card.get("locationRef"));
        assertEquals("SECTION", card.get("locationType"));
        assertEquals(3, card.get("pageNumber"));
        assertEquals(4, card.get("pageEndNumber"));
        assertEquals("Experiments", card.get("sectionTitle"));
        assertEquals("Agentic eval appears here.", card.get("preview"));
        assertEquals("raw-paper-id", card.get("paperId"));
        assertFalse(json.contains("model-v1"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("reading-el-1"));
        assertFalse(json.contains("readingElementId"));
        assertFalse(json.contains("matchedFields"));
        assertFalse(json.contains("routingSource"));
        assertFalse(json.contains("matchSource"));
    }
}
