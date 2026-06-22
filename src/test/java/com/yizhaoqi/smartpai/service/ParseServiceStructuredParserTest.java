package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperTextChunk;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.PaperChunkBuilder;
import com.yizhaoqi.smartpai.paper.parser.PaperPdfParser;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperMetadata;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParseServiceStructuredParserTest {

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private UsageQuotaService usageQuotaService;

    @Mock
    private PaperPdfParser paperPdfParser;

    @Test
    void parseAndSavePersistsStructuredPaperChunkProvenance() throws Exception {
        ParseService parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "paperTextChunkRepository", paperTextChunkRepository);
        ReflectionTestUtils.setField(parseService, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(parseService, "usageQuotaService", usageQuotaService);
        ReflectionTestUtils.setField(parseService, "paperPdfParser", paperPdfParser);
        ReflectionTestUtils.setField(parseService, "paperChunkBuilder", new PaperChunkBuilder());
        ReflectionTestUtils.setField(parseService, "chunkSize", 512);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);

        Paper paper = new Paper();
        paper.setPaperId("paper123");
        paper.setOriginalFilename("uploaded.pdf");
        paper.setPaperTitle("uploaded.pdf");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper123")).thenReturn(Optional.of(paper));
        when(paperPdfParser.parse(any(), eq("uploaded.pdf"))).thenReturn(parsedPaper());

        parseService.parseAndSave(
                "paper123",
                new ByteArrayInputStream("%PDF-test".getBytes(StandardCharsets.UTF_8)),
                "uploaded.pdf",
                "7",
                "lab",
                true
        );

        ArgumentCaptor<PaperTextChunk> captor = ArgumentCaptor.forClass(PaperTextChunk.class);
        verify(paperTextChunkRepository).save(captor.capture());
        PaperTextChunk chunk = captor.getValue();

        assertEquals("paper123", chunk.getPaperId());
        assertEquals(1, chunk.getChunkId());
        assertEquals("The model grounds answers in retrieved paper evidence.", chunk.getTextContent());
        assertEquals(3, chunk.getPageNumber());
        assertEquals("Methods", chunk.getSectionTitle());
        assertEquals(ParsedPaperElementType.PARAGRAPH.name(), chunk.getElementType());
        assertEquals("opendataloader-pdf", chunk.getParserName());
        assertEquals("2.4.7", chunk.getParserVersion());
        assertTrue(chunk.getBboxJson().contains("\"pageNumber\":3"));
        assertTrue(chunk.getRawProvenanceJson().contains("\"elementId\":\"p1\""));
        assertEquals("7", chunk.getUserId());
        assertEquals("lab", chunk.getOrgTag());
        assertTrue(chunk.isPublic());
        assertEquals("Evidence Paper", paper.getPaperTitle());
        assertEquals("Ada", paper.getAuthors());
        verify(paperRepository).save(paper);
    }

    private ParsedPaper parsedPaper() {
        return new ParsedPaper(
                "opendataloader-pdf",
                "2.4.7",
                new ParsedPaperMetadata("paper.pdf", "Evidence Paper", "Ada", 3, null, null),
                List.of(
                        new ParsedPaperElement(
                                "h1",
                                3,
                                1,
                                ParsedPaperElementType.HEADING,
                                "Methods",
                                null,
                                1,
                                new BoundingBox(3, 72.0, 700.0, 520.0, 735.0, "pdf_points", "bottom_left"),
                                Map.of()
                        ),
                        new ParsedPaperElement(
                                "p1",
                                3,
                                2,
                                ParsedPaperElementType.PARAGRAPH,
                                "The model grounds answers in retrieved paper evidence.",
                                null,
                                null,
                                new BoundingBox(3, 72.0, 620.0, 520.0, 690.0, "pdf_points", "bottom_left"),
                                Map.of("id", 2)
                        )
                ),
                Map.of()
        );
    }
}
