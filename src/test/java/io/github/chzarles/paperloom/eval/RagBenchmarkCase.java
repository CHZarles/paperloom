package io.github.chzarles.paperloom.eval;

import java.util.List;

public record RagBenchmarkCase(
        String id,
        String query,
        String language,
        String taskType,
        String scopeMode,
        Scope scope,
        String expectedRoute,
        List<String> requiredAnswerRegex,
        List<String> requiredEvidenceRegex,
        List<String> forbiddenAnswerRegex,
        List<String> forbiddenEvidenceRegex,
        List<String> expectedPaperIds,
        boolean requiresCitation
) {
    public RagBenchmarkCase {
        language = blankToDefault(language, "zh");
        taskType = blankToDefault(taskType, "AUTO_SOURCE_QA");
        scopeMode = blankToDefault(scopeMode, "AUTO_SOURCE");
        scope = scope == null ? new Scope(List.of(), List.of()) : scope;
        requiredAnswerRegex = safeList(requiredAnswerRegex);
        requiredEvidenceRegex = safeList(requiredEvidenceRegex);
        forbiddenAnswerRegex = safeList(forbiddenAnswerRegex);
        forbiddenEvidenceRegex = safeList(forbiddenEvidenceRegex);
        expectedPaperIds = safeList(expectedPaperIds);
    }

    public static RagBenchmarkCase productRescueCase(String id,
                                                     String query,
                                                     String expectedRoute,
                                                     List<String> expectedPaperIds,
                                                     List<String> requiredEvidenceRegex,
                                                     List<String> forbiddenAnswerRegex) {
        return new RagBenchmarkCase(
                id,
                query,
                "zh",
                "PRODUCT_RESCUE",
                expectedPaperIds == null || expectedPaperIds.isEmpty() ? "AUTO_SOURCE" : "MANUAL_SOURCE",
                new Scope(expectedPaperIds, List.of()),
                expectedRoute,
                List.of(),
                requiredEvidenceRegex,
                forbiddenAnswerRegex,
                List.of("^\\d+$", "^Agentic Search, Semantic Search"),
                expectedPaperIds,
                true
        );
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Scope(
            List<String> paperIds,
            List<String> paperTitles
    ) {
        public Scope {
            paperIds = paperIds == null ? List.of() : paperIds;
            paperTitles = paperTitles == null ? List.of() : paperTitles;
        }
    }
}
