package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCorpusCleanupCliTest {

    @Test
    void parseAllowsDryRunWithoutDeleteConfirmation() {
        EvalCorpusCleanupCli.Options options = EvalCorpusCleanupCli.Options.parse(new String[]{
                "--source-schema", "paismart",
                "--eval-schema", "paperloom_eval",
                "--dry-run"
        });

        assertEquals("paismart", options.sourceSchema());
        assertEquals("paperloom_eval", options.evalSchema());
        assertEquals(true, options.dryRun());
        assertEquals(false, options.confirmDeleteEvalFromProduct());
    }

    @Test
    void parseRequiresConfirmationForActualCleanup() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                EvalCorpusCleanupCli.Options.parse(new String[]{
                        "--source-schema", "paismart",
                        "--eval-schema", "paperloom_eval"
                })
        );

        assertTrue(error.getMessage().contains("confirmed cleanup requires --confirm-delete-eval-from-product"));
    }

    @Test
    void parseRequiresEvalSchemaBoundary() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                EvalCorpusCleanupCli.Options.parse(new String[]{
                        "--source-schema", "paismart",
                        "--eval-schema", "paismart_eval",
                        "--dry-run"
                })
        );

        assertTrue(error.getMessage().contains("eval schema must be paperloom_eval"));
    }

    @Test
    void dryRunPrintsPlannedCountsAndDoesNotDelete() {
        EvalCorpusCleanupCli.Options options = EvalCorpusCleanupCli.Options.parse(new String[]{
                "--source-schema", "paismart",
                "--eval-schema", "paperloom_eval",
                "--dry-run"
        });
        FakeCleanupStore store = new FakeCleanupStore();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = EvalCorpusCleanupCli.run(options, store, new PrintStream(output, true, StandardCharsets.UTF_8));

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertEquals(1, store.countCalls);
        assertEquals(0, store.cleanupCalls);
        assertTrue(text.contains("planned cleanup"));
        assertTrue(text.contains("source schema: paismart"));
        assertTrue(text.contains("eval schema: paperloom_eval"));
        assertTrue(text.contains("file_upload rows: 64254"));
        assertTrue(text.contains("paper_text_chunks rows: 1480777"));
        assertTrue(text.contains("product paper_search docs: 64254"));
        assertTrue(text.contains("legacy eval columns: 4"));
        assertTrue(text.contains("product eval tables: 4"));
    }

    @Test
    void confirmedCleanupPrintsDeletedCounts() {
        EvalCorpusCleanupCli.Options options = EvalCorpusCleanupCli.Options.parse(new String[]{
                "--source-schema", "paismart",
                "--eval-schema", "paperloom_eval",
                "--confirm-delete-eval-from-product"
        });
        FakeCleanupStore store = new FakeCleanupStore();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = EvalCorpusCleanupCli.run(options, store, new PrintStream(output, true, StandardCharsets.UTF_8));

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertEquals(1, store.countCalls);
        assertEquals(1, store.cleanupCalls);
        assertTrue(text.contains("planned cleanup"));
        assertTrue(text.contains("deleted cleanup"));
        assertTrue(text.contains("conversations rows: 12"));
        assertTrue(text.contains("product paper_chunks docs: 1480777"));
    }

    private static final class FakeCleanupStore implements EvalCorpusCleanupCli.CleanupStore {
        private int countCalls;
        private int cleanupCalls;

        @Override
        public EvalCorpusCleanupCli.CleanupCounts count(EvalCorpusCleanupCli.Options options) {
            countCalls += 1;
            return counts();
        }

        @Override
        public EvalCorpusCleanupCli.CleanupCounts cleanup(EvalCorpusCleanupCli.Options options) {
            cleanupCalls += 1;
            return counts();
        }

        private EvalCorpusCleanupCli.CleanupCounts counts() {
            return new EvalCorpusCleanupCli.CleanupCounts(
                    64254,
                    64254,
                    1480777,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    12,
                    64254,
                    1480777,
                    4,
                    4
            );
        }
    }
}
