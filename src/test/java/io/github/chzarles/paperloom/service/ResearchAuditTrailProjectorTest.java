package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchAuditTrailProjectorTest {

    private final ResearchAuditTrailProjector projector = new ResearchAuditTrailProjector();

    @Test
    void projectsCitedAndUncitedReadEvidenceWithVisualFields() {
        List<Map<String, Object>> events = List.of(
                Map.of(
                        "type", "research_progress",
                        "eventType", "tool_completed",
                        "sequence", 1,
                        "tool", "search_paper_candidates",
                        "status", "completed",
                        "input", Map.of("query", "agent benchmark"),
                        "output", Map.of(
                                "resultCount", 1,
                                "papers", List.of(Map.of(
                                        "paperId", "paper-1",
                                        "title", "Agent Benchmark"
                                ))
                        )
                ),
                Map.of(
                        "type", "research_progress",
                        "eventType", "tool_completed",
                        "sequence", 2,
                        "tool", "read_locations",
                        "status", "completed",
                        "input", Map.of("locationRefs", List.of("section_ref_1")),
                        "output", Map.of(
                                "evidenceCount", 2,
                                "evidence", List.of(
                                        Map.of(
                                                "evidenceId", "ev_cited",
                                                "paperId", "paper-1",
                                                "title", "Agent Benchmark",
                                                "locationRef", "section_ref_1",
                                                "section", "Results",
                                                "page", 5,
                                                "quote", "AgentBench reports progress metrics.",
                                                "bboxJson", "{\"coordinateSystem\":\"top_left_1000\"}",
                                                "pageScreenshotAvailable", true
                                        ),
                                        Map.of(
                                                "evidenceId", "ev_uncited",
                                                "paperId", "paper-1",
                                                "title", "Agent Benchmark",
                                                "locationRef", "section_ref_2",
                                                "section", "Appendix",
                                                "page", 9,
                                                "quote", "Ablations are listed in the appendix."
                                        )
                                )
                        )
                )
        );
        Map<String, Map<String, Object>> references = Map.of(
                "1",
                Map.of(
                        "paperId", "paper-1",
                        "paperTitle", "Agent Benchmark",
                        "pageNumber", 5,
                        "sectionTitle", "Results",
                        "evidenceRef", "ev_cited",
                        "citationRef", "[1]",
                        "matchedChunkText", "AgentBench reports progress metrics.",
                        "bboxJson", "{\"coordinateSystem\":\"top_left_1000\"}",
                        "pageScreenshotAvailable", true
                )
        );

        ResearchAuditTrail trail = projector.project("COMPLETED", references, events);

        assertNotNull(trail);
        assertEquals("research-audit-trail/v1", trail.schemaVersion());
        assertEquals(List.of("[1]"), trail.answer().citationRefs());
        assertEquals(2, trail.diagnostics().readEvidenceCount());
        assertEquals(1, trail.diagnostics().citedEvidenceCount());
        assertEquals(1, trail.diagnostics().uncitedReadEvidenceCount());
        assertEquals(1, trail.diagnostics().visualEvidenceAvailableCount());
        assertTrue(trail.evidence().stream().anyMatch(row ->
                "cited".equals(row.status())
                        && "ev_cited".equals(row.evidenceRef())
                        && Boolean.TRUE.equals(row.pageScreenshotAvailable())
                        && row.bboxJson().contains("top_left_1000")
        ));
        assertTrue(trail.evidence().stream().anyMatch(row ->
                "read".equals(row.status())
                        && "ev_uncited".equals(row.evidenceRef())
                        && "Ablations are listed in the appendix.".equals(row.content())
        ));
    }

    @Test
    void returnsNullWhenThereIsNoAuditableResearchData() {
        assertEquals(null, projector.project("COMPLETED", Map.of(), List.of()));
    }
}
