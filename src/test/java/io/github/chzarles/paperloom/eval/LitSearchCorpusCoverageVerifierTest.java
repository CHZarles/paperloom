package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitSearchCorpusCoverageVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsNextResumeOffsetAtFirstMissingPage() throws Exception {
        writePage(0, 100);
        writePage(100, 100);
        writePage(300, 100);

        LitSearchCorpusCoverageVerifier.CoverageReport report = LitSearchCorpusCoverageVerifier.verify(
                options(0, 450, 100)
        );

        assertFalse(report.complete());
        assertEquals(200, report.contiguousRows());
        assertEquals(200, report.nextResumeOffset());
        assertEquals(2, report.validPages());
        assertTrue(report.problems().get(0).contains("missing page"));
        assertTrue(report.problems().get(0).contains("200"));
    }

    @Test
    void stopsCoverageAtFirstPageWithWrongRowIndexes() throws Exception {
        writePage(0, 100);
        Path badPage = tempDir.resolve("litsearch-corpus-clean-page-00100.json");
        Files.writeString(badPage, rowsJsonWithMismatch(100, 100, 1, 102));

        LitSearchCorpusCoverageVerifier.CoverageReport report = LitSearchCorpusCoverageVerifier.verify(
                options(0, 300, 100)
        );

        assertFalse(report.complete());
        assertEquals(100, report.contiguousRows());
        assertEquals(100, report.nextResumeOffset());
        assertEquals(1, report.validPages());
        assertTrue(report.problems().get(0).contains("row_idx"));
        assertTrue(report.problems().get(0).contains("101"));
    }

    @Test
    void acceptsFinalShortPageWhenItCompletesTheCorpus() throws Exception {
        writePage(0, 100);
        writePage(100, 100);
        writePage(200, 5);

        LitSearchCorpusCoverageVerifier.CoverageReport report = LitSearchCorpusCoverageVerifier.verify(
                options(0, 205, 100)
        );

        assertTrue(report.complete());
        assertEquals(205, report.contiguousRows());
        assertEquals(205, report.nextResumeOffset());
        assertEquals(3, report.validPages());
        assertTrue(report.problems().isEmpty());
    }

    @Test
    void cliPrintsNextResumeOffsetAndProblems() throws Exception {
        writePage(0, 100);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));

            LitSearchCorpusCoverageVerifier.main(new String[]{
                    "--input-dir", tempDir.toString(),
                    "--filename-format", "litsearch-corpus-clean-page-%05d.json",
                    "--start-offset", "0",
                    "--total-rows", "300",
                    "--page-size", "100"
            });
        } finally {
            System.setOut(originalOut);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("complete=false"));
        assertTrue(output.contains("contiguousRows=100"));
        assertTrue(output.contains("nextResumeOffset=100"));
        assertTrue(output.contains("missing page"));
    }

    private LitSearchCorpusCoverageVerifier.Options options(int startOffset, int totalRows, int pageSize) {
        return new LitSearchCorpusCoverageVerifier.Options(
                tempDir,
                "litsearch-corpus-clean-page-%05d.json",
                startOffset,
                totalRows,
                pageSize
        );
    }

    private void writePage(int offset, int rows) throws Exception {
        Files.writeString(tempDir.resolve(String.format(java.util.Locale.ROOT,
                "litsearch-corpus-clean-page-%05d.json",
                offset
        )), rowsJson(offset, rows));
    }

    private String rowsJson(int offset, int rows) {
        StringBuilder builder = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append("{\"row_idx\":").append(offset + i).append("}");
        }
        return builder.append("]}").toString();
    }

    private String rowsJsonWithMismatch(int offset, int rows, int mismatchPosition, int actualRowIndex) {
        StringBuilder builder = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                builder.append(",");
            }
            int rowIndex = i == mismatchPosition ? actualRowIndex : offset + i;
            builder.append("{\"row_idx\":").append(rowIndex).append("}");
        }
        return builder.append("]}").toString();
    }
}
