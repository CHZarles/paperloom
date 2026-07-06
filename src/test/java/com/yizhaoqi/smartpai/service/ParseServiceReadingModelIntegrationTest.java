package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParseServiceReadingModelIntegrationTest {

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private UsageQuotaService usageQuotaService;

    @Mock
    private PaperPdfParser paperPdfParser;

    @Mock
    private PaperParserArtifactService paperParserArtifactService;

    @Mock
    private PaperVisualAssetService paperVisualAssetService;

    @Mock
    private PaperReadingModelService paperReadingModelService;

    @Test
    void parseAndSaveBuildsReadingModelAfterParserArtifactAndBeforeDownstreamWork() throws Exception {
        ParseService parseService = parseService();
        Paper paper = new Paper();
        paper.setPaperId("paper123");
        paper.setOriginalFilename("uploaded.pdf");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper123")).thenReturn(Optional.of(paper));
        ParsedPaper parsedPaper = parsedPaper();
        when(paperPdfParser.parse(any(), eq("uploaded.pdf"))).thenReturn(parsedPaper);
        when(paperReadingModelService.replaceFromParsedPaper("paper123", parsedPaper, "7", "lab", true))
                .thenReturn(readyModel());
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

        InOrder order = inOrder(paperParserArtifactService, paperReadingModelService, paperVisualAssetService);
        order.verify(paperParserArtifactService).saveParserArtifact("paper123", parsedPaper, "7", "lab", true);
        order.verify(paperReadingModelService).replaceFromParsedPaper("paper123", parsedPaper, "7", "lab", true);
        order.verify(paperVisualAssetService).replaceVisualAssets(eq("paper123"), eq("rm_test_1"), any(), eq(parsedPaper), eq("7"), eq("lab"), eq(true));
    }

    private ParseService parseService() {
        ParseService parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "paperTextChunkRepository", paperTextChunkRepository);
        ReflectionTestUtils.setField(parseService, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(parseService, "usageQuotaService", usageQuotaService);
        ReflectionTestUtils.setField(parseService, "paperPdfParser", paperPdfParser);
        ReflectionTestUtils.setField(parseService, "paperChunkBuilder", new PaperChunkBuilder());
        ReflectionTestUtils.setField(parseService, "paperParserArtifactService", paperParserArtifactService);
        ReflectionTestUtils.setField(parseService, "paperVisualAssetService", paperVisualAssetService);
        ReflectionTestUtils.setField(parseService, "paperReadingModelService", paperReadingModelService);
        ReflectionTestUtils.setField(parseService, "chunkSize", 512);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);
        return parseService;
    }

    private PaperReadingModel readyModel() {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper123");
        model.setModelVersion("rm_test_1");
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        return model;
    }

    private ParsedPaper parsedPaper() {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(new ParsedPaperElement(
                        "p1",
                        1,
                        1,
                        ParsedPaperElementType.PARAGRAPH,
                        "Readable text.",
                        null,
                        null,
                        null,
                        Map.of()
                )),
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of()
        );
    }
}
