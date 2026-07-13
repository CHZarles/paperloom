package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperParserArtifact;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.paper.parser.BoundingBox;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperArtifactPayload;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElement;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElementType;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperFigure;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperMetadata;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import io.minio.MinioClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperVisualAssetServiceTest {

    @Test
    void rendersPageAndCropsPdfBottomLeftBoundingBoxToImageCoordinates() throws Exception {
        PaperVisualAssetService service = new PaperVisualAssetService();
        ReflectionTestUtils.setField(service, "pageDpi", 72);

        byte[] pdfBytes = simplePdf();
        PaperVisualAssetService.ImageBytes pageImage = service.renderPagePng(pdfBytes, 1);
        assertEquals(612, pageImage.widthPx());
        assertEquals(792, pageImage.heightPx());

        BoundingBox tableBox = new BoundingBox(1, 72.0, 620.0, 360.0, 700.0, "pdf_points", "bottom_left");
        PaperVisualAssetService.ImageBytes crop = service.cropPdfBoundingBox(pageImage.bytes(), tableBox);

        assertEquals(288, crop.widthPx());
        assertEquals(80, crop.heightPx());
        assertTrue(crop.bytes().length > 0);
    }

    @Test
    void persistsMineruParserImageWithReadingElementBackReference() throws Exception {
        PaperVisualAssetService service = new PaperVisualAssetService();
        MinioClient minioClient = mock(MinioClient.class);
        PaperVisualAssetRepository visualAssetRepository = mock(PaperVisualAssetRepository.class);
        PaperReadingElementRepository readingElementRepository = mock(PaperReadingElementRepository.class);
        ReflectionTestUtils.setField(service, "minioClient", minioClient);
        ReflectionTestUtils.setField(service, "paperVisualAssetRepository", visualAssetRepository);
        ReflectionTestUtils.setField(service, "paperReadingElementRepository", readingElementRepository);

        List<PaperVisualAsset> saved = new ArrayList<>();
        when(visualAssetRepository.save(any(PaperVisualAsset.class))).thenAnswer(invocation -> {
            PaperVisualAsset asset = invocation.getArgument(0);
            saved.add(asset);
            return asset;
        });
        when(visualAssetRepository.findByPaperId("paper-a")).thenAnswer(invocation -> saved);

        PaperReadingElement readingElement = new PaperReadingElement();
        readingElement.setPaperId("paper-a");
        readingElement.setModelVersion("rm-1");
        readingElement.setReadingElementId("reading-element-chart");
        readingElement.setParserElementId("chart-el");
        readingElement.setSourceObjectId("figure-chart");
        readingElement.setElementType("CHART");
        readingElement.setPageNumber(7);
        readingElement.setReadingOrder(3);
        readingElement.setParserImagePath("images/chart-1.png");
        readingElement.setBboxJson("{\"pageNumber\":7}");
        when(readingElementRepository.findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc("paper-a", "rm-1"))
                .thenReturn(List.of(readingElement));

        ParsedPaper parsedPaper = parsedPaperWithMineruImage(mineruZip("2412.08972/auto/images/chart-1.png", pngBytes()));

        service.replaceVisualAssets(
                "paper-a",
                "rm-1",
                null,
                parsedPaper,
                "user-a",
                "lab",
                false
        );

        assertEquals(1, saved.size());
        PaperVisualAsset asset = saved.get(0);
        assertEquals(PaperVisualAsset.TYPE_PARSER_IMAGE, asset.getAssetType());
        assertEquals(PaperVisualAsset.STATUS_AVAILABLE, asset.getAssetStatus());
        assertEquals("paper-a", asset.getPaperId());
        assertEquals("rm-1", asset.getModelVersion());
        assertEquals(7, asset.getPageNumber());
        assertEquals("figure-chart", asset.getSourceObjectId());
        assertEquals("chart-el", asset.getParserElementId());
        assertEquals("reading-element-chart", asset.getReadingElementId());
        assertEquals("images/chart-1.png", asset.getParserImagePath());
        assertEquals("image/png", asset.getContentType());
        assertNotNull(asset.getBboxJson());
        assertTrue(asset.getObjectKey().contains("parser-images"));
        assertTrue(asset.getWidthPx() > 0);
        assertTrue(asset.getHeightPx() > 0);
    }

    @Test
    void persistsMissingParserImageGapWithReadingElementBackReference() {
        PaperVisualAssetService service = new PaperVisualAssetService();
        PaperVisualAssetRepository visualAssetRepository = mock(PaperVisualAssetRepository.class);
        PaperReadingElementRepository readingElementRepository = mock(PaperReadingElementRepository.class);
        ReflectionTestUtils.setField(service, "paperVisualAssetRepository", visualAssetRepository);
        ReflectionTestUtils.setField(service, "paperReadingElementRepository", readingElementRepository);

        List<PaperVisualAsset> saved = new ArrayList<>();
        when(visualAssetRepository.save(any(PaperVisualAsset.class))).thenAnswer(invocation -> {
            PaperVisualAsset asset = invocation.getArgument(0);
            saved.add(asset);
            return asset;
        });
        when(visualAssetRepository.findByPaperId("paper-a")).thenAnswer(invocation -> saved);

        PaperReadingElement readingElement = new PaperReadingElement();
        readingElement.setPaperId("paper-a");
        readingElement.setModelVersion("rm-1");
        readingElement.setReadingElementId("reading-element-chart");
        readingElement.setParserElementId("chart-el");
        readingElement.setSourceObjectId("figure-chart");
        readingElement.setElementType("CHART");
        readingElement.setPageNumber(7);
        readingElement.setReadingOrder(3);
        readingElement.setParserImagePath("images/chart-1.png");
        readingElement.setBboxJson("{\"pageNumber\":7}");
        when(readingElementRepository.findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc("paper-a", "rm-1"))
                .thenReturn(List.of(readingElement));

        ParsedPaper parsedPaper = parsedPaperWithMineruImage(mineruZipWithoutImage());

        service.replaceVisualAssets(
                "paper-a",
                "rm-1",
                null,
                parsedPaper,
                "user-a",
                "lab",
                false
        );

        assertEquals(1, saved.size());
        PaperVisualAsset asset = saved.get(0);
        assertEquals(PaperVisualAsset.TYPE_PARSER_IMAGE, asset.getAssetType());
        assertEquals(PaperVisualAsset.STATUS_MISSING_IN_ARTIFACT, asset.getAssetStatus());
        assertEquals("reading-element-chart", asset.getReadingElementId());
        assertEquals("images/chart-1.png", asset.getParserImagePath());
        assertEquals("figure-chart", asset.getSourceObjectId());
        assertEquals(7, asset.getPageNumber());
        assertEquals(null, asset.getObjectKey());
        assertNotNull(asset.getFailureReason());
    }

    @Test
    void persistsPageAndReadingElementGapsWhenObjectStorageFails() throws Exception {
        PaperVisualAssetService service = new PaperVisualAssetService();
        MinioClient minioClient = mock(MinioClient.class);
        PaperVisualAssetRepository visualAssetRepository = mock(PaperVisualAssetRepository.class);
        PaperReadingElementRepository readingElementRepository = mock(PaperReadingElementRepository.class);
        ReflectionTestUtils.setField(service, "pageDpi", 72);
        ReflectionTestUtils.setField(service, "minioClient", minioClient);
        ReflectionTestUtils.setField(service, "paperVisualAssetRepository", visualAssetRepository);
        ReflectionTestUtils.setField(service, "paperReadingElementRepository", readingElementRepository);
        when(minioClient.putObject(any())).thenThrow(new RuntimeException("storage down"));

        List<PaperVisualAsset> saved = new ArrayList<>();
        when(visualAssetRepository.save(any(PaperVisualAsset.class))).thenAnswer(invocation -> {
            PaperVisualAsset asset = invocation.getArgument(0);
            saved.add(asset);
            return asset;
        });
        when(visualAssetRepository.findByPaperId("paper-a")).thenAnswer(invocation -> saved);

        PaperReadingElement table = new PaperReadingElement();
        table.setPaperId("paper-a");
        table.setModelVersion("rm-1");
        table.setReadingElementId("reading-element-table");
        table.setParserElementId("table-el");
        table.setSourceObjectId("table-1");
        table.setElementType("TABLE");
        table.setPageNumber(1);
        table.setReadingOrder(2);
        table.setBboxJson("{\"pageNumber\":1,\"left\":72.0,\"bottom\":620.0,\"right\":360.0,\"top\":700.0,\"unit\":\"pdf_points\",\"coordinateSystem\":\"bottom_left\"}");
        when(readingElementRepository.findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc("paper-a", "rm-1"))
                .thenReturn(List.of(table));

        service.replaceVisualAssets(
                "paper-a",
                "rm-1",
                simplePdf(),
                parsedPaperWithoutParserImages(),
                "user-a",
                "lab",
                false
        );

        PaperVisualAsset pageGap = saved.stream()
                .filter(asset -> PaperVisualAsset.TYPE_PAGE_SCREENSHOT.equals(asset.getAssetType()))
                .findFirst()
                .orElseThrow();
        assertEquals(PaperVisualAsset.STATUS_STORAGE_FAILED, pageGap.getAssetStatus());
        assertEquals(1, pageGap.getPageNumber());
        assertEquals(null, pageGap.getObjectKey());

        PaperVisualAsset cropGap = saved.stream()
                .filter(asset -> PaperVisualAsset.TYPE_TABLE_CROP.equals(asset.getAssetType()))
                .findFirst()
                .orElseThrow();
        assertEquals(PaperVisualAsset.STATUS_RENDER_FAILED, cropGap.getAssetStatus());
        assertEquals("reading-element-table", cropGap.getReadingElementId());
        assertEquals(null, cropGap.getObjectKey());
    }

    private byte[] simplePdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA_BOLD, 16);
                content.newLineAtOffset(72, 690);
                content.showText("Table 1");
                content.endText();

                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 11);
                content.newLineAtOffset(72, 660);
                content.showText("Metric Value");
                content.endText();
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    private ParsedPaper parsedPaperWithMineruImage(byte[] resultZip) {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 7, null, null),
                List.of(
                        new ParsedPaperElement(
                                "p1",
                                1,
                                1,
                                ParsedPaperElementType.PARAGRAPH,
                                "Readable page text.",
                                null,
                                null,
                                null,
                                Map.of()
                        )
                ),
                Map.of(),
                "{}",
                List.of(),
                List.of(new ParsedPaperFigure(
                        "figure-chart",
                        "chart-el",
                        7,
                        3,
                        null,
                        null,
                        null,
                        new BoundingBox(7, 526.0, 178.0, 862.0, 85.0, "mineru_1000", "top_left_1000"),
                        "MINERU_CHART",
                        "HIGH",
                        Map.of("type", "chart", "img_path", "images/chart-1.png")
                )),
                List.of(),
                List.of(new ParsedPaperArtifactPayload(
                        PaperParserArtifact.TYPE_MINERU_RESULT_ZIP,
                        "raw-result.zip",
                        "application/zip",
                        resultZip
                ))
        );
    }

    private ParsedPaper parsedPaperWithoutParserImages() {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(
                        new ParsedPaperElement(
                                "p1",
                                1,
                                1,
                                ParsedPaperElementType.PARAGRAPH,
                                "Readable page text.",
                                null,
                                null,
                                null,
                                Map.of()
                        )
                ),
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private byte[] mineruZip(String imageEntryName, byte[] imageBytes) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("2412.08972/auto/2412.08972_content_list.json"));
            zip.write("[]".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(imageEntryName));
            zip.write(imageBytes);
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        }
    }

    private byte[] mineruZipWithoutImage() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("2412.08972/auto/2412.08972_content_list.json"));
            zip.write("[]".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }
}
