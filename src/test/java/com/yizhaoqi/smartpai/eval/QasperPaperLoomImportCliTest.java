package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void parsesImportOptionsForEvalQasperCorpusAndServiceCases() {
        QasperPaperLoomImportCli.Options options = QasperPaperLoomImportCli.Options.parse(new String[]{
                "--chunks", "eval/rag/qasper/generated/qasper-dev-200-paragraph-chunks.jsonl",
                "--rag-cases", "eval/rag/qasper/generated/qasper-dev-200-rag-cases.jsonl",
                "--cases-output", "eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl",
                "--retrieval-corpus", "EVAL_QASPER",
                "--limit-papers", "10",
                "--eval-split", "dev"
        });

        assertEquals(Path.of("eval/rag/qasper/generated/qasper-dev-200-paragraph-chunks.jsonl"), options.chunksPath());
        assertEquals(Path.of("eval/rag/qasper/generated/qasper-dev-200-rag-cases.jsonl"), options.ragCasesPath());
        assertEquals(Path.of("eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl"), options.casesOutputPath());
        assertEquals(RetrievalCorpus.EVAL_QASPER, options.retrievalCorpus());
        assertEquals(10, options.limitPapers());
        assertEquals("dev", options.evalSplit());
    }

    @Test
    void refusesMissingRetrievalCorpus() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                QasperPaperLoomImportCli.Options.parse(new String[]{
                        "--chunks", "chunks.jsonl"
                })
        );

        assertTrue(error.getMessage().contains("Missing --retrieval-corpus"));
    }

    @Test
    void refusesNonQasperRetrievalCorpus() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                QasperPaperLoomImportCli.Options.parse(new String[]{
                        "--chunks", "chunks.jsonl",
                        "--retrieval-corpus", "PRODUCT_LIBRARY"
                })
        );

        assertTrue(error.getMessage().contains("EVAL_QASPER"));
    }
}
