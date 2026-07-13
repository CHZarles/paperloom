package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCorpusMigrationCliTest {

    @Test
    void parseRequiresPaperloomEvalTargetSchema() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                EvalCorpusMigrationCli.Options.parse(new String[]{
                        "--source-schema", "paperloom",
                        "--target-schema", "other_eval",
                        "--corpus", "all",
                        "--dry-run"
                })
        );

        assertTrue(error.getMessage().contains("target schema must be paperloom_eval"));
    }

    @Test
    void dryRunPrintsSourceTargetAndExpectedCorpusCounts() {
        EvalCorpusMigrationCli.Options options = EvalCorpusMigrationCli.Options.parse(new String[]{
                "--source-schema", "paperloom",
                "--target-schema", "paperloom_eval",
                "--corpus", "all",
                "--dry-run"
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = EvalCorpusMigrationCli.run(
                options,
                (sourceSchema, corpora) -> List.of(
                        new EvalCorpusMigrationCli.CorpusCount("litsearch", 64183, 1479874),
                        new EvalCorpusMigrationCli.CorpusCount("qasper", 71, 903)
                ),
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(text.contains("source schema: paperloom"));
        assertTrue(text.contains("target schema: paperloom_eval"));
        assertTrue(text.contains("litsearch expected papers: 64183"));
        assertTrue(text.contains("qasper expected papers: 71"));
        assertTrue(text.contains("dryRun=true"));
    }
}
