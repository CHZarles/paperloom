package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
