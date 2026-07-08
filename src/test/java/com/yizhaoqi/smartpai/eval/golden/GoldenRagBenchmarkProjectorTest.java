package com.yizhaoqi.smartpai.eval.golden;

import com.yizhaoqi.smartpai.eval.RagBenchmarkCase;
import com.yizhaoqi.smartpai.eval.RagBenchmarkDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenRagBenchmarkProjectorTest {

    @TempDir
    Path tempDir;

    @Test
    void projectsCommittedGoldenCasesToRagBenchmarkCases() throws Exception {
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader()
                .load(Path.of("research/golden-data/manifest.yaml"));

        List<RagBenchmarkCase> projected = new GoldenRagBenchmarkProjector().project(dataset.cases());

        assertEquals(7, projected.size());
        RagBenchmarkCase adam = projected.stream()
                .filter(testCase -> "transformer_adam_params_001".equals(testCase.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("Transformer original paper used Adam with what beta1, beta2, and epsilon?", adam.query());
        assertEquals("METHODOLOGY_REPRODUCTION", adam.taskType());
        assertEquals("MANUAL_SOURCE", adam.scopeMode());
        assertEquals(List.of("attention_is_all_you_need_2017"), adam.expectedPaperIds());
        assertTrue(adam.requiresCitation());
    }

    @Test
    void writesJsonlThatExistingDatasetLoaderCanRead() throws Exception {
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader()
                .load(Path.of("research/golden-data/manifest.yaml"));
        Path output = tempDir.resolve("golden.jsonl");

        new GoldenRagBenchmarkProjector().writeJsonl(dataset.cases(), output);

        List<RagBenchmarkCase> loaded = RagBenchmarkDataset.load(output);
        assertEquals(7, loaded.size());
    }
}
