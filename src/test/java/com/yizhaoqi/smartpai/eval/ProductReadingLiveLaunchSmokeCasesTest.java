package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReadingLiveLaunchSmokeCasesTest {

    @Test
    void launchSmokeCasesCoverAllNineReadingToolsAndRequiredAnchors() throws Exception {
        List<ProductReadingLiveLaunchSmokeRunner.ProductReadingLiveLaunchSmokeCase> cases =
                ProductReadingLiveLaunchSmokeRunner.loadCases(
                        Path.of("eval/rag/product-reading-live-launch-smoke-cases.jsonl"));

        Set<String> toolNames = cases.stream()
                .flatMap(testCase -> testCase.requiredToolNames().stream())
                .collect(Collectors.toSet());

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
        ), toolNames);
        assertTrue(cases.stream().anyMatch(testCase -> "browse_papers".equals(testCase.id())
                && testCase.productStateItemRequired()
                && testCase.requiredProductStateSourceTools().contains("list_papers")));
        assertTrue(cases.stream().anyMatch(testCase -> "semantic_search_papers".equals(testCase.id())
                && testCase.productStateItemRequired()
                && testCase.requiredProductStateSourceTools().contains("search_paper_candidates")
                && "SEARCH_PAPERS".equals(testCase.readingActionValue())));
        assertTrue(cases.stream().anyMatch(testCase -> "identity_paper_lookup".equals(testCase.id())
                && testCase.productStateItemRequired()
                && testCase.requiredProductStateSourceTools().contains("find_papers_by_identity")));
        assertTrue(cases.stream().anyMatch(testCase -> "read_selected_locations".equals(testCase.id())
                && testCase.referenceRequired()
                && "identity_paper_lookup".equals(testCase.focusPaperHandleFromCaseValue())));
        assertTrue(cases.stream().anyMatch(testCase -> "semantic_location_search".equals(testCase.id())
                && "FIND_LOCATIONS".equals(testCase.readingActionValue())
                && testCase.requiredToolNames().contains("find_reading_locations")));
        assertTrue(cases.stream().anyMatch(testCase -> "trace_clicked_source_quote".equals(testCase.id())
                && testCase.referenceRequired()
                && "read_selected_locations".equals(testCase.focusSourceQuoteRefFromCaseValue())));
    }
}
