package io.github.chzarles.paperloom.eval;

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

        assertEquals(16, cases.size());
        Set<String> tools = new LinkedHashSet<>();
        Set<String> cardSources = new LinkedHashSet<>();
        boolean hasReadEvidenceCase = false;
        boolean hasTraceEvidenceCase = false;
        boolean everyCaseRequiresResearchTrace = true;
        boolean everyCaseRequiresVerifiedResearchTrace = true;
        boolean everyCaseRequiresReadableAnswer = true;
        boolean hasExactFilenameRegression = false;
        boolean hasThisPaperFollowupRegression = false;
        boolean hasChineseMethodExperimentRegression = false;
        boolean hasBeginnerRecommendationRegression = false;
        boolean evidenceCasesRequireArtifactCompleteness = true;
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
            everyCaseRequiresReadableAnswer &= testCase.noviceReadableAnswerRequired();
            if ("session_95_98_exact_filename_2412".equals(testCase.id())) {
                hasExactFilenameRegression = testCase.requiredInputContains().contains("2412.08972.pdf")
                        && testCase.requiredOriginalFilenames().contains("2412.08972.pdf");
            }
            if ("session_107_this_paper_followup".equals(testCase.id())) {
                hasThisPaperFollowupRegression = testCase.requiredInputContains().contains("这篇论文")
                        && testCase.requiredToolNames().contains("get_paper_outline");
            }
            if ("session_110_113_chinese_method_experiment_lookup".equals(testCase.id())) {
                hasChineseMethodExperimentRegression = testCase.requiredInputContains().containsAll(List.of("方法", "实验"))
                        && testCase.requiredToolNames().contains("find_reading_locations");
            }
            if ("session_120_beginner_recommendation_flow".equals(testCase.id())) {
                hasBeginnerRecommendationRegression = testCase.requiredInputContains().contains("入门")
                        && testCase.beginnerShortlistRequired();
            }
            if (testCase.requiredAnswerTypeValue() != null
                    && testCase.requiredAnswerTypeValue().equals("EVIDENCE_ANSWER")) {
                evidenceCasesRequireArtifactCompleteness &= testCase.artifactCompletenessRequired()
                        && testCase.requiredMissingEvidence().contains("visual_pdf_page_evidence");
            }
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
        assertTrue(everyCaseRequiresReadableAnswer);
        assertTrue(evidenceCasesRequireArtifactCompleteness);
        assertTrue(hasExactFilenameRegression);
        assertTrue(hasThisPaperFollowupRegression);
        assertTrue(hasChineseMethodExperimentRegression);
        assertTrue(hasBeginnerRecommendationRegression);
    }
}
