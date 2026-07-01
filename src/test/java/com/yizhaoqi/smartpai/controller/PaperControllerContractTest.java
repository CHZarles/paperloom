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
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperControllerContractTest {

    private static final String LEGACY_STRUCTURED_FIELD = "structured" + "Import";
    private static final String LEGACY_EVAL_FIELD = "eval" + "Import";
    private static final List<String> FORBIDDEN_EVAL_FIELDS = List.of(
            LEGACY_EVAL_FIELD,
            LEGACY_STRUCTURED_FIELD,
            "sourceDataset",
            "evalSplit",
            "isEval"
    );

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
        assertFalse(item.containsKey(LEGACY_STRUCTURED_FIELD));
        assertFalse(item.containsKey(LEGACY_EVAL_FIELD));
        assertEquals(List.of(), item.get("assetWarnings"));
    }

    @Test
    void accessiblePaperPaginationBuildsEvidenceStateForCurrentPageOnly() {
        List<Paper> pageRows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Paper paper = new Paper();
            paper.setPaperId("paper-%02d".formatted(i));
            paper.setOriginalFilename("paper-%02d.pdf".formatted(i));
            paper.setPaperTitle("Paper %02d".formatted(i));
            paper.setStatus(Paper.STATUS_COMPLETED);
            paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
            paper.setUserId("1");
            paper.setPublic(true);
            pageRows.add(paper);
        }

        when(paperService.getAccessiblePapersPage(eq("1"), eq("default"), eq(PageRequest.of(0, 10))))
                .thenReturn(new PageImpl<>(pageRows, PageRequest.of(0, 10), 25));

        var response = paperController.getAccessiblePapers("1", "default", 1, 10, null, null);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        List<?> content = (List<?>) data.get("content");

        assertEquals(10, content.size());
        assertEquals(25L, data.get("totalElements"));
        assertEquals(1, data.get("number"));
        assertEquals(10, data.get("size"));
        verify(paperParserArtifactService, times(10)).findLatestParserArtifact(anyString());
        verify(paperService, never()).getAccessiblePapers("1", "default");
    }

    @Test
    void accessiblePapersSupportsPaperLevelQuery() {
        Paper matchingPaper = paper(
                "paper-agent-rag",
                "Adaptive Agent RAG",
                "adaptive-agent-rag.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED,
                Paper.STATUS_COMPLETED
        );
        Paper nonMatchingPaper = paper(
                "paper-vision",
                "Vision Transformer Survey",
                "vision-transformers.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED,
                Paper.STATUS_COMPLETED
        );

        when(paperService.searchAccessiblePaperCandidates(
                eq("1"),
                eq("default"),
                eq("agent"),
                isNull(),
                eq(PageRequest.of(0, 10))
        )).thenReturn(new PageImpl<>(List.of(matchingPaper), PageRequest.of(0, 10), 1));

        var response = paperController.getAccessiblePapers("1", "default", 1, 10, "agent", null);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        List<?> content = (List<?>) data.get("content");
        Map<?, ?> item = (Map<?, ?>) content.get(0);

        assertEquals(1, content.size());
        assertEquals("paper-agent-rag", item.get("paperId"));
        assertFalse(content.stream()
                .map(Map.class::cast)
                .anyMatch(row -> nonMatchingPaper.getPaperId().equals(row.get("paperId"))));
        verify(paperService).searchAccessiblePaperCandidates(
                eq("1"),
                eq("default"),
                eq("agent"),
                isNull(),
                eq(PageRequest.of(0, 10))
        );
    }

    @Test
    void pdfPreviewResponseUsesInlinePreviewStreamUrl() {
        Paper paper = paper(
                "paper-preview",
                "Preview Paper",
                "preview-paper.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED,
                Paper.STATUS_COMPLETED
        );

        when(paperRepository.findFirstByPaperIdAndIsPublicTrueOrderByCreatedAtDesc("paper-preview"))
                .thenReturn(Optional.of(paper));

        var response = paperController.previewPaperByPath("paper-preview", null, null, null);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("pdf", data.get("previewType"));
        assertEquals("/api/v1/papers/paper-preview/preview/pdf", data.get("previewUrl"));
        verify(paperService, never()).generateDownloadUrl("paper-preview");
    }

    @Test
    void pdfPreviewStreamUsesInlineDisposition() {
        Paper paper = paper(
                "paper-preview",
                "Preview Paper",
                "preview-paper.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED,
                Paper.STATUS_COMPLETED
        );
        paper.setTotalSize(8L);

        when(paperRepository.findFirstByPaperIdAndIsPublicTrueOrderByCreatedAtDesc("paper-preview"))
                .thenReturn(Optional.of(paper));
        when(paperService.openMergedPdfStream("paper-preview"))
                .thenReturn(new ByteArrayInputStream("%PDF-1.7".getBytes()));

        var response = paperController.previewPdfByPath("paper-preview", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").startsWith("inline;"));
        assertEquals(8L, response.getHeaders().getContentLength());
        assertTrue(response.getBody() instanceof InputStreamResource);
    }

    @Test
    void accessiblePapersReadinessSearchableHidesUnsearchablePapers() {
        Paper searchablePaper = paper(
                "paper-searchable",
                "Searchable Paper",
                "searchable-paper.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED,
                Paper.STATUS_COMPLETED
        );
        Paper unsearchablePaper = paper(
                "paper-processing",
                "Processing Paper",
                "processing-paper.pdf",
                Paper.VECTORIZATION_STATUS_FAILED,
                Paper.STATUS_UPLOADING
        );

        when(paperService.searchAccessiblePaperCandidates(
                eq("1"),
                eq("default"),
                isNull(),
                eq("searchable"),
                eq(PageRequest.of(0, 10))
        )).thenReturn(new PageImpl<>(List.of(searchablePaper), PageRequest.of(0, 10), 1));

        var response = paperController.getAccessiblePapers("1", "default", 1, 10, null, "searchable");
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        List<?> content = (List<?>) data.get("content");
        Map<?, ?> item = (Map<?, ?>) content.get(0);

        assertEquals(1, content.size());
        assertEquals("paper-searchable", item.get("paperId"));
        assertEquals(1L, data.get("totalElements"));
        assertFalse(content.stream()
                .map(Map.class::cast)
                .anyMatch(row -> unsearchablePaper.getPaperId().equals(row.get("paperId"))));
        verify(paperService).searchAccessiblePaperCandidates(
                eq("1"),
                eq("default"),
                isNull(),
                eq("searchable"),
                eq(PageRequest.of(0, 10))
        );
    }

    @Test
    void accessiblePapersResponseContainsNoEvalFields() {
        Paper paper = paper(
                "paper-product",
                "Product Paper",
                "product-paper.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED,
                Paper.STATUS_COMPLETED
        );

        when(paperService.getAccessiblePapersPage(eq("1"), eq("default"), eq(PageRequest.of(0, 10))))
                .thenReturn(new PageImpl<>(List.of(paper), PageRequest.of(0, 10), 1));

        var response = paperController.getAccessiblePapers("1", "default", 1, 10, null, null);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        List<?> content = (List<?>) data.get("content");
        Map<?, ?> item = (Map<?, ?>) content.get(0);

        for (String forbiddenField : FORBIDDEN_EVAL_FIELDS) {
            assertFalse(item.containsKey(forbiddenField), "response row exposed " + forbiddenField);
        }
    }

    @Test
    void accessiblePapersRejectsUnsupportedReadiness() {
        var response = paperController.getAccessiblePapers("1", "default", 1, 10, null, "indexed");
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(400, response.getStatusCode().value());
        assertEquals(400, body.get("code"));
        verify(paperService, never()).searchAccessiblePaperCandidates(
                eq("1"),
                eq("default"),
                isNull(),
                eq("indexed"),
                eq(PageRequest.of(0, 10))
        );
    }

    @Test
    void metadataDetailUsesPdfReadinessOnly() {
        Paper paper = new Paper();
        paper.setPaperId("fedcba9876543210fedcba9876543210");
        paper.setOriginalFilename("paper.pdf");
        paper.setPaperTitle("PDF Paper");
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        paper.setUserId("2");

        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("fedcba9876543210fedcba9876543210"))
                .thenReturn(Optional.of(paper));
        when(paperRepository.save(paper)).thenReturn(paper);

        var response = paperController.updatePaperMetadata(
                "fedcba9876543210fedcba9876543210",
                Map.of("paperTitle", "PDF Paper"),
                "2",
                "USER"
        );
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");

        assertEquals("PDF", data.get("sourceType"));
        assertEquals("PDF_PENDING_ASSETS", data.get("evidenceAssetLevel"));
        assertEquals(false, data.get("pdfEvidenceAvailable"));
        assertFalse(data.containsKey(LEGACY_STRUCTURED_FIELD));
        assertFalse(data.containsKey(LEGACY_EVAL_FIELD));
        assertTrue(((List<?>) data.get("assetWarnings")).contains("parser_artifact_missing"));
        assertTrue(((List<?>) data.get("assetWarnings")).contains("page_screenshots_missing"));
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

    private Paper paper(
            String paperId,
            String paperTitle,
            String originalFilename,
            String vectorizationStatus,
            int status) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setPaperTitle(paperTitle);
        paper.setOriginalFilename(originalFilename);
        paper.setVectorizationStatus(vectorizationStatus);
        paper.setStatus(status);
        paper.setUserId("1");
        paper.setPublic(true);
        return paper;
    }
}
