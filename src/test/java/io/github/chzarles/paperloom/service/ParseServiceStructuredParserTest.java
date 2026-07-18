package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperTextChunk;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.paper.parser.BoundingBox;
import io.github.chzarles.paperloom.paper.parser.PaperChunkBuilder;
import io.github.chzarles.paperloom.paper.parser.PaperPdfParser;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElement;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElementType;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperFigure;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperFormula;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperMetadata;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperTable;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParseServiceStructuredParserTest {

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperPdfParser paperPdfParser;

    @Mock
    private PaperParserArtifactService paperParserArtifactService;

    @Mock
    private PaperVisualAssetService paperVisualAssetService;

    @Mock
    private PaperReadingModelService paperReadingModelService;

    @Test
    void parseAndSavePersistsStructuredPaperChunkProvenance() throws Exception {
        ParseService parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "paperTextChunkRepository", paperTextChunkRepository);
        ReflectionTestUtils.setField(parseService, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(parseService, "paperPdfParser", paperPdfParser);
        ReflectionTestUtils.setField(parseService, "paperChunkBuilder", new PaperChunkBuilder());
        ReflectionTestUtils.setField(parseService, "paperParserArtifactService", paperParserArtifactService);
        ReflectionTestUtils.setField(parseService, "paperVisualAssetService", paperVisualAssetService);
        ReflectionTestUtils.setField(parseService, "paperReadingModelService", paperReadingModelService);
        ReflectionTestUtils.setField(parseService, "chunkSize", 512);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);

        Paper paper = new Paper();
        paper.setPaperId("paper123");
        paper.setOriginalFilename("uploaded.pdf");
        paper.setPaperTitle("uploaded.pdf");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper123")).thenReturn(Optional.of(paper));
        ParsedPaper parsedPaper = parsedPaper();
        when(paperPdfParser.parse(any(), eq("uploaded.pdf"))).thenReturn(parsedPaper);
        PaperReadingModel readyModel = new PaperReadingModel();
        readyModel.setPaperId("paper123");
        readyModel.setModelVersion("rm_test_1");
        readyModel.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        readyModel.setCurrent(true);
        when(paperReadingModelService.replaceFromParsedPaper("paper123", parsedPaper, "7", "lab", true))
                .thenReturn(readyModel);
        when(paperVisualAssetService.replaceVisualAssets(eq("paper123"), eq("rm_test_1"), any(), eq(parsedPaper), eq("7"), eq("lab"), eq(true)))
                .thenReturn(List.of());

        parseService.parseAndSave(
                "paper123",
                new ByteArrayInputStream("%PDF-test".getBytes(StandardCharsets.UTF_8)),
                "uploaded.pdf",
                "7",
                "lab",
                true
        );

        ArgumentCaptor<PaperTextChunk> captor = ArgumentCaptor.forClass(PaperTextChunk.class);
        verify(paperTextChunkRepository, times(2)).save(captor.capture());
        PaperTextChunk chunk = captor.getAllValues().stream()
                .filter(savedChunk -> "TEXT".equals(savedChunk.getSourceKind()))
                .findFirst()
                .orElseThrow();
        PaperTextChunk tableChunk = captor.getAllValues().stream()
                .filter(savedChunk -> "TABLE".equals(savedChunk.getSourceKind()))
                .findFirst()
                .orElseThrow();

        assertEquals("paper123", chunk.getPaperId());
        assertEquals(1, chunk.getChunkId());
        assertEquals("The model grounds answers in retrieved paper evidence.", chunk.getTextContent());
        assertEquals(3, chunk.getPageNumber());
        assertEquals("Methods", chunk.getSectionTitle());
        assertEquals(ParsedPaperElementType.PARAGRAPH.name(), chunk.getElementType());
        assertEquals("opendataloader-pdf", chunk.getParserName());
        assertEquals("2.4.7", chunk.getParserVersion());
        assertEquals("TEXT", chunk.getSourceKind());
        assertTrue(chunk.getBboxJson().contains("\"pageNumber\":3"));
        assertTrue(chunk.getRawProvenanceJson().contains("\"elementId\":\"p1\""));
        assertEquals("7", chunk.getUserId());
        assertEquals("lab", chunk.getOrgTag());
        assertTrue(chunk.isPublic());
        assertEquals("table-t1", tableChunk.getTableId());
        assertTrue(tableChunk.getTextContent().contains("PaperLoom: 91.2"));
        assertEquals("Evidence Paper", paper.getPaperTitle());
        assertEquals("Ada", paper.getAuthors());
        assertEquals(Paper.VECTORIZATION_STATUS_CHUNKING, paper.getVectorizationStatus());
        verify(paperRepository, atLeastOnce()).save(paper);
        verify(paperParserArtifactService).saveParserArtifact("paper123", parsedPaper, "7", "lab", true);
        verify(paperVisualAssetService).replaceVisualAssets(eq("paper123"), eq("rm_test_1"), any(), eq(parsedPaper), eq("7"), eq("lab"), eq(true));

        InOrder order = inOrder(paperParserArtifactService, paperReadingModelService, paperVisualAssetService);
        order.verify(paperParserArtifactService).saveParserArtifact("paper123", parsedPaper, "7", "lab", true);
        order.verify(paperReadingModelService).replaceFromParsedPaper("paper123", parsedPaper, "7", "lab", true);
        order.verify(paperVisualAssetService).replaceVisualAssets(eq("paper123"), eq("rm_test_1"), any(), eq(parsedPaper), eq("7"), eq("lab"), eq(true));
    }

    @Test
    void parseAndSaveStopsWhenReadingModelIsNotReady() throws Exception {
        ParseService parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "paperTextChunkRepository", paperTextChunkRepository);
        ReflectionTestUtils.setField(parseService, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(parseService, "paperPdfParser", paperPdfParser);
        ReflectionTestUtils.setField(parseService, "paperChunkBuilder", new PaperChunkBuilder());
        ReflectionTestUtils.setField(parseService, "paperParserArtifactService", paperParserArtifactService);
        ReflectionTestUtils.setField(parseService, "paperVisualAssetService", paperVisualAssetService);
        ReflectionTestUtils.setField(parseService, "paperReadingModelService", paperReadingModelService);
        ReflectionTestUtils.setField(parseService, "chunkSize", 512);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);

        Paper paper = new Paper();
        paper.setPaperId("paper123");
        paper.setOriginalFilename("uploaded.pdf");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper123")).thenReturn(Optional.of(paper));
        ParsedPaper parsedPaper = parsedPaper();
        when(paperPdfParser.parse(any(), eq("uploaded.pdf"))).thenReturn(parsedPaper);
        PaperReadingModel failedModel = new PaperReadingModel();
        failedModel.setPaperId("paper123");
        failedModel.setModelVersion("rm_failed");
        failedModel.setModelStatus(PaperReadingModelStatus.READING_MODEL_FAILED);
        failedModel.setFailureReason("NO_READABLE_NUMBERED_TEXT");
        when(paperReadingModelService.replaceFromParsedPaper("paper123", parsedPaper, "7", "lab", true))
                .thenReturn(failedModel);

        assertThrows(
                PaperReadingModelNotReadyException.class,
                () -> parseService.parseAndSave(
                        "paper123",
                        new ByteArrayInputStream("%PDF-test".getBytes(StandardCharsets.UTF_8)),
                        "uploaded.pdf",
                        "7",
                        "lab",
                        true
                )
        );

        verify(paperTextChunkRepository, never()).save(any());
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
                        ),
                        new ParsedPaperElement(
                                "t1",
                                3,
                                3,
                                ParsedPaperElementType.TABLE,
                                "Metric: Accuracy\nPaperLoom: 91.2",
                                null,
                                null,
                                new BoundingBox(3, 72.0, 420.0, 520.0, 560.0, "pdf_points", "bottom_left"),
                                Map.of("number of rows", 2, "number of columns", 2)
                        )
                ),
                Map.of(),
                "{\"kids\":[]}",
                List.of(new ParsedPaperTable(
                        "table-t1",
                        "t1",
                        3,
                        3,
                        null,
                        "Methods",
                        2,
                        2,
                        "Metric: Accuracy\nPaperLoom: 91.2",
                        "| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |",
                        new BoundingBox(3, 72.0, 420.0, 520.0, 560.0, "pdf_points", "bottom_left"),
                        Map.of("number of rows", 2)
                )),
                List.of(new ParsedPaperFigure(
                        "figure-f1",
                        "f1",
                        3,
                        4,
                        "Figure 1: Evidence flow.",
                        "Methods",
                        "Figure 1: Evidence flow.",
                        new BoundingBox(3, 72.0, 300.0, 520.0, 390.0, "pdf_points", "bottom_left"),
                        "MINERU_FIGURE",
                        "HIGH",
                        Map.of()
                )),
                List.of(new ParsedPaperFormula(
                        "formula-x",
                        "x",
                        3,
                        5,
                        "score = bm25 + cosine",
                        null,
                        "Methods",
                        new BoundingBox(3, 72.0, 250.0, 520.0, 290.0, "pdf_points", "bottom_left"),
                        Map.of()
                ))
        );
    }

}
