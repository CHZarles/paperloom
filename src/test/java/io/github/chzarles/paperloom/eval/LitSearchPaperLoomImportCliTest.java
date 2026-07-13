package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitSearchPaperLoomImportCliTest {

    @Test
    void usesSpringStartupArgsThatAvoidImportCliSideEffects() {
        List<String> args = Arrays.asList(LitSearchPaperLoomImportCli.springStartupArgs());

        assertTrue(args.contains("--elasticsearch.init.enabled=false"));
        assertTrue(args.contains("--spring.kafka.listener.auto-startup=false"));
        assertTrue(args.contains("--spring.kafka.admin.auto-create=false"));
        assertTrue(args.contains("--admin.bootstrap.enabled=false"));
        assertTrue(args.contains("--paper.bootstrap.enabled=false"));
        assertTrue(args.contains("--spring.jpa.show-sql=false"));
        assertTrue(args.contains("--logging.level.root=WARN"));
        assertTrue(args.contains("--logging.level.org.hibernate.SQL=WARN"));
        assertTrue(args.contains("--logging.level.io.github.chzarles.paperloom.eval.EvalCorpusIndexService=WARN"));
    }

    @Test
    void parsesImportOptionsForEvalLitSearchCorpus() {
        LitSearchPaperLoomImportCli.Options options = LitSearchPaperLoomImportCli.Options.parse(new String[]{
                "--corpus", "eval/rag/litsearch/generated/litsearch-corpus-clean-sample-20.jsonl",
                "--retrieval-corpus", "EVAL_LITSEARCH",
                "--start-offset", "1000",
                "--limit", "20",
                "--max-chunk-characters", "1200",
                "--eval-split", "dev-sample",
                "--index-batch-size", "25"
        });

        assertEquals(Path.of("eval/rag/litsearch/generated/litsearch-corpus-clean-sample-20.jsonl"), options.corpusPath());
        assertEquals(RetrievalCorpus.EVAL_LITSEARCH, options.retrievalCorpus());
        assertEquals(1000, options.startOffset());
        assertEquals(20, options.limit());
        assertEquals(1200, options.maxChunkCharacters());
        assertEquals("dev-sample", options.evalSplit());
        assertEquals(25, options.indexBatchSize());
    }

    @Test
    void refusesMissingRetrievalCorpus() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LitSearchPaperLoomImportCli.Options.parse(new String[]{
                        "--corpus", "sample.jsonl"
                })
        );

        assertTrue(error.getMessage().contains("Missing --retrieval-corpus"));
    }

    @Test
    void refusesNonLitSearchRetrievalCorpus() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LitSearchPaperLoomImportCli.Options.parse(new String[]{
                        "--corpus", "sample.jsonl",
                        "--retrieval-corpus", "PRODUCT_LIBRARY"
                })
        );

        assertTrue(error.getMessage().contains("EVAL_LITSEARCH"));
    }
}
