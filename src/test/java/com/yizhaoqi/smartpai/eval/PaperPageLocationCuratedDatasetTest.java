package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPageLocationCuratedDatasetTest {

    @Test
    void loadsCuratedExactPageCasesForProductSmokePaper() throws Exception {
        List<PaperPageLocatorCase> cases = PaperPageLocatorCaseDataset.load(
                Path.of("eval/rag/page-location/product-rescue-curated-page-cases.jsonl")
        );

        assertEquals(6, cases.size());
        Map<String, PaperPageLocatorCase> byId = cases.stream()
                .collect(Collectors.toMap(PaperPageLocatorCase::id, Function.identity()));
        assertEquals(List.of("6da506ce952a2c4d85928b3e0052f4f6:1"),
                byId.get("grep_curated_related_concepts").goldPageKeys());
        assertEquals(List.of("6da506ce952a2c4d85928b3e0052f4f6:2"),
                byId.get("grep_curated_retrieval_strategies").goldPageKeys());
        assertEquals(List.of("6da506ce952a2c4d85928b3e0052f4f6:3"),
                byId.get("grep_curated_method_dataset").goldPageKeys());
        assertEquals(List.of("6da506ce952a2c4d85928b3e0052f4f6:5"),
                byId.get("grep_curated_experiment1_table").goldPageKeys());
        assertEquals(List.of("6da506ce952a2c4d85928b3e0052f4f6:7"),
                byId.get("grep_curated_noise_tables").goldPageKeys());
        assertEquals(List.of("6da506ce952a2c4d85928b3e0052f4f6:7"),
                byId.get("grep_curated_limitations_conclusion").goldPageKeys());
    }

    @Test
    void pageLocationRegistryIncludesRawAndPlannedPageIndexHarnesses() throws Exception {
        RagBenchmarkRegistry registry = RagBenchmarkRegistry.load(Path.of("eval/rag/page-location/harnesses.yaml"));

        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "page-index-offline".equals(harness.id())
                        && "none".equals(harness.planner())));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "page-index-planned".equals(harness.id())
                        && "page-locator".equals(harness.planner())));

        RagBenchmarkRegistry.BenchmarkDefinition benchmark = registry.benchmark("product-rescue-curated-page-location");
        assertEquals("Product Rescue Curated Page Location", benchmark.name());
        assertEquals("prototype", benchmark.tier());
        assertEquals("positivePageRecallAt3", benchmark.primaryMetric());
        assertEquals("6", benchmark.cases());
    }
}
