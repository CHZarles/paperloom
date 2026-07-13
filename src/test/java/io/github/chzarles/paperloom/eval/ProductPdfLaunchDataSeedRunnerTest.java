package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPdfLaunchDataSeedRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void uploadsChunksMergesPollsFrontendSearchableStatusAndWritesPassingRun() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\nlaunch-data-seed".getBytes();
        Path pdf = tempDir.resolve("launch-paper.pdf");
        Files.write(pdf, pdfBytes);
        Path manifest = manifestFor(pdf);
        String paperId = md5(pdfBytes);
        FakeSeedClient client = new FakeSeedClient(List.of(List.of(), List.of(searchableStatus(paperId))));

        ProductPdfLaunchDataSeedRunner runner = new ProductPdfLaunchDataSeedRunner(client);
        Path runDir = runner.run(options(manifest, 8, 1));

        assertEquals(4, client.uploads.size());
        assertEquals(paperId, client.uploads.get(0).paperId());
        assertEquals(0, client.uploads.get(0).chunkIndex());
        assertEquals(4, client.uploads.get(0).totalChunks());
        assertEquals(pdfBytes.length, client.uploads.get(0).totalSize());
        assertArrayEquals(slice(pdfBytes, 0, 8), client.uploads.get(0).bytes());
        assertArrayEquals(slice(pdfBytes, 24, pdfBytes.length), client.uploads.get(3).bytes());
        assertEquals(List.of(new ProductPdfLaunchDataSeedRunner.MergeRequest(
                paperId,
                "launch-paper.pdf"
        )), client.merges);
        assertEquals(2, client.listCalls);

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(1, scorecard.path("caseCount").asInt());
        assertEquals(1, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());

        JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases").get(0);
        assertTrue(row.path("passed").asBoolean());
        assertEquals(paperId, row.path("diagnostics").path("computedPaperId").asText());
        assertTrue(row.path("diagnostics").path("frontendSearchable").asBoolean());
    }

    @Test
    void existingSearchableManifestPaperPassesWithoutReuploading() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\nexisting-launch-data".getBytes();
        Path pdf = tempDir.resolve("existing-paper.pdf");
        Files.write(pdf, pdfBytes);
        Path manifest = manifestFor(pdf);
        String paperId = md5(pdfBytes);
        FakeSeedClient client = new FakeSeedClient(List.of(List.of(searchableStatus(paperId))));

        ProductPdfLaunchDataSeedRunner runner = new ProductPdfLaunchDataSeedRunner(client);
        Path runDir = runner.run(options(manifest, 8, 1));

        assertEquals(0, client.uploads.size());
        assertEquals(0, client.merges.size());
        assertEquals(1, client.listCalls);

        JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases").get(0);
        assertTrue(row.path("passed").asBoolean());
        assertTrue(row.path("diagnostics").path("alreadySeeded").asBoolean());
        assertFalse(row.path("diagnostics").path("merged").asBoolean());
        assertTrue(row.path("diagnostics").path("frontendSearchable").asBoolean());
        assertEquals(paperId, row.path("diagnostics").path("computedPaperId").asText());
    }

    @Test
    void failsWhenUploadedPaperNeverBecomesFrontendSearchable() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\nnot-searchable".getBytes();
        Path pdf = tempDir.resolve("not-searchable.pdf");
        Files.write(pdf, pdfBytes);
        Path manifest = manifestFor(pdf);
        String paperId = md5(pdfBytes);
        FakeSeedClient client = new FakeSeedClient(List.of(List.of(new ProductPdfLaunchDataSeedRunner.PaperStatus(
                paperId,
                "not-searchable.pdf",
                1,
                "COMPLETED",
                null,
                0,
                Map.of()
        ))));

        ProductPdfLaunchDataSeedRunner runner = new ProductPdfLaunchDataSeedRunner(client);
        Path runDir = runner.run(options(manifest, 1024, 1));

        JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases").get(0);
        assertFalse(row.path("passed").asBoolean());
        assertTrue(row.path("failures").toString().contains("front_searchable_missing"));
        assertTrue(row.path("failureClass").toString().contains("FRONT_SEARCHABLE_MISSING"));
        assertEquals(1, client.merges.size());
    }

    @Test
    void missingLocalPdfFailsWithoutCallingClient() throws Exception {
        Path missingPdf = tempDir.resolve("missing.pdf");
        Path manifest = manifestFor(missingPdf);
        FakeSeedClient client = new FakeSeedClient(List.of());

        ProductPdfLaunchDataSeedRunner runner = new ProductPdfLaunchDataSeedRunner(client);
        Path runDir = runner.run(options(manifest, 1024, 1));

        JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases").get(0);
        assertFalse(row.path("passed").asBoolean());
        assertTrue(row.path("failures").toString().contains("local_pdf_missing"));
        assertTrue(row.path("failureClass").toString().contains("LOCAL_PDF_MISSING"));
        assertEquals(0, client.uploads.size());
        assertEquals(0, client.merges.size());
        assertEquals(0, client.listCalls);
    }

    private Path manifestFor(Path pdf) throws Exception {
        Path manifest = tempDir.resolve("manifest-" + System.nanoTime() + ".jsonl");
        Files.writeString(manifest, "{\"id\":\"launch_pdf\",\"path\":\""
                + pdf.toString().replace("\\", "\\\\") + "\"}\n");
        return manifest;
    }

    private ProductPdfLaunchDataSeedRunner.Options options(Path manifest, int chunkSizeBytes, int pollAttempts) {
        return new ProductPdfLaunchDataSeedRunner.Options(
                manifest,
                tempDir.resolve("runs"),
                "launch-data-seed-test",
                "2026-07-07T13:00:00Z",
                "product-pdf-launch-data-seed",
                "product-pdf-launch-30",
                chunkSizeBytes,
                pollAttempts,
                0L,
                true
        );
    }

    private static ProductPdfLaunchDataSeedRunner.PaperStatus searchableStatus(String paperId) {
        return new ProductPdfLaunchDataSeedRunner.PaperStatus(
                paperId,
                "launch-paper.pdf",
                1,
                "COMPLETED",
                42L,
                3,
                Map.of("source", "fake")
        );
    }

    private static byte[] slice(byte[] bytes, int start, int end) {
        byte[] result = new byte[end - start];
        System.arraycopy(bytes, start, result, 0, result.length);
        return result;
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    private static final class FakeSeedClient implements ProductPdfLaunchDataSeedRunner.LaunchDataSeedClient {
        private final List<List<ProductPdfLaunchDataSeedRunner.PaperStatus>> statusPolls;
        private final List<ProductPdfLaunchDataSeedRunner.UploadChunkRequest> uploads = new ArrayList<>();
        private final List<ProductPdfLaunchDataSeedRunner.MergeRequest> merges = new ArrayList<>();
        private int listCalls;

        private FakeSeedClient(List<List<ProductPdfLaunchDataSeedRunner.PaperStatus>> statusPolls) {
            this.statusPolls = new ArrayList<>(statusPolls);
        }

        @Override
        public void uploadChunk(ProductPdfLaunchDataSeedRunner.UploadChunkRequest request) {
            uploads.add(request);
        }

        @Override
        public void merge(ProductPdfLaunchDataSeedRunner.MergeRequest request) {
            merges.add(request);
        }

        @Override
        public List<ProductPdfLaunchDataSeedRunner.PaperStatus> listUploadedPapers() {
            listCalls++;
            if (statusPolls.isEmpty()) {
                return List.of();
            }
            return statusPolls.remove(0);
        }
    }
}
