package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReadingLaunchTraceCasesTest {

    @Test
    void launchTraceCasesCoverAllNineReadingToolsAndThreePaperChoiceSources() throws Exception {
        List<ProductReadingLaunchTraceEvalRunner.ProductReadingLaunchTraceCase> cases =
                ProductReadingLaunchTraceEvalRunner.loadCases(Path.of("eval/rag/product-reading-launch-trace-cases.jsonl"));

        Set<String> tools = new LinkedHashSet<>();
        Set<String> cardSources = new LinkedHashSet<>();
        boolean hasReadEvidenceCase = false;
        boolean hasTraceEvidenceCase = false;
        boolean everyCaseRequiresResearchTrace = true;
        boolean everyCaseRequiresVerifiedResearchTrace = true;
        for (ProductReadingLaunchTraceEvalRunner.ProductReadingLaunchTraceCase testCase : cases) {
            tools.addAll(testCase.requiredToolNames());
            cardSources.addAll(testCase.requiredProductStateSourceTools());
            if (testCase.requiredToolNames().contains("read_locations") && Boolean.TRUE.equals(testCase.requiresReference())) {
                hasReadEvidenceCase = true;
            }
            if (testCase.requiredToolNames().contains("trace_source_quotes") && Boolean.TRUE.equals(testCase.requiresReference())) {
                hasTraceEvidenceCase = true;
            }
            everyCaseRequiresResearchTrace &= testCase.researchTraceRequired();
            everyCaseRequiresVerifiedResearchTrace &= testCase.verifiedResearchTraceRequired();
        }

        assertEquals(Set.of(
                "get_session_state",
                "list_papers",
                "search_paper_candidates",
                "find_papers_by_identity",
                "get_paper_outline",
                "list_paper_locations",
                "find_reading_locations",
                "read_locations",
                "trace_source_quotes"
        ), tools);
        assertEquals(Set.of(
                "list_papers",
                "search_paper_candidates",
                "find_papers_by_identity"
        ), cardSources);
        assertTrue(hasReadEvidenceCase);
        assertTrue(hasTraceEvidenceCase);
        assertTrue(everyCaseRequiresResearchTrace);
        assertTrue(everyCaseRequiresVerifiedResearchTrace);
    }
}
