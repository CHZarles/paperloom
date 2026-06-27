package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperParserArtifact;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.service.PaperFigureService;
import com.yizhaoqi.smartpai.service.PaperFormulaService;
import com.yizhaoqi.smartpai.service.PaperParserArtifactService;
import com.yizhaoqi.smartpai.service.PaperService;
import com.yizhaoqi.smartpai.service.PaperTableService;
import com.yizhaoqi.smartpai.service.PaperVisualAssetService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperControllerContractTest {

    @Mock
    private PaperService paperService;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private OrganizationTagRepository organizationTagRepository;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private ChatHandler chatHandler;

    @Mock
    private ConversationService conversationService;

    @Mock
    private PaperParserArtifactService paperParserArtifactService;

    @Mock
    private PaperTableService paperTableService;

    @Mock
    private PaperFigureService paperFigureService;

    @Mock
    private PaperFormulaService paperFormulaService;

    @Mock
    private PaperVisualAssetService paperVisualAssetService;

    private PaperController paperController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        paperController = new PaperController();
        ReflectionTestUtils.setField(paperController, "paperService", paperService);
        ReflectionTestUtils.setField(paperController, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(paperController, "organizationTagRepository", organizationTagRepository);
        ReflectionTestUtils.setField(paperController, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(paperController, "chatHandler", chatHandler);
        ReflectionTestUtils.setField(paperController, "conversationService", conversationService);
        ReflectionTestUtils.setField(paperController, "paperParserArtifactService", paperParserArtifactService);
        ReflectionTestUtils.setField(paperController, "paperTableService", paperTableService);
        ReflectionTestUtils.setField(paperController, "paperFigureService", paperFigureService);
        ReflectionTestUtils.setField(paperController, "paperFormulaService", paperFormulaService);
        ReflectionTestUtils.setField(paperController, "paperVisualAssetService", paperVisualAssetService);

        when(paperParserArtifactService.findLatestParserArtifact(anyString())).thenReturn(Optional.empty());
        when(paperTableService.countByPaperId(anyString())).thenReturn(0L);
        when(paperFigureService.countByPaperId(anyString())).thenReturn(0L);
        when(paperFormulaService.countByPaperId(anyString())).thenReturn(0L);
        when(paperVisualAssetService.countPageScreenshots(anyString())).thenReturn(0L);
        when(paperVisualAssetService.countTableCrops(anyString())).thenReturn(0L);
        when(paperVisualAssetService.countFigureCrops(anyString())).thenReturn(0L);
    }

    @Test
    void uploadedPaperListUsesPaperContractNames() {
        Paper paper = new Paper();
        paper.setPaperId("0123456789abcdef0123456789abcdef");
        paper.setOriginalFilename("original.pdf");
        paper.setPaperTitle("Paper Title");
        paper.setTotalSize(4096L);
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        paper.setUserId("2");
        paper.setOrgTag("lab");
        paper.setPublic(true);

        PaperParserArtifact artifact = new PaperParserArtifact();
        artifact.setPaperId("0123456789abcdef0123456789abcdef");
        artifact.setParserName("MinerU");
        artifact.setParserVersion("1.0");

        when(paperService.getUserUploadedPapers("2")).thenReturn(List.of(paper));
        when(organizationTagRepository.findByTagId("lab")).thenReturn(Optional.empty());
        when(paperParserArtifactService.findLatestParserArtifact("0123456789abcdef0123456789abcdef"))
                .thenReturn(Optional.of(artifact));
        when(paperVisualAssetService.countPageScreenshots("0123456789abcdef0123456789abcdef")).thenReturn(1L);

        var response = paperController.getUserUploadedPapers("2");
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        List<?> data = (List<?>) body.get("data");
        Map<?, ?> item = (Map<?, ?>) data.get(0);

        assertEquals("0123456789abcdef0123456789abcdef", item.get("paperId"));
        assertEquals("Paper Title", item.get("paperTitle"));
        assertEquals("original.pdf", item.get("originalFilename"));
        assertEquals("COMPLETED", item.get("processingStatus"));
        assertTrue((Boolean) item.get("isPublic"));
        assertFalse(item.containsKey("fileMd5"));
        assertFalse(item.containsKey("fileName"));
        assertFalse(item.containsKey("sourceFileName"));
        assertFalse(item.containsKey("public"));
        assertFalse(item.containsKey("vectorizationStatus"));
        assertEquals("PDF", item.get("sourceType"));
        assertEquals("PDF_VISUAL", item.get("evidenceAssetLevel"));
        assertEquals(true, item.get("pdfEvidenceAvailable"));
        assertEquals(false, item.get("structuredImport"));
        assertEquals(false, item.get("evalImport"));
        assertEquals(List.of(), item.get("assetWarnings"));
    }

    @Test
    void metadataDetailMarksEvalImportsAsTextOnly() {
        Paper paper = new Paper();
        paper.setPaperId("fedcba9876543210fedcba9876543210");
        paper.setOriginalFilename("litsearch:123.json");
        paper.setPaperTitle("Eval Paper");
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        paper.setUserId("2");
        paper.setSourceDataset("litsearch");
        paper.setEvalSplit("full");
        paper.setEval(true);

        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("fedcba9876543210fedcba9876543210"))
                .thenReturn(Optional.of(paper));
        when(paperRepository.save(paper)).thenReturn(paper);

        var response = paperController.updatePaperMetadata(
                "fedcba9876543210fedcba9876543210",
                Map.of("paperTitle", "Eval Paper"),
                "2",
                "USER"
        );
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");

        assertEquals("EVAL_IMPORT", data.get("sourceType"));
        assertEquals("TEXT_ONLY", data.get("evidenceAssetLevel"));
        assertEquals(false, data.get("pdfEvidenceAvailable"));
        assertEquals(true, data.get("structuredImport"));
        assertEquals(true, data.get("evalImport"));
        assertTrue(((List<?>) data.get("assetWarnings")).contains("structured_import_text_only"));
    }

    @Test
    void adminCanDeletePaperOwnedByAnotherUser() {
        Paper paper = new Paper();
        paper.setPaperId("0123456789abcdef0123456789abcdef");
        paper.setOriginalFilename("other-user-paper.pdf");
        paper.setUserId("2");

        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("0123456789abcdef0123456789abcdef"))
                .thenReturn(Optional.of(paper));

        var response = paperController.deletePaper("0123456789abcdef0123456789abcdef", "1", "ADMIN");

        assertEquals(200, response.getStatusCode().value());
        verify(paperService).deletePaper("0123456789abcdef0123456789abcdef", "1", "ADMIN");
    }

    @Test
    void nonAdminCannotDeletePaperOwnedByAnotherUser() {
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("0123456789abcdef0123456789abcdef", "1"))
                .thenReturn(Optional.empty());

        var response = paperController.deletePaper("0123456789abcdef0123456789abcdef", "1", "USER");

        assertEquals(404, response.getStatusCode().value());
        verify(paperService, never()).deletePaper("0123456789abcdef0123456789abcdef", "1", "USER");
    }
}
