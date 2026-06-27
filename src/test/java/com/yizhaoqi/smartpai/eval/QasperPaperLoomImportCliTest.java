package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QasperPaperLoomImportCliTest {

    @Test
    void usesSpringStartupArgsThatAvoidImportCliSideEffects() {
        List<String> args = Arrays.asList(QasperPaperLoomImportCli.springStartupArgs());

        assertTrue(args.contains("--elasticsearch.init.enabled=false"));
        assertTrue(args.contains("--spring.kafka.listener.auto-startup=false"));
        assertTrue(args.contains("--admin.bootstrap.enabled=false"));
        assertTrue(args.contains("--paper.bootstrap.enabled=false"));
    }

    @Test
    void parsesImportOptionsForEvalScopedQasperRowsAndServiceCases() {
        QasperPaperLoomImportCli.Options options = QasperPaperLoomImportCli.Options.parse(new String[]{
                "--chunks", "eval/rag/qasper/generated/qasper-dev-200-paragraph-chunks.jsonl",
                "--rag-cases", "eval/rag/qasper/generated/qasper-dev-200-rag-cases.jsonl",
                "--cases-output", "eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl",
                "--user-id", "eval-user",
                "--org-tag", "eval-qasper",
                "--public", "false",
                "--limit-papers", "10",
                "--eval-split", "dev"
        });

        assertEquals(Path.of("eval/rag/qasper/generated/qasper-dev-200-paragraph-chunks.jsonl"), options.chunksPath());
        assertEquals(Path.of("eval/rag/qasper/generated/qasper-dev-200-rag-cases.jsonl"), options.ragCasesPath());
        assertEquals(Path.of("eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl"), options.casesOutputPath());
        assertEquals("eval-user", options.userId());
        assertEquals("eval-qasper", options.orgTag());
        assertFalse(options.isPublic());
        assertEquals(10, options.limitPapers());
        assertEquals("dev", options.evalSplit());
    }

    @Test
    void defaultsToEvalQasperImportMarkers() {
        QasperPaperLoomImportCli.Options options = QasperPaperLoomImportCli.Options.parse(new String[]{
                "--chunks", "chunks.jsonl"
        });

        assertEquals(Path.of("chunks.jsonl"), options.chunksPath());
        assertEquals(null, options.ragCasesPath());
        assertEquals(null, options.casesOutputPath());
        assertEquals("eval-qasper-user", options.userId());
        assertEquals("eval-qasper", options.orgTag());
        assertTrue(options.isPublic());
        assertEquals(0, options.limitPapers());
        assertEquals("dev", options.evalSplit());
    }
}
