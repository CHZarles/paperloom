package com.yizhaoqi.smartpai.paper.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenDataLoaderPaperPdfParserTest {

    @Test
    void parsesDigitalPdfThroughOpenDataLoaderAndKeepsOriginalFilename() throws Exception {
        byte[] pdfBytes = simplePdf("Methods", "Transformer evidence is preserved with page provenance.");
        OpenDataLoaderPaperPdfParser parser = new OpenDataLoaderPaperPdfParser(new OpenDataLoaderJsonMapper());

        ParsedPaper paper = parser.parse(new ByteArrayInputStream(pdfBytes), "sample-paper.pdf");

        assertEquals("opendataloader-pdf", paper.parserName());
        assertEquals("sample-paper.pdf", paper.metadata().originalFilename());
        assertEquals(1, paper.metadata().pageCount());
        assertFalse(paper.elements().isEmpty());
        assertTrue(paper.elements().stream().anyMatch(element ->
                element.pageNumber() != null && element.pageNumber() == 1
                        && element.text() != null
                        && element.text().contains("Transformer evidence")
        ));
    }

    private byte[] simplePdf(String title, String body) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA_BOLD, 16);
                content.newLineAtOffset(72, 720);
                content.showText(title);
                content.endText();

                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 11);
                content.newLineAtOffset(72, 680);
                content.showText(body);
                content.endText();
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }
}
