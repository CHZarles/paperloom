package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(args.contains("--logging.level.com.yizhaoqi.smartpai.service.ElasticsearchService=WARN"));
    }

    @Test
    void parsesImportOptionsForEvalScopedLitSearchRows() {
        LitSearchPaperLoomImportCli.Options options = LitSearchPaperLoomImportCli.Options.parse(new String[]{
                "--corpus", "eval/rag/litsearch/generated/litsearch-corpus-clean-sample-20.jsonl",
                "--user-id", "eval-user",
                "--org-tag", "eval-litsearch",
                "--public", "false",
                "--start-offset", "1000",
                "--limit", "20",
                "--max-chunk-characters", "1200",
                "--eval-split", "dev-sample",
                "--index-batch-size", "25"
        });

        assertEquals(Path.of("eval/rag/litsearch/generated/litsearch-corpus-clean-sample-20.jsonl"), options.corpusPath());
        assertEquals("eval-user", options.userId());
        assertEquals("eval-litsearch", options.orgTag());
        assertFalse(options.isPublic());
        assertEquals(1000, options.startOffset());
        assertEquals(20, options.limit());
        assertEquals(1200, options.maxChunkCharacters());
        assertEquals("dev-sample", options.evalSplit());
        assertEquals(25, options.indexBatchSize());
    }

    @Test
    void defaultsToEvalLitSearchImportMarkers() {
        LitSearchPaperLoomImportCli.Options options = LitSearchPaperLoomImportCli.Options.parse(new String[]{
                "--corpus", "sample.jsonl"
        });

        assertEquals("eval-litsearch-user", options.userId());
        assertEquals("eval-litsearch", options.orgTag());
        assertTrue(options.isPublic());
        assertEquals(0, options.startOffset());
        assertEquals(0, options.limit());
        assertEquals(1800, options.maxChunkCharacters());
        assertEquals("full", options.evalSplit());
        assertEquals(500, options.indexBatchSize());
    }
}
