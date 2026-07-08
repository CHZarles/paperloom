package com.yizhaoqi.smartpai.eval.golden;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenTraceScorerTest {

    @Test
    void scoresPassingTraceFixture() throws Exception {
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader()
                .load(Path.of("research/golden-data/manifest.yaml"));
        GoldenDatasetSchema.GoldenCase testCase = caseById(dataset, "transformer_adam_params_001");
        GoldenDatasetSchema.RunTrace trace = new GoldenRunTraceLoader()
                .load(Path.of("research/golden-data/run-traces/transformer-adam-pass.yaml"));

        GoldenDatasetSchema.CaseScore score = new GoldenTraceScorer().score(testCase, dataset, trace);

        assertTrue(score.passed(), () -> String.join(", ", score.failures()));
    }

    @Test
    void scoresFailingTraceFixture() throws Exception {
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader()
                .load(Path.of("research/golden-data/manifest.yaml"));
        GoldenDatasetSchema.GoldenCase testCase = caseById(dataset, "transformer_adam_params_001");
        GoldenDatasetSchema.RunTrace trace = new GoldenRunTraceLoader()
                .load(Path.of("research/golden-data/run-traces/transformer-adam-fail.yaml"));

        GoldenDatasetSchema.CaseScore score = new GoldenTraceScorer().score(testCase, dataset, trace);

        assertFalse(score.passed());
        assertTrue(score.failures().stream().anyMatch(failure -> failure.contains("REQUIRED_ANCHOR_MISSING")));
        assertTrue(score.failures().stream().anyMatch(failure -> failure.contains("FORBIDDEN_ANCHOR_USED")));
        assertTrue(score.failures().stream().anyMatch(failure -> failure.contains("TRACE_OBLIGATION_FAILED")));
    }

    private GoldenDatasetSchema.GoldenCase caseById(GoldenDatasetSchema.GoldenDataset dataset, String id) {
        Map<String, GoldenDatasetSchema.GoldenCase> cases = dataset.cases().stream()
                .collect(Collectors.toMap(GoldenDatasetSchema.GoldenCase::id, Function.identity()));
        return cases.get(id);
    }
}
