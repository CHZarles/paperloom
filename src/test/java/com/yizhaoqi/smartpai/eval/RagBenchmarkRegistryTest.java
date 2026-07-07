package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBenchmarkRegistryTest {

    @Test
    void loadsHarnessesAndProfessionalBenchmarksFromYaml() throws Exception {
        RagBenchmarkRegistry registry = RagBenchmarkRegistry.load(Path.of("eval/rag/harnesses.yaml"));

        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "current-evidence-ledger".equals(harness.id()) && "runnable".equals(harness.status())));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "keyword-only-baseline".equals(harness.id())
                        && "runnable-litsearch".equals(harness.status())
                        && "keyword".equals(harness.retrieval())));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "service-backed-page-window".equals(harness.id())
                        && "runnable-paper-qa".equals(harness.status())
                        && "hybrid-plus-page-window".equals(harness.retrieval())
                        && harness.benchmarkIds().equals(List.of("product-rescue-paper-qa", "qasper-dev-200"))));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "service-backed-scoped-page-window".equals(harness.id())
                        && "runnable-paper-qa".equals(harness.status())
                        && "scoped-paper-page-window".equals(harness.retrieval())
                        && harness.benchmarkIds().equals(List.of("product-rescue-paper-qa", "qasper-dev-200"))));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "service-backed-scoped-diverse-window".equals(harness.id())
                        && "runnable-paper-qa".equals(harness.status())
                        && "scoped-paper-diverse-window".equals(harness.retrieval())
                        && "scientific-qa-diverse-windows".equals(harness.planner())
                        && harness.benchmarkIds().equals(List.of("product-rescue-paper-qa", "qasper-dev-200"))));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "service-backed-scoped-diverse-window-k5".equals(harness.id())
                        && "runnable-paper-qa".equals(harness.status())
                        && "scoped-paper-diverse-window-k5".equals(harness.retrieval())
                        && "scientific-qa-diverse-windows".equals(harness.planner())
                        && harness.benchmarkIds().equals(List.of("product-rescue-paper-qa", "qasper-dev-200"))));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "service-backed-scoped-diverse-window-k7".equals(harness.id())
                        && "runnable-paper-qa".equals(harness.status())
                        && "scoped-paper-diverse-window-k7".equals(harness.retrieval())
                        && "scientific-qa-diverse-windows".equals(harness.planner())
                        && harness.benchmarkIds().equals(List.of("product-rescue-paper-qa", "qasper-dev-200"))));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "product-pdf-parser-smoke".equals(harness.id())
                        && "runnable-parser-smoke".equals(harness.status())
                        && "parser-output-check".equals(harness.retrieval())
                        && harness.benchmarkIds().equals(List.of("product-pdf-parser-smoke"))));
        assertTrue(registry.benchmarks().stream()
                .anyMatch(benchmark -> "qasper-dev-200".equals(benchmark.id())
                        && "professional".equals(benchmark.tier())
                        && "passRate".equals(benchmark.primaryMetric())));
        assertTrue(registry.benchmarks().stream()
                .anyMatch(benchmark -> "litsearch-full".equals(benchmark.id())
                        && "professional".equals(benchmark.tier())
                        && "runnable".equals(benchmark.status())
                        && "eval/rag/litsearch/generated/litsearch-full-query.jsonl".equals(benchmark.path())
                        && "recallAt20".equals(benchmark.primaryMetric())
                        && benchmark.source().contains("princeton-nlp/LitSearch")));
        assertEquals("597 queries / 64,183 papers", registry.benchmark("litsearch-full").cases());
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "current-evidence-ledger".equals(harness.id())
                        && harness.benchmarkIds().contains("litsearch-service-slice-k5")));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "current-evidence-ledger".equals(harness.id())
                        && harness.benchmarkIds().contains("product-figure-table-smoke")));
        assertEquals("597 queries / 5,060 imported candidate papers",
                registry.benchmark("litsearch-service-slice-k5").cases());
    }

    @Test
    void registersFigureTableSmokeAsTableOnlyUntilFigureFixtureExists() throws Exception {
        RagBenchmarkRegistry registry = RagBenchmarkRegistry.load(Path.of("eval/rag/harnesses.yaml"));

        RagBenchmarkRegistry.BenchmarkDefinition benchmark = registry.benchmark("product-figure-table-smoke");

        assertEquals("Product Figure/Table Smoke", benchmark.name());
        assertEquals("product", benchmark.tier());
        assertEquals("table and figure evidence claim gate", benchmark.task());
        assertEquals("runnable-table-only", benchmark.status());
        assertEquals("eval/rag/figure-table/product-figure-table-smoke.jsonl", benchmark.path());
        assertEquals("passRate", benchmark.primaryMetric());
        assertEquals("2 table cases / figure fixture pending", benchmark.cases());
    }

    @Test
    void figureTableSmokeCurrentlyContainsOnlyScopedTableCases() throws Exception {
        List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(Path.of("eval/rag/figure-table/product-figure-table-smoke.jsonl"));

        assertEquals(2, cases.size());
        assertTrue(cases.stream().allMatch(testCase -> "TABLE_QA".equals(testCase.taskType())));
        assertTrue(cases.stream().allMatch(testCase -> "MANUAL_SOURCE".equals(testCase.scopeMode())));
        assertTrue(cases.stream().allMatch(RagBenchmarkCase::requiresCitation));
        assertTrue(cases.stream().allMatch(testCase -> !testCase.requiredEvidenceRegex().isEmpty()));
    }

    @Test
    void registersRealPdfParserSmokeAsSeparateProductGate() throws Exception {
        RagBenchmarkRegistry registry = RagBenchmarkRegistry.load(Path.of("eval/rag/harnesses.yaml"));

        RagBenchmarkRegistry.BenchmarkDefinition benchmark = registry.benchmark("product-pdf-parser-smoke");

        assertEquals("Product PDF Parser Smoke", benchmark.name());
        assertEquals("product", benchmark.tier());
        assertEquals("real PDF parser and evidence-asset smoke", benchmark.task());
        assertEquals("eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl", benchmark.path());
        assertEquals("passRate", benchmark.primaryMetric());
        assertEquals("1", benchmark.cases());
    }

    @Test
    void registersLaunchTraceEvalAndThirtyPdfParserGate() throws Exception {
        RagBenchmarkRegistry registry = RagBenchmarkRegistry.load(Path.of("eval/rag/harnesses.yaml"));

        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "product-reading-launch-trace-eval".equals(harness.id())
                        && "runnable-trace-eval".equals(harness.status())
                        && "trace-artifact-check".equals(harness.retrieval())
                        && harness.benchmarkIds().equals(List.of("product-reading-launch-trace"))));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "product-reading-live-launch-smoke".equals(harness.id())
                        && "runnable-live-smoke".equals(harness.status())
                        && "websocket-product-reading-chat".equals(harness.retrieval())
                        && harness.benchmarkIds().equals(List.of("product-reading-live-launch-smoke"))));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "product-pdf-launch-data-seed".equals(harness.id())
                        && "runnable-live-seed".equals(harness.status())
                        && "product-upload-http".equals(harness.retrieval())
                        && harness.benchmarkIds().equals(List.of("product-pdf-launch-data-seed"))));
        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "product-launch-runtime-preflight".equals(harness.id())
                        && "runnable-runtime-preflight".equals(harness.status())
                        && "runtime-preflight".equals(harness.retrieval())
                        && harness.benchmarkIds().equals(List.of("product-launch-runtime-preflight"))));

        RagBenchmarkRegistry.BenchmarkDefinition traceBenchmark = registry.benchmark("product-reading-launch-trace");
        assertEquals("Product Reading Launch Trace", traceBenchmark.name());
        assertEquals("product", traceBenchmark.tier());
        assertEquals("Product Reading 9-tool trace coverage", traceBenchmark.task());
        assertEquals("eval/rag/product-reading-launch-trace-cases.jsonl", traceBenchmark.path());
        assertEquals("passRate", traceBenchmark.primaryMetric());
        assertEquals("9", traceBenchmark.cases());

        RagBenchmarkRegistry.BenchmarkDefinition liveSmokeBenchmark = registry.benchmark("product-reading-live-launch-smoke");
        assertEquals("Product Reading Live Launch Smoke", liveSmokeBenchmark.name());
        assertEquals("product", liveSmokeBenchmark.tier());
        assertEquals("Live WebSocket Product Reading launch smoke", liveSmokeBenchmark.task());
        assertEquals("eval/rag/product-reading-live-launch-smoke-cases.jsonl", liveSmokeBenchmark.path());
        assertEquals("passRate", liveSmokeBenchmark.primaryMetric());
        assertEquals("9", liveSmokeBenchmark.cases());

        RagBenchmarkRegistry.BenchmarkDefinition pdfBenchmark = registry.benchmark("product-pdf-launch-30");
        assertEquals("Product PDF Launch 30", pdfBenchmark.name());
        assertEquals("product", pdfBenchmark.tier());
        assertEquals("30-real-PDF parser launch gate", pdfBenchmark.task());
        assertEquals("eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl", pdfBenchmark.path());
        assertEquals("passRate", pdfBenchmark.primaryMetric());
        assertEquals("30", pdfBenchmark.cases());

        RagBenchmarkRegistry.BenchmarkDefinition seedBenchmark = registry.benchmark("product-pdf-launch-data-seed");
        assertEquals("Product PDF Launch Data Seed", seedBenchmark.name());
        assertEquals("product", seedBenchmark.tier());
        assertEquals("30-PDF upload/merge/searchable live data seed", seedBenchmark.task());
        assertEquals("eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl", seedBenchmark.path());
        assertEquals("passRate", seedBenchmark.primaryMetric());
        assertEquals("30", seedBenchmark.cases());

        RagBenchmarkRegistry.BenchmarkDefinition preflightBenchmark = registry.benchmark("product-launch-runtime-preflight");
        assertEquals("Product Launch Runtime Preflight", preflightBenchmark.name());
        assertEquals("product", preflightBenchmark.tier());
        assertEquals("launch runtime dependency preflight", preflightBenchmark.task());
        assertEquals(".env", preflightBenchmark.path());
        assertEquals("passRate", preflightBenchmark.primaryMetric());
        assertEquals("10", preflightBenchmark.cases());
    }

    @Test
    void registersProductPaperQaSliceForServiceBackedPageWindowRuns() throws Exception {
        RagBenchmarkRegistry registry = RagBenchmarkRegistry.load(Path.of("eval/rag/harnesses.yaml"));

        RagBenchmarkRegistry.BenchmarkDefinition benchmark = registry.benchmark("product-rescue-paper-qa");

        assertEquals("Product Paper-QA Slice", benchmark.name());
        assertEquals("product", benchmark.tier());
        assertEquals("source-grounded scoped paper QA", benchmark.task());
        assertEquals("eval/rag/product-rescue-paper-qa.jsonl", benchmark.path());
        assertEquals("passRate", benchmark.primaryMetric());
        assertEquals("10", benchmark.cases());
    }

    @Test
    void productPaperQaSliceContainsOnlyScopedPaperQaCases() throws Exception {
        List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(Path.of("eval/rag/product-rescue-paper-qa.jsonl"));

        assertEquals(10, cases.size());
        assertTrue(cases.stream().allMatch(testCase -> "MANUAL_SOURCE".equals(testCase.scopeMode())));
        assertTrue(cases.stream().allMatch(testCase -> "MANUAL_SOURCE_QA".equals(testCase.expectedRoute())));
        assertTrue(cases.stream().allMatch(testCase -> !testCase.scope().paperIds().isEmpty()));
        assertTrue(cases.stream().allMatch(testCase -> !testCase.requiredEvidenceRegex().isEmpty()));
        assertTrue(cases.stream().anyMatch(testCase -> "TABLE_QA".equals(testCase.taskType())));
        assertTrue(cases.stream().anyMatch(testCase -> "LIMITATION_QA".equals(testCase.taskType())));
        assertTrue(cases.stream().anyMatch(testCase -> "REFERENCE_CONTEXT_QA".equals(testCase.taskType())));
        assertTrue(cases.stream().anyMatch(testCase -> "MULTI_PAPER_DISAMBIGUATION_QA".equals(testCase.taskType())));
        assertFalse(cases.stream().anyMatch(testCase -> List.of(
                "NON_PAPER_SYSTEM",
                "LIBRARY_INVENTORY",
                "REFERENCE_QA"
        ).contains(testCase.taskType())));
    }
}
