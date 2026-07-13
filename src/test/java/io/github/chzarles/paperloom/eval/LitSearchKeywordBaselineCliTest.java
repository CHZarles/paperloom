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

class LitSearchKeywordBaselineCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void retrievesMiniLitSearchCorpusAndWritesScorecard() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini-query.jsonl");
        new LitSearchBenchmarkConverter().writeQueryJsonl(
                Path.of("src/test/resources/eval/litsearch-mini-query.json"),
                gold,
                0
        );
        Path retrieved = tempDir.resolve("keyword-retrieved.jsonl");
        Path registry = registryYaml();
        Path runsRoot = tempDir.resolve("runs");
        Path cheatsheet = tempDir.resolve("CHEATSHEET.md");

        Path runDir = LitSearchKeywordBaselineCli.run(new LitSearchKeywordBaselineCli.Options(
                gold,
                Path.of("src/test/resources/eval/litsearch-mini-corpus.json"),
                retrieved,
                runsRoot,
                registry,
                cheatsheet,
                "keyword-only-baseline",
                "litsearch-mini",
                "run-litsearch-keyword-mini",
                "2026-06-23T22:10:00Z",
                2
        ));

        List<String> retrievedLines = Files.readAllLines(retrieved);
        JsonNode first = OBJECT_MAPPER.readTree(retrievedLines.get(0));
        JsonNode second = OBJECT_MAPPER.readTree(retrievedLines.get(1));
        assertEquals("litsearch_inline_acl_0000", first.path("caseId").asText());
        assertEquals("202719327", first.path("retrievedCorpusIds").get(0).asText());
        assertEquals("litsearch_inline_acl_0001", second.path("caseId").asText());
        assertEquals("226254579", second.path("retrievedCorpusIds").get(0).asText());

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(2, scorecard.path("caseCount").asInt());
        assertEquals(2, scorecard.path("passed").asInt());
        assertEquals(0.75d, scorecard.path("metrics").path("recallAt20").asDouble());

        String markdown = Files.readString(cheatsheet);
        assertTrue(markdown.contains("| keyword-only-baseline | LitSearch Mini | prototype | 2 | Recall@20 75.0% |"));
    }

    @Test
    void rejectsMiniCorpusWhenDatasetIdWouldBeReportedAsLitSearchFull() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini-query.jsonl");
        new LitSearchBenchmarkConverter().writeQueryJsonl(
                Path.of("src/test/resources/eval/litsearch-mini-query.json"),
                gold,
                0
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LitSearchKeywordBaselineCli.run(new LitSearchKeywordBaselineCli.Options(
                        gold,
                        Path.of("src/test/resources/eval/litsearch-mini-corpus.json"),
                        tempDir.resolve("keyword-retrieved.jsonl"),
                        tempDir.resolve("runs"),
                        registryYaml(),
                        tempDir.resolve("CHEATSHEET.md"),
                        "keyword-only-baseline",
                        "litsearch-full",
                        "run-litsearch-keyword-mini",
                        "2026-06-23T22:10:00Z",
                        2
                )));

        assertTrue(error.getMessage().contains("litsearch-full"));
        assertTrue(error.getMessage().contains("sample-specific dataset id"));
    }

    @Test
    void rejectsCorpusNamedFullWhenItDoesNotHaveFullCorpusRows() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini-query.jsonl");
        new LitSearchBenchmarkConverter().writeQueryJsonl(
                Path.of("src/test/resources/eval/litsearch-mini-query.json"),
                gold,
                0
        );
        Path corpus = tempDir.resolve("litsearch-corpus-clean-full.jsonl");
        LitSearchCorpusJsonlWriter.write(
                List.of(Path.of("src/test/resources/eval/litsearch-mini-corpus.json")),
                corpus,
                0
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LitSearchKeywordBaselineCli.run(new LitSearchKeywordBaselineCli.Options(
                        gold,
                        corpus,
                        tempDir.resolve("keyword-retrieved.jsonl"),
                        tempDir.resolve("runs"),
                        registryYaml(),
                        tempDir.resolve("CHEATSHEET.md"),
                        "keyword-only-baseline",
                        "litsearch-full",
                        "run-litsearch-keyword-fake-full",
                        "2026-06-24T15:40:00Z",
                        2
                )));

        assertTrue(error.getMessage().contains("litsearch-full"));
        assertTrue(error.getMessage().contains("64,183"));
    }

    @Test
    void retrievesFromMergedCorpusJsonl() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini-query.jsonl");
        new LitSearchBenchmarkConverter().writeQueryJsonl(
                Path.of("src/test/resources/eval/litsearch-mini-query.json"),
                gold,
                0
        );
        Path corpusJsonl = tempDir.resolve("litsearch-mini-corpus.jsonl");
        LitSearchCorpusJsonlWriter.write(
                List.of(Path.of("src/test/resources/eval/litsearch-mini-corpus.json")),
                corpusJsonl,
                0
        );
        Path retrieved = tempDir.resolve("keyword-retrieved-from-jsonl.jsonl");
        Path registry = registryYaml();

        Path runDir = LitSearchKeywordBaselineCli.run(new LitSearchKeywordBaselineCli.Options(
                gold,
                corpusJsonl,
                retrieved,
                tempDir.resolve("runs-jsonl"),
                registry,
                tempDir.resolve("CHEATSHEET-jsonl.md"),
                "keyword-only-baseline",
                "litsearch-mini",
                "run-litsearch-keyword-jsonl-mini",
                "2026-06-23T22:15:00Z",
                2
        ));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(0.75d, scorecard.path("metrics").path("recallAt20").asDouble());
        assertEquals(2, LitSearchPaperDocumentDataset.load(corpusJsonl).size());
    }

    @Test
    void corpusJsonlWriterCliMergesPagesWithLimit() throws Exception {
        Path output = tempDir.resolve("litsearch-corpus-one.jsonl");

        LitSearchCorpusJsonlWriter.main(new String[]{
                "--output", output.toString(),
                "--max-papers", "1",
                "src/test/resources/eval/litsearch-mini-corpus.json"
        });

        List<LitSearchPaperDocument> papers = LitSearchPaperDocumentDataset.load(output);
        assertEquals(1, papers.size());
        assertEquals("202719327", papers.get(0).paperId());
    }

    private Path registryYaml() throws Exception {
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: keyword-only-baseline
                    name: Keyword Only Baseline
                    description: Offline keyword retrieval baseline.
                    retrieval: keyword
                    planner: none
                    verifier: disabled
                    status: runnable
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
