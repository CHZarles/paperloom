package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitSearchRetrievalScoreCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesStandardRunArtifactsFromRetrievedCorpusIds() throws Exception {
        Path gold = tempDir.resolve("litsearch-gold.jsonl");
        Files.writeString(gold, """
                {"id":"litsearch_case_0001","taskType":"LITSEARCH_RETRIEVAL","querySet":"mini","query":"find distillation papers","specificity":1,"quality":2,"goldCorpusIds":["p1","p2"]}
                {"id":"litsearch_case_0002","taskType":"LITSEARCH_RETRIEVAL","querySet":"mini","query":"find dialect papers","specificity":0,"quality":1,"goldCorpusIds":["p3"]}
                """);
        Path retrieved = tempDir.resolve("retrieved.jsonl");
        Files.writeString(retrieved, """
                {"caseId":"litsearch_case_0001","retrievedCorpusIds":["x","p1","y"]}
                {"caseId":"litsearch_case_0002","retrievedCorpusIds":["z"]}
                """);
        Path registry = registryYaml();
        Path runsRoot = tempDir.resolve("runs");
        Path cheatsheet = tempDir.resolve("CHEATSHEET.md");

        Path runDir = LitSearchRetrievalScoreCli.run(new LitSearchRetrievalScoreCli.Options(
                gold,
                retrieved,
                runsRoot,
                registry,
                cheatsheet,
                "current-evidence-ledger",
                "litsearch-mini",
                "run-litsearch-mini",
                "2026-06-23T22:00:00Z"
        ));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(2, scorecard.path("caseCount").asInt());
        assertEquals(1, scorecard.path("passed").asInt());
        assertEquals(0.25d, scorecard.path("metrics").path("recallAt20").asDouble());
        assertEquals(0.25d, scorecard.path("metrics").path("recallAt5").asDouble());
        assertEquals(0.25d, scorecard.path("metrics").path("mrr").asDouble());

        JsonNode run = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile());
        assertEquals("LITSEARCH_RETRIEVAL", run.path("cases").get(0).path("route").asText());
        assertTrue(run.path("cases").get(1).path("failures").get(0).asText().contains("GOLD_CORPUS_MISSING_AT20"));

        String markdown = Files.readString(cheatsheet);
        assertTrue(markdown.contains("| current-evidence-ledger | LitSearch Mini | prototype | 2 | Recall@20 25.0% |"));
        assertTrue(markdown.contains("`runs/run-litsearch-mini/`"));
    }

    @Test
    void rejectsMiniGoldWhenDatasetIdWouldBeReportedAsLitSearchFull() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini-gold.jsonl");
        Files.writeString(gold, """
                {"id":"litsearch_case_0001","taskType":"LITSEARCH_RETRIEVAL","querySet":"mini","query":"find distillation papers","specificity":1,"quality":2,"goldCorpusIds":["p1","p2"]}
                {"id":"litsearch_case_0002","taskType":"LITSEARCH_RETRIEVAL","querySet":"mini","query":"find dialect papers","specificity":0,"quality":1,"goldCorpusIds":["p3"]}
                """);
        Path retrieved = tempDir.resolve("retrieved.jsonl");
        Files.writeString(retrieved, """
                {"caseId":"litsearch_case_0001","retrievedCorpusIds":["p1"]}
                {"caseId":"litsearch_case_0002","retrievedCorpusIds":["p3"]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LitSearchRetrievalScoreCli.run(new LitSearchRetrievalScoreCli.Options(
                        gold,
                        retrieved,
                        tempDir.resolve("runs"),
                        registryYaml(),
                        tempDir.resolve("CHEATSHEET.md"),
                        "current-evidence-ledger",
                        "litsearch-full",
                        "run-litsearch-mini-as-full",
                        "2026-06-23T22:00:00Z"
                )));

        assertTrue(error.getMessage().contains("litsearch-full"));
        assertTrue(error.getMessage().contains("597"));
    }

    private Path registryYaml() throws Exception {
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: current-evidence-ledger
                    name: Current Evidence Ledger
                    description: Router plus adaptive retrieval.
                    retrieval: adaptive-hybrid
                    planner: deterministic-first
                    verifier: enabled
                    status: runnable
                benchmarks:
                  - id: litsearch-mini
                    name: LitSearch Mini
                    tier: prototype
                    task: literature search retrieval
                    status: runnable
                    path: src/test/resources/eval/litsearch-mini-query.json
                    source: https://huggingface.co/datasets/princeton-nlp/LitSearch
                    primaryMetric: recallAt20
                    cases: "2"
                """);
        return registry;
    }
}
