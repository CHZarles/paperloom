package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitSearchBenchmarkConverterTest {

    @TempDir
    Path tempDir;

    @Test
    void convertsHuggingFaceQueryRowsToRetrievalCases() throws Exception {
        List<LitSearchBenchmarkCase> cases = new LitSearchBenchmarkConverter()
                .convertQueries(Path.of("src/test/resources/eval/litsearch-mini-query.json"), 10);

        assertEquals(2, cases.size());
        LitSearchBenchmarkCase first = cases.get(0);
        assertEquals("litsearch_inline_acl_0000", first.id());
        assertEquals("LITSEARCH_RETRIEVAL", first.taskType());
        assertEquals("inline_acl", first.querySet());
        assertTrue(first.query().contains("task-agnostic knowledge distillation"));
        assertEquals(0, first.specificity());
        assertEquals(2, first.quality());
        assertEquals(List.of("202719327"), first.goldCorpusIds());
    }

    @Test
    void writesAndLoadsRetrievalCasesAsJsonl() throws Exception {
        Path output = tempDir.resolve("litsearch-query-mini.jsonl");

        new LitSearchBenchmarkConverter()
                .writeQueryJsonl(Path.of("src/test/resources/eval/litsearch-mini-query.json"), output, 1);

        List<LitSearchBenchmarkCase> cases = LitSearchBenchmarkDataset.load(output);
        assertEquals(1, cases.size());
        assertEquals("litsearch_inline_acl_0000", cases.get(0).id());
        assertEquals(List.of("202719327"), cases.get(0).goldCorpusIds());
    }

    @Test
    void convertsCleanCorpusRowsToPaperDocuments() throws Exception {
        List<LitSearchPaperDocument> papers = new LitSearchBenchmarkConverter()
                .convertCorpus(Path.of("src/test/resources/eval/litsearch-mini-corpus.json"), 10);

        assertEquals(2, papers.size());
        LitSearchPaperDocument first = papers.get(0);
        assertEquals("202719327", first.paperId());
        assertEquals("Task-Agnostic Knowledge Distillation for Compressing Large Language Models", first.title());
        assertTrue(first.abstractText().contains("task-agnostic knowledge distillation"));
        assertTrue(first.fullPaperText().contains("large-scale language models"));
    }
}
