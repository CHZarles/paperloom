package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperEvidenceHitScorerTest {

    @Test
    void scoresRequiredEvidenceInTopKChunks() {
        RagBenchmarkCase testCase = caseWithEvidence("case-a", "needle evidence");

        Map<String, Double> metrics = PaperEvidenceHitScorer.scoreChunkEvidence(
                List.of(testCase),
                Map.of("case-a", List.of(
                        chunk(1, 10, "irrelevant text"),
                        chunk(2, 20, "this chunk contains needle evidence")
                )),
                1,
                2
        );

        assertEquals(0.0d, metrics.get("chunkEvidenceHitAt1"));
        assertEquals(1.0d, metrics.get("chunkEvidenceHitAt2"));
    }

    @Test
    void scoresRequiredEvidenceInInspectedPageWindows() {
        RagBenchmarkCase testCase = caseWithEvidence("case-a", "needle evidence");
        PaperPageDocument page1 = page(1);
        PaperPageDocument page2 = page(2);
        PaperPageWindow window1 = new PaperPageWindow(page1, List.of(page1), 2.0d, List.of("text"));
        PaperPageWindow window2 = new PaperPageWindow(page2, List.of(page2), 1.0d, List.of("text"));

        Map<String, Double> metrics = PaperEvidenceHitScorer.scoreWindowEvidence(
                List.of(testCase),
                Map.of("case-a", List.of(
                        PaperPageInspection.from(window1, List.of(chunk(1, 10, "irrelevant text"))),
                        PaperPageInspection.from(window2, List.of(chunk(2, 20, "this window contains needle evidence")))
                )),
                1,
                2
        );

        assertEquals(0.0d, metrics.get("windowEvidenceHitAt1"));
        assertEquals(1.0d, metrics.get("windowEvidenceHitAt2"));
    }

    @Test
    void ignoresCasesWithoutUsefulEvidencePatterns() {
        RagBenchmarkCase generic = caseWithEvidence("generic", ".");

        Map<String, Double> metrics = PaperEvidenceHitScorer.scoreChunkEvidence(
                List.of(generic),
                Map.of("generic", List.of(chunk(1, 10, "anything"))),
                1
        );

        assertEquals(0.0d, metrics.get("chunkEvidenceCaseCount"));
        assertEquals(0.0d, metrics.get("chunkEvidenceHitAt1"));
    }

    private RagBenchmarkCase caseWithEvidence(String id, String evidenceRegex) {
        return new RagBenchmarkCase(
                id,
                "query",
                "zh",
                "EVIDENCE_QA",
                "MANUAL_SOURCE",
                new RagBenchmarkCase.Scope(List.of("paper-a"), List.of()),
                "MANUAL_SOURCE_QA",
                List.of(),
                List.of(evidenceRegex),
                List.of(),
                List.of(),
                List.of("paper-a"),
                true
        );
    }

    private SearchResult chunk(int pageNumber, int chunkId, String text) {
        SearchResult result = new SearchResult("paper-a", chunkId, text, 1.0d);
        result.setPaperTitle("paper-a");
        result.setOriginalFilename("paper-a.pdf");
        result.setPageNumber(pageNumber);
        result.setMatchedChunkText(text);
        result.setSectionTitle("Section");
        result.setSourceKind("TEXT");
        return result;
    }

    private PaperPageDocument page(int pageNumber) {
        return new PaperPageDocument(
                "paper-a",
                "paper-a",
                "paper-a.pdf",
                pageNumber,
                "text",
                List.of(pageNumber),
                List.of("Section"),
                List.of("TEXT"),
                List.of(),
                List.of()
        );
    }
}
