package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperVisualAsset;
import com.yizhaoqi.smartpai.repository.PaperFigureRepository;
import com.yizhaoqi.smartpai.repository.PaperFormulaRepository;
import com.yizhaoqi.smartpai.repository.PaperParserArtifactRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTableRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.repository.PaperVisualAssetRepository;
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
    void evalImportJsonRowIsRejectedAsParserSmokeEvidence() throws Exception {
        Fixture fixture = fixture();
        Paper paper = pdfPaper("qasper:123", "qasper-paper.json");
        paper.setEval(true);
        paper.setSourceDataset("qasper");
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
        assertTrue(row.path("failures").toString().contains("eval_or_structured_import_not_pdf"));
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
        PaperTableRepository tableRepository = mock(PaperTableRepository.class);
        PaperFigureRepository figureRepository = mock(PaperFigureRepository.class);
        PaperFormulaRepository formulaRepository = mock(PaperFormulaRepository.class);
        ProductPdfParserSmokeRunner runner = new ProductPdfParserSmokeRunner(
                paperRepository,
                chunkRepository,
                parserArtifactRepository,
                visualAssetRepository,
                tableRepository,
                figureRepository,
                formulaRepository
        );
        return new Fixture(
                runner,
                paperRepository,
                chunkRepository,
                parserArtifactRepository,
                visualAssetRepository
        );
    }

    private record Fixture(
            ProductPdfParserSmokeRunner runner,
            PaperRepository paperRepository,
            PaperTextChunkRepository chunkRepository,
            PaperParserArtifactRepository parserArtifactRepository,
            PaperVisualAssetRepository visualAssetRepository
    ) {
    }
}
