package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.repository.PaperParserArtifactRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductPdfParserSmokeRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void pdfRowWithChunksParserArtifactAndPageScreenshotPasses() throws Exception {
        Fixture fixture = fixture();
        Paper paper = pdfPaper("paper-a", "adaptive.pdf");
        when(fixture.paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a"))
                .thenReturn(Optional.of(paper));
        when(fixture.chunkRepository.countByPaperId("paper-a")).thenReturn(3L);
        when(fixture.chunkRepository.countByPaperIdAndPageNumberIsNotNull("paper-a")).thenReturn(2L);
        when(fixture.parserArtifactRepository.countByPaperId("paper-a")).thenReturn(1L);
        when(fixture.visualAssetRepository.countByPaperIdAndAssetType(
                "paper-a",
                PaperVisualAsset.TYPE_PAGE_SCREENSHOT
        )).thenReturn(1L);

        Path runDir = fixture.runner.run(new ProductPdfParserSmokeRunner.Options(
                manifest("""
                        {"id":"ok","paperId":"paper-a","expectedMinChunks":2,"expectedMinPages":1,"requiresPageScreenshot":true}
                        """),
                tempDir.resolve("runs"),
                "pdf-smoke-ok",
                "2026-06-27T10:00:00Z",
                "product-pdf-parser-smoke",
                "product-pdf-parser-smoke"
        ));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(1, scorecard.path("caseCount").asInt());
        assertEquals(1, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());
        assertEquals(3.0d, scorecard.path("metrics").path("avgChunkCount").asDouble());
        JsonNode run = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile());
        assertTrue(run.path("cases").get(0).path("failures").isEmpty());
        assertEquals("paper-a", run.path("cases").get(0).path("diagnostics").path("paperId").asText());
    }

    @Test
    void pdfRowWithChunksButMissingParserArtifactFails() throws Exception {
        Fixture fixture = fixture();
        Paper paper = pdfPaper("paper-a", "adaptive.pdf");
        when(fixture.paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a"))
                .thenReturn(Optional.of(paper));
        when(fixture.chunkRepository.countByPaperId("paper-a")).thenReturn(3L);
        when(fixture.chunkRepository.countByPaperIdAndPageNumberIsNotNull("paper-a")).thenReturn(2L);
        when(fixture.parserArtifactRepository.countByPaperId("paper-a")).thenReturn(0L);

        Path runDir = fixture.runner.run(new ProductPdfParserSmokeRunner.Options(
                manifest("""
                        {"id":"missing_artifact","paperId":"paper-a","expectedMinChunks":1,"expectedMinPages":1}
                        """),
                tempDir.resolve("runs"),
                "pdf-smoke-missing-artifact",
                "2026-06-27T10:05:00Z",
                "product-pdf-parser-smoke",
                "product-pdf-parser-smoke"
        ));

        JsonNode run = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile());
        JsonNode row = run.path("cases").get(0);
        assertEquals(false, row.path("passed").asBoolean());
        assertTrue(row.path("failures").toString().contains("parser_artifact_missing"));
        assertTrue(row.path("failureClass").toString().contains("PARSER_ARTIFACT"));
    }

    @Test
    void jsonRowIsRejectedAsParserSmokeEvidence() throws Exception {
        Fixture fixture = fixture();
        Paper paper = pdfPaper("qasper:123", "qasper-paper.json");
        when(fixture.paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("qasper:123"))
                .thenReturn(Optional.of(paper));
        when(fixture.chunkRepository.countByPaperId("qasper:123")).thenReturn(5L);
        when(fixture.chunkRepository.countByPaperIdAndPageNumberIsNotNull("qasper:123")).thenReturn(5L);
        when(fixture.parserArtifactRepository.countByPaperId("qasper:123")).thenReturn(1L);

        Path runDir = fixture.runner.run(new ProductPdfParserSmokeRunner.Options(
                manifest("""
                        {"id":"eval_json","paperId":"qasper:123","expectedMinChunks":1}
                        """),
                tempDir.resolve("runs"),
                "pdf-smoke-eval-json",
                "2026-06-27T10:10:00Z",
                "product-pdf-parser-smoke",
                "product-pdf-parser-smoke"
        ));

        JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile())
                .path("cases").get(0);
        assertEquals(false, row.path("passed").asBoolean());
        assertTrue(row.path("failures").toString().contains("json_row_not_pdf"));
        assertTrue(row.path("failureClass").toString().contains("PDF_PROVENANCE"));
    }

    @Test
    void missingPageScreenshotFailsOnlyWhenManifestRequiresVisualPageEvidence() throws Exception {
        Fixture fixture = fixture();
        Paper paper = pdfPaper("paper-a", "adaptive.pdf");
        when(fixture.paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a"))
                .thenReturn(Optional.of(paper));
        when(fixture.chunkRepository.countByPaperId("paper-a")).thenReturn(3L);
        when(fixture.chunkRepository.countByPaperIdAndPageNumberIsNotNull("paper-a")).thenReturn(2L);
        when(fixture.parserArtifactRepository.countByPaperId("paper-a")).thenReturn(1L);
        when(fixture.visualAssetRepository.countByPaperIdAndAssetType(
                "paper-a",
                PaperVisualAsset.TYPE_PAGE_SCREENSHOT
        )).thenReturn(0L);

        Path runDir = fixture.runner.run(new ProductPdfParserSmokeRunner.Options(
                manifest("""
                        {"id":"text_only_ok","paperId":"paper-a","expectedMinChunks":1,"expectedMinPages":1,"requiresPageScreenshot":false}
                        {"id":"visual_required_fail","paperId":"paper-a","expectedMinChunks":1,"expectedMinPages":1,"requiresPageScreenshot":true}
                        """),
                tempDir.resolve("runs"),
                "pdf-smoke-page-screenshot",
                "2026-06-27T10:15:00Z",
                "product-pdf-parser-smoke",
                "product-pdf-parser-smoke"
        ));

        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        assertEquals(true, rows.get(0).path("passed").asBoolean());
        assertEquals(false, rows.get(1).path("passed").asBoolean());
        assertTrue(rows.get(1).path("failures").toString().contains("page_screenshot_count_below_min"));
        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(0.5d, scorecard.path("passRate").asDouble());
    }

    @Test
    void cliUsesSpringStartupArgsThatAvoidEvalSideEffects() {
        assertTrue(Arrays.asList(ProductPdfParserSmokeCli.springStartupArgs())
                .contains("--elasticsearch.init.enabled=false"));
        assertTrue(Arrays.asList(ProductPdfParserSmokeCli.springStartupArgs())
                .contains("--spring.kafka.listener.auto-startup=false"));
        assertTrue(Arrays.asList(ProductPdfParserSmokeCli.springStartupArgs())
                .contains("--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"));
        assertTrue(Arrays.asList(ProductPdfParserSmokeCli.springStartupArgs())
                .contains("--admin.bootstrap.enabled=false"));
        assertTrue(Arrays.asList(ProductPdfParserSmokeCli.springStartupArgs())
                .contains("--paper.bootstrap.enabled=false"));
    }

    @Test
    void cliParsesDefaultRunShape() {
        ProductPdfParserSmokeCli.Options options = ProductPdfParserSmokeCli.Options.parse(new String[]{
                "--manifest", "eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl",
                "--started-at", "2026-06-27T10:20:00Z"
        });

        assertEquals(Path.of("eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl"), options.manifestPath());
        assertEquals(Path.of("eval/rag/runs"), options.runsRoot());
        assertEquals("product-pdf-parser-smoke", options.harnessId());
        assertEquals("product-pdf-parser-smoke", options.datasetId());
        assertEquals("2026-06-27T102000Z-product-pdf-parser-smoke-product-pdf-parser-smoke", options.runId());
    }

    @Test
    void structuredContentCountsComeFromCurrentReadingModel() throws Exception {
        Fixture fixture = fixture();
        Paper paper = pdfPaper("paper-a", "adaptive.pdf");
        when(fixture.paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a"))
                .thenReturn(Optional.of(paper));
        when(fixture.chunkRepository.countByPaperId("paper-a")).thenReturn(3L);
        when(fixture.chunkRepository.countByPaperIdAndPageNumberIsNotNull("paper-a")).thenReturn(2L);
        when(fixture.parserArtifactRepository.countByPaperId("paper-a")).thenReturn(1L);

        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion("rm-1");
        when(fixture.readingModelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(model));
        when(fixture.readingElementRepository.countByPaperIdAndModelVersionAndElementType("paper-a", "rm-1", "TABLE"))
                .thenReturn(1L);
        when(fixture.readingElementRepository.countByPaperIdAndModelVersionAndElementType("paper-a", "rm-1", "IMAGE"))
                .thenReturn(1L);
        when(fixture.readingElementRepository.countByPaperIdAndModelVersionAndElementType("paper-a", "rm-1", "CHART"))
                .thenReturn(1L);
        when(fixture.readingElementRepository.countByPaperIdAndModelVersionAndElementType("paper-a", "rm-1", "FORMULA"))
                .thenReturn(1L);

        Path runDir = fixture.runner.run(new ProductPdfParserSmokeRunner.Options(
                manifest("""
                        {"id":"structured","paperId":"paper-a","expectedMinChunks":1,"expectedMinPages":1,"requiresTableOrFigure":true,"expectedMinTables":1,"expectedMinFigures":2,"expectedMinFormulas":1}
                        """),
                tempDir.resolve("runs"),
                "pdf-smoke-structured",
                "2026-06-27T10:25:00Z",
                "product-pdf-parser-smoke",
                "product-pdf-parser-smoke"
        ));

        JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile())
                .path("cases").get(0);
        assertEquals(true, row.path("passed").asBoolean());
        assertEquals("rm-1", row.path("diagnostics").path("readingModelVersion").asText());
        assertEquals("paper_reading_elements", row.path("diagnostics").path("structuredContentSource").asText());
        assertEquals(1, row.path("diagnostics").path("tableCount").asInt());
        assertEquals(2, row.path("diagnostics").path("figureCount").asInt());
        assertEquals(1, row.path("diagnostics").path("formulaCount").asInt());
    }

    private Path manifest(String content) throws Exception {
        Path manifest = tempDir.resolve("manifest-" + System.nanoTime() + ".jsonl");
        Files.writeString(manifest, content);
        return manifest;
    }

    private Paper pdfPaper(String paperId, String originalFilename) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setOriginalFilename(originalFilename);
        paper.setPaperTitle("Adaptive Retrieval");
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        paper.setCreatedAt(LocalDateTime.of(2026, 6, 27, 10, 0));
        return paper;
    }

    private Fixture fixture() {
        PaperRepository paperRepository = mock(PaperRepository.class);
        PaperTextChunkRepository chunkRepository = mock(PaperTextChunkRepository.class);
        PaperParserArtifactRepository parserArtifactRepository = mock(PaperParserArtifactRepository.class);
        PaperVisualAssetRepository visualAssetRepository = mock(PaperVisualAssetRepository.class);
        PaperReadingModelRepository readingModelRepository = mock(PaperReadingModelRepository.class);
        PaperReadingElementRepository readingElementRepository = mock(PaperReadingElementRepository.class);
        ProductPdfParserSmokeRunner runner = new ProductPdfParserSmokeRunner(
                paperRepository,
                chunkRepository,
                parserArtifactRepository,
                visualAssetRepository,
                readingModelRepository,
                readingElementRepository
        );
        return new Fixture(
                runner,
                paperRepository,
                chunkRepository,
                parserArtifactRepository,
                visualAssetRepository,
                readingModelRepository,
                readingElementRepository
        );
    }

    private record Fixture(
            ProductPdfParserSmokeRunner runner,
            PaperRepository paperRepository,
            PaperTextChunkRepository chunkRepository,
            PaperParserArtifactRepository parserArtifactRepository,
            PaperVisualAssetRepository visualAssetRepository,
            PaperReadingModelRepository readingModelRepository,
            PaperReadingElementRepository readingElementRepository
    ) {
    }
}
