package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitSearchFacetPaperBaselineCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void favorsTitleAndAbstractFacetCoverageOverRepeatedSingleTermBodyMatches() throws Exception {
        Path gold = tempDir.resolve("litsearch-facet-query.jsonl");
        Files.write(gold, List.of(OBJECT_MAPPER.writeValueAsString(new LitSearchBenchmarkCase(
                "facet_hallucination_detection",
                "LITSEARCH_RETRIEVAL",
                "mini",
                "post hoc hallucination detection at token and sentence level for neural sequence generation",
                0,
                2,
                List.of("gold")
        ))));
        Path corpus = tempDir.resolve("litsearch-facet-corpus.jsonl");
        Files.write(corpus, List.of(
                OBJECT_MAPPER.writeValueAsString(new LitSearchPaperDocument(
                        "decoy",
                        "Hallucination in text generation",
                        "A short discussion of hallucination.",
                        "hallucination ".repeat(80),
                        List.of()
                )),
                OBJECT_MAPPER.writeValueAsString(new LitSearchPaperDocument(
                        "gold",
                        "Token and sentence level hallucination detection",
                        "Post hoc detection for neural sequence generation with token and sentence evidence.",
                        "We study hallucination detection for sequence generation tasks.",
                        List.of()
                ))
        ));
        Path retrieved = tempDir.resolve("facet-retrieved.jsonl");
        Path registry = registryYaml();

        Path runDir = LitSearchFacetPaperBaselineCli.run(new LitSearchFacetPaperBaselineCli.Options(
                gold,
                corpus,
                retrieved,
                tempDir.resolve("runs"),
                registry,
                tempDir.resolve("CHEATSHEET.md"),
                "facet-paper-baseline",
                "litsearch-mini",
                "run-litsearch-facet-mini",
                "2026-06-24T15:00:00Z",
                2
        ));

        JsonNode retrievedRow = OBJECT_MAPPER.readTree(Files.readString(retrieved).lines().findFirst().orElseThrow());
        assertEquals("facet_hallucination_detection", retrievedRow.path("caseId").asText());
        assertEquals("gold", retrievedRow.path("retrievedCorpusIds").get(0).asText());

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(1, scorecard.path("caseCount").asInt());
        assertEquals(1.0d, scorecard.path("metrics").path("recallAt20").asDouble());

        String markdown = Files.readString(tempDir.resolve("CHEATSHEET.md"));
        assertTrue(markdown.contains("| facet-paper-baseline | LitSearch Mini | prototype | 1 | Recall@20 100.0% |"));
    }

    @Test
    void rejectsMiniCorpusWhenDatasetIdWouldBeReportedAsLitSearchFull() throws Exception {
        Path gold = tempDir.resolve("litsearch-facet-query.jsonl");
        Files.write(gold, List.of(OBJECT_MAPPER.writeValueAsString(new LitSearchBenchmarkCase(
                "facet_hallucination_detection",
                "LITSEARCH_RETRIEVAL",
                "mini",
                "post hoc hallucination detection",
                0,
                2,
                List.of("gold")
        ))));
        Path corpus = tempDir.resolve("litsearch-facet-mini-corpus.jsonl");
        Files.write(corpus, List.of(OBJECT_MAPPER.writeValueAsString(new LitSearchPaperDocument(
                "gold",
                "Post hoc hallucination detection",
                "Mini corpus row.",
                "",
                List.of()
        ))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LitSearchFacetPaperBaselineCli.run(new LitSearchFacetPaperBaselineCli.Options(
                        gold,
                        corpus,
                        tempDir.resolve("facet-retrieved.jsonl"),
                        tempDir.resolve("runs"),
                        registryYaml(),
                        tempDir.resolve("CHEATSHEET.md"),
                        "facet-paper-baseline",
                        "litsearch-full",
                        "run-litsearch-facet-mini",
                        "2026-06-24T15:00:00Z",
                        2
                )));

        assertTrue(error.getMessage().contains("litsearch-full"));
        assertTrue(error.getMessage().contains("sample-specific dataset id"));
    }

    @Test
    void rewardsOrderedQueryPhrasesInTitleAndAbstract() {
        LitSearchBenchmarkCase testCase = new LitSearchBenchmarkCase(
                "phrase_order",
                "LITSEARCH_RETRIEVAL",
                "mini",
                "neural sequence generation",
                0,
                2,
                List.of("gold")
        );

        List<LitSearchPaperDocument> papers = List.of(
                new LitSearchPaperDocument(
                        "aaa-decoy",
                        "Generation sequence neural",
                        "The same words appear out of order.",
                        "",
                        List.of()
                ),
                new LitSearchPaperDocument(
                        "gold",
                        "Neural sequence generation",
                        "The ordered phrase appears in the title.",
                        "",
                        List.of()
                )
        );

        List<String> retrieved = LitSearchFacetPaperBaselineCli
                .retrieve(List.of(testCase), papers, 2)
                .get("phrase_order");

        assertEquals("gold", retrieved.get(0));
    }

    private Path registryYaml() throws Exception {
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: facet-paper-baseline
                    name: Facet Paper Baseline
                    description: Offline title/abstract/full-text facet coverage baseline.
                    retrieval: paper-facet
                    planner: none
                    verifier: disabled
                    status: runnable-litsearch
                benchmarks:
                  - id: litsearch-mini
                    name: LitSearch Mini
                    tier: prototype
                    task: literature search retrieval
                    status: sample
                    path: src/test/resources/eval/litsearch-mini-query.json
                    source: https://huggingface.co/datasets/princeton-nlp/LitSearch
                    primaryMetric: recallAt20
                    cases: "2"
                """);
        return registry;
    }
}
