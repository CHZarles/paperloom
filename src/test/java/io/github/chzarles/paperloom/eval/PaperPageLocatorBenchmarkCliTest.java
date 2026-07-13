package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPageLocatorBenchmarkCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void runsPageLocationBenchmarkAndWritesScorecard() throws Exception {
        Path cases = tempDir.resolve("page-location-cases.jsonl");
        Files.write(cases, List.of(
                """
                        {"id":"noise_table","query":"高噪声实验 accuracy table","goldPageKeys":["paper-a:4"]}
                        """.trim(),
                """
                        {"id":"latency_limitation","query":"latency limitation","goldPageKeys":["paper-a:5"]}
                        """.trim()
        ));
        Path chunks = tempDir.resolve("page-location-chunks.jsonl");
        Files.write(chunks, List.of(
                """
                        {"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":4,"chunkId":7,"sectionTitle":"Experiments","sourceKind":"TABLE","tableId":"table-2","text":"Table 2 reports accuracy under increasing noise."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":5,"chunkId":9,"sectionTitle":"Limitations","sourceKind":"TEXT","text":"The approach has a retrieval latency limitation."}
                        """.trim(),
                """
                        {"paperId":"paper-b","paperTitle":"Survey Paper","originalFilename":"survey.pdf","pageNumber":1,"chunkId":1,"sectionTitle":"Introduction","sourceKind":"TEXT","text":"This survey introduces search systems."}
                        """.trim()
        ));
        Path retrieved = tempDir.resolve("page-location-retrieved.jsonl");
        Path runsRoot = tempDir.resolve("runs");
        Path cheatsheet = tempDir.resolve("CHEATSHEET.md");

        Path runDir = PaperPageLocatorBenchmarkCli.run(new PaperPageLocatorBenchmarkCli.Options(
                cases,
                chunks,
                retrieved,
                runsRoot,
                registryYaml(),
                cheatsheet,
                "page-index-offline",
                "page-location-mini",
                "run-page-index-mini",
                "2026-06-23T23:00:00Z",
                3
        ));

        List<String> retrievedLines = Files.readAllLines(retrieved);
        JsonNode first = OBJECT_MAPPER.readTree(retrievedLines.get(0));
        JsonNode second = OBJECT_MAPPER.readTree(retrievedLines.get(1));
        assertEquals("noise_table", first.path("caseId").asText());
        assertEquals("paper-a:4", first.path("retrievedPageKeys").get(0).asText());
        assertEquals("latency_limitation", second.path("caseId").asText());
        assertEquals("paper-a:5", second.path("retrievedPageKeys").get(0).asText());

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(2, scorecard.path("caseCount").asInt());
        assertEquals(2, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("metrics").path("pageRecallAt1").asDouble());
        assertEquals(1.0d, scorecard.path("metrics").path("pageRecallAt3").asDouble());
        assertEquals(1.0d, scorecard.path("metrics").path("pageMrr").asDouble());

        String markdown = Files.readString(cheatsheet);
        assertTrue(markdown.contains("| page-index-offline | Page Location Mini | prototype | 2 | Page@3 100.0% |"));
    }

    @Test
    void canUsePageLocatorPlannerForGenericChineseConceptQueries() throws Exception {
        Path cases = tempDir.resolve("concept-cases.jsonl");
        Files.write(cases, List.of(
                """
                        {"id":"related_concepts","query":"在论文里找相关概念","goldPageKeys":["paper-a:3"]}
                        """.trim()
        ));
        Path chunks = tempDir.resolve("concept-chunks.jsonl");
        Files.write(chunks, List.of(
                """
                        {"paperId":"paper-a","paperTitle":"Is Grep All You Need? How Agent Harnesses Reshape Agentic Search","originalFilename":"agentic.pdf","pageNumber":1,"chunkId":1,"sectionTitle":"Introduction","sourceKind":"TEXT","text":"This opening page describes the study motivation."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Is Grep All You Need? How Agent Harnesses Reshape Agentic Search","originalFilename":"agentic.pdf","pageNumber":3,"chunkId":7,"sectionTitle":"Keywords","sourceKind":"TEXT","text":"Agentic Search, Semantic Search, Lexical Search, Context Engineering, Agent Harnesses, LLM Evaluation, Grep"}
                        """.trim()
        ));
        Path retrieved = tempDir.resolve("concept-retrieved.jsonl");
        Path runsRoot = tempDir.resolve("concept-runs");
        Path cheatsheet = tempDir.resolve("concept-CHEATSHEET.md");

        PaperPageLocatorBenchmarkCli.run(new PaperPageLocatorBenchmarkCli.Options(
                cases,
                chunks,
                retrieved,
                runsRoot,
                registryYaml(),
                cheatsheet,
                "page-index-offline",
                "page-location-mini",
                "run-page-index-planned-mini",
                "2026-06-23T23:10:00Z",
                "page-locator",
                3
        ));

        JsonNode retrievedCase = OBJECT_MAPPER.readTree(Files.readAllLines(retrieved).get(0));
        assertEquals("paper-a:3", retrievedCase.path("retrievedPageKeys").get(0).asText());
        assertTrue(retrievedCase.path("scores").get(0).asDouble() > 0.0d);
        assertTrue(retrievedCase.path("locatorQuery").asText().contains("semantic search"));
    }

    @Test
    void writesWindowMetricsWhenPageWindowRadiusIsConfigured() throws Exception {
        Path cases = tempDir.resolve("window-cases.jsonl");
        Files.write(cases, List.of(
                """
                        {"id":"nearby_table","query":"overall accuracy table","goldPageKeys":["paper-a:7"]}
                        """.trim()
        ));
        Path chunks = tempDir.resolve("window-chunks.jsonl");
        Files.write(chunks, List.of(
                """
                        {"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":5,"chunkId":51,"sectionTitle":"4.1 Experiment 1","sourceKind":"TABLE","tableId":"table-1","text":"Table 1 reports overall accuracy."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":6,"chunkId":61,"sectionTitle":"4.2 Experiment 2","sourceKind":"TEXT","text":"Noise discussion."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":7,"chunkId":71,"sectionTitle":"4.2 Experiment 2","sourceKind":"TABLE","tableId":"table-2","text":"Table 2 reports session limit noise results."}
                        """.trim()
        ));
        Path retrieved = tempDir.resolve("window-retrieved.jsonl");
        Path runsRoot = tempDir.resolve("window-runs");
        Path cheatsheet = tempDir.resolve("window-CHEATSHEET.md");

        Path runDir = PaperPageLocatorBenchmarkCli.run(new PaperPageLocatorBenchmarkCli.Options(
                cases,
                chunks,
                retrieved,
                runsRoot,
                registryYaml(),
                cheatsheet,
                "page-index-offline",
                "page-location-mini",
                "run-page-index-window-mini",
                "2026-06-24T00:10:00Z",
                "none",
                1,
                2
        ));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(0.0d, scorecard.path("metrics").path("pageRecallAt1").asDouble());
        assertEquals(1.0d, scorecard.path("metrics").path("windowRecallAt1").asDouble());
        assertEquals(1.0d, scorecard.path("metrics").path("positiveWindowRecallAt1").asDouble());

        JsonNode retrievedCase = OBJECT_MAPPER.readTree(Files.readAllLines(retrieved).get(0));
        assertEquals("paper-a:5", retrievedCase.path("windows").get(0).path("centerPageKey").asText());
        assertEquals("paper-a:7", retrievedCase.path("windows").get(0).path("pageKeys").get(2).asText());
    }

    @Test
    void writesEvidenceHitMetricsWhenRagCasesAreProvided() throws Exception {
        Path pageCases = tempDir.resolve("evidence-page-cases.jsonl");
        Files.write(pageCases, List.of(
                """
                        {"id":"nearby_table","query":"overall accuracy table","goldPageKeys":["paper-a:7"]}
                        """.trim()
        ));
        Path ragCases = tempDir.resolve("evidence-rag-cases.jsonl");
        Files.write(ragCases, List.of(
                """
                        {"id":"nearby_table","query":"overall accuracy table","language":"en","taskType":"EXPERIMENT_QA","scopeMode":"MANUAL_SOURCE","scope":{"paperIds":["paper-a"],"paperTitles":["Agentic Retrieval"]},"expectedRoute":"MANUAL_SOURCE_QA","requiredAnswerRegex":[],"requiredEvidenceRegex":["session limit noise results"],"forbiddenAnswerRegex":[],"forbiddenEvidenceRegex":[],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                        """.trim(),
                """
                        {"id":"not_in_page_cases","query":"unmapped question","language":"en","taskType":"EXPERIMENT_QA","scopeMode":"MANUAL_SOURCE","scope":{"paperIds":["paper-a"],"paperTitles":["Agentic Retrieval"]},"expectedRoute":"MANUAL_SOURCE_QA","requiredAnswerRegex":[],"requiredEvidenceRegex":["unmapped evidence"],"forbiddenAnswerRegex":[],"forbiddenEvidenceRegex":[],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                        """.trim()
        ));
        Path chunks = tempDir.resolve("evidence-chunks.jsonl");
        Files.write(chunks, List.of(
                """
                        {"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":5,"chunkId":51,"sectionTitle":"4.1 Experiment 1","sourceKind":"TABLE","tableId":"table-1","text":"Table 1 reports overall accuracy."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":6,"chunkId":61,"sectionTitle":"4.2 Experiment 2","sourceKind":"TEXT","text":"Noise discussion."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":7,"chunkId":71,"sectionTitle":"4.2 Experiment 2","sourceKind":"TABLE","tableId":"table-2","text":"Table 2 reports session limit noise results."}
                        """.trim()
        ));
        Path retrieved = tempDir.resolve("evidence-retrieved.jsonl");
        Path runsRoot = tempDir.resolve("evidence-runs");
        Path cheatsheet = tempDir.resolve("evidence-CHEATSHEET.md");

        PaperPageLocatorBenchmarkCli.main(new String[]{
                "--cases", pageCases.toString(),
                "--rag-cases", ragCases.toString(),
                "--chunks", chunks.toString(),
                "--retrieved", retrieved.toString(),
                "--runs-root", runsRoot.toString(),
                "--registry", registryYaml().toString(),
                "--cheatsheet", cheatsheet.toString(),
                "--harness-id", "page-index-offline",
                "--dataset-id", "page-location-mini",
                "--run-id", "run-page-index-evidence-mini",
                "--started-at", "2026-06-24T01:10:00Z",
                "--query-planner", "none",
                "--window-radius", "2",
                "--top-k", "2"
        });

        JsonNode scorecard = OBJECT_MAPPER.readTree(
                runsRoot.resolve("run-page-index-evidence-mini").resolve("scorecard.json").toFile()
        );
        JsonNode metrics = scorecard.path("metrics");
        assertEquals(1.0d, metrics.path("chunkEvidenceCaseCount").asDouble());
        assertEquals(0.0d, metrics.path("chunkEvidenceHitAt1").asDouble());
        assertEquals(1.0d, metrics.path("chunkEvidenceHitAt2").asDouble());
        assertEquals(1.0d, metrics.path("windowEvidenceCaseCount").asDouble());
        assertEquals(1.0d, metrics.path("windowEvidenceHitAt1").asDouble());
        assertEquals(1.0d, metrics.path("windowEvidenceHitAt2").asDouble());
    }

    @Test
    void limitsPageRankingToScopedPaperWhenRagCasesAreProvided() throws Exception {
        Path pageCases = tempDir.resolve("scoped-page-cases.jsonl");
        Files.write(pageCases, List.of(
                """
                        {"id":"scoped_qasper","query":"what baseline do they compare with?","goldPageKeys":["paper-a:2"]}
                        """.trim()
        ));
        Path ragCases = tempDir.resolve("scoped-rag-cases.jsonl");
        Files.write(ragCases, List.of(
                """
                        {"id":"scoped_qasper","query":"what baseline do they compare with?","language":"en","taskType":"QASPER_EVIDENCE_QA","scopeMode":"MANUAL_SOURCE","scope":{"paperIds":["paper-a"],"paperTitles":["Paper A"]},"expectedRoute":"MANUAL_SOURCE_QA","requiredAnswerRegex":[],"requiredEvidenceRegex":["baseline alpha"],"forbiddenAnswerRegex":[],"forbiddenEvidenceRegex":[],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                        """.trim()
        ));
        Path chunks = tempDir.resolve("scoped-chunks.jsonl");
        Files.write(chunks, List.of(
                """
                        {"paperId":"paper-b","paperTitle":"Baseline Compare Study","originalFilename":"paper-b.json","pageNumber":1,"chunkId":1,"sectionTitle":"Baseline Compare","sourceKind":"TEXT","text":"This page discusses baseline compare with systems in detail."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Paper A","originalFilename":"paper-a.json","pageNumber":2,"chunkId":2,"sectionTitle":"Experiments","sourceKind":"TEXT","text":"The model is compared with baseline alpha."}
                        """.trim()
        ));
        Path retrieved = tempDir.resolve("scoped-retrieved.jsonl");
        Path runsRoot = tempDir.resolve("scoped-runs");
        Path cheatsheet = tempDir.resolve("scoped-CHEATSHEET.md");

        PaperPageLocatorBenchmarkCli.main(new String[]{
                "--cases", pageCases.toString(),
                "--rag-cases", ragCases.toString(),
                "--chunks", chunks.toString(),
                "--retrieved", retrieved.toString(),
                "--runs-root", runsRoot.toString(),
                "--registry", registryYaml().toString(),
                "--cheatsheet", cheatsheet.toString(),
                "--harness-id", "page-index-offline",
                "--dataset-id", "page-location-mini",
                "--run-id", "run-scoped-page-index-mini",
                "--started-at", "2026-06-24T01:40:00Z",
                "--query-planner", "none",
                "--window-radius", "0",
                "--top-k", "1"
        });

        JsonNode retrievedCase = OBJECT_MAPPER.readTree(Files.readAllLines(retrieved).get(0));
        assertEquals("paper-a:2", retrievedCase.path("retrievedPageKeys").get(0).asText());
    }

    @Test
    void canUseScientificQaPlannerForQasperStyleEvidenceQuestions() throws Exception {
        Path pageCases = tempDir.resolve("scientific-qa-page-cases.jsonl");
        Files.write(pageCases, List.of(
                """
                        {"id":"qasper_baseline","query":"what are the pivot-based baselines?","goldPageKeys":["paper-a:4"]}
                        """.trim()
        ));
        Path chunks = tempDir.resolve("scientific-qa-chunks.jsonl");
        Files.write(chunks, List.of(
                """
                        {"paperId":"paper-a","paperTitle":"Cross-lingual Transfer","originalFilename":"paper-a.json","pageNumber":2,"chunkId":2,"sectionTitle":"Introduction","sourceKind":"TEXT","text":"Pivot methods are introduced as motivation for zero-shot translation."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Cross-lingual Transfer","originalFilename":"paper-a.json","pageNumber":4,"chunkId":4,"sectionTitle":"Experiments","sourceKind":"TEXT","text":"We compare our approaches with related approaches of pivoting, multilingual NMT, and cross-lingual transfer without pretraining."}
                        """.trim()
        ));
        Path retrieved = tempDir.resolve("scientific-qa-retrieved.jsonl");
        Path runsRoot = tempDir.resolve("scientific-qa-runs");
        Path cheatsheet = tempDir.resolve("scientific-qa-CHEATSHEET.md");

        PaperPageLocatorBenchmarkCli.main(new String[]{
                "--cases", pageCases.toString(),
                "--chunks", chunks.toString(),
                "--retrieved", retrieved.toString(),
                "--runs-root", runsRoot.toString(),
                "--registry", registryYaml().toString(),
                "--cheatsheet", cheatsheet.toString(),
                "--harness-id", "page-index-scientific-qa",
                "--dataset-id", "page-location-mini",
                "--run-id", "run-scientific-qa-page-index-mini",
                "--started-at", "2026-06-24T02:10:00Z",
                "--query-planner", "scientific-qa",
                "--window-radius", "0",
                "--top-k", "1"
        });

        JsonNode retrievedCase = OBJECT_MAPPER.readTree(Files.readAllLines(retrieved).get(0));
        assertEquals("paper-a:4", retrievedCase.path("retrievedPageKeys").get(0).asText());
        assertTrue(retrievedCase.path("locatorQuery").asText().contains("comparison"));
        assertTrue(retrievedCase.path("queryExpansions").toString().contains("\"compare\""));
    }

    private Path registryYaml() throws Exception {
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: page-index-offline
                    name: Page Index Offline
                    description: Offline page locator prototype.
                    retrieval: page-index
                    planner: none
                    verifier: disabled
                    status: prototype
                benchmarks:
                  - id: page-location-mini
                    name: Page Location Mini
                    tier: prototype
                    task: page-window retrieval
                    status: runnable
                    path: page-location-cases.jsonl
                    source: local
                    primaryMetric: pageRecallAt3
                    cases: "2"
                """);
        return registry;
    }
}
