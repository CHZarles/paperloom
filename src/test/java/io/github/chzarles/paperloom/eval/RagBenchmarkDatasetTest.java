package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBenchmarkDatasetTest {

    @Test
    void loadsProductRescueSmokeDataset() throws Exception {
        List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(Path.of("eval/rag/product-rescue-smoke.jsonl"));

        assertFalse(cases.isEmpty());
        assertTrue(cases.stream().anyMatch(testCase -> "grep_high_noise_zh".equals(testCase.id())));
        assertTrue(cases.stream().anyMatch(testCase -> "non_paper_session_id".equals(testCase.id())));
        assertTrue(cases.stream().allMatch(testCase -> testCase.id() != null && !testCase.id().isBlank()));
        assertTrue(cases.stream().allMatch(testCase -> testCase.query() != null && !testCase.query().isBlank()));
    }
}
