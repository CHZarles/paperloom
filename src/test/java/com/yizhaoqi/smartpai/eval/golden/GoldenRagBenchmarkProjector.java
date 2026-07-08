package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.eval.RagBenchmarkCase;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GoldenRagBenchmarkProjector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<RagBenchmarkCase> project(List<GoldenDatasetSchema.GoldenCase> cases) {
        return (cases == null ? List.<GoldenDatasetSchema.GoldenCase>of() : cases)
                .stream()
                .map(this::project)
                .toList();
    }

    public RagBenchmarkCase project(GoldenDatasetSchema.GoldenCase testCase) {
        GoldenDatasetSchema.CompatibilityProjection projection = testCase.compatibility_projection();
        List<String> expectedPaperIds = projection == null
                ? testCase.corpus_scope().required_paper_ids()
                : projection.expectedPaperIds();
        String scopeMode = expectedPaperIds.isEmpty() ? "AUTO_SOURCE" : "MANUAL_SOURCE";
        return new RagBenchmarkCase(
                testCase.id(),
                testCase.question() == null ? "" : testCase.question().text(),
                testCase.question() == null ? "en" : blankToDefault(testCase.question().language(), "en"),
                projection == null ? answerTypeAsTaskType(testCase) : blankToDefault(projection.taskType(), answerTypeAsTaskType(testCase)),
                scopeMode,
                new RagBenchmarkCase.Scope(expectedPaperIds, List.of()),
                projection == null ? "" : blankToDefault(projection.expectedRoute(), ""),
                projection == null ? List.of() : projection.requiredAnswerRegex(),
                projection == null ? List.of() : projection.requiredEvidenceRegex(),
                projection == null ? List.of() : projection.forbiddenAnswerRegex(),
                projection == null ? List.of() : projection.forbiddenEvidenceRegex(),
                expectedPaperIds,
                projection != null && Boolean.TRUE.equals(projection.requiresCitation())
        );
    }

    public void writeJsonl(List<GoldenDatasetSchema.GoldenCase> cases, Path output) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            for (RagBenchmarkCase row : project(cases)) {
                writer.write(OBJECT_MAPPER.writeValueAsString(row));
                writer.newLine();
            }
        }
    }

    private String answerTypeAsTaskType(GoldenDatasetSchema.GoldenCase testCase) {
        if (testCase.expected_result() == null || testCase.expected_result().answer_type() == null) {
            return "GOLDEN_CASE";
        }
        return testCase.expected_result().answer_type().toUpperCase().replace('-', '_');
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
