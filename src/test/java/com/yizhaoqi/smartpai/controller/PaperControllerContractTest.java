package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperParserArtifact;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.service.PaperParserArtifactService;
import com.yizhaoqi.smartpai.service.PaperRecommendationCandidate;
import com.yizhaoqi.smartpai.service.PaperRecommendationCandidateService;
import com.yizhaoqi.smartpai.service.PaperRecommendationSearchRequest;
import com.yizhaoqi.smartpai.service.PaperService;
import com.yizhaoqi.smartpai.service.PaperVisualAssetService;
import com.yizhaoqi.smartpai.service.ReadingLocationCandidate;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private PaperReadingModelRepository paperReadingModelRepository;

    @Mock
    private PaperReadingElementRepository paperReadingElementRepository;

    @Mock
    private PaperVisualAssetService paperVisualAssetService;

    @Mock
    private PaperRecommendationCandidateService paperRecommendationCandidateService;

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
        ReflectionTestUtils.setField(paperController, "paperReadingModelRepository", paperReadingModelRepository);
        ReflectionTestUtils.setField(paperController, "paperReadingElementRepository", paperReadingElementRepository);
        ReflectionTestUtils.setField(paperController, "paperVisualAssetService", paperVisualAssetService);
        ReflectionTestUtils.setField(paperController, "paperRecommendationCandidateService", paperRecommendationCandidateService);

        when(paperParserArtifactService.findLatestParserArtifact(anyString())).thenReturn(Optional.empty());
        when(paperReadingModelRepository.findFirstByPaperIdAndIsCurrentTrue(anyString())).thenReturn(Optional.empty());
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
    void recommendationCandidateEndpointReturnsPaperGroupedCandidatesAndClampsLimits() {
        ReadingLocationCandidate location = new ReadingLocationCandidate(
                "paper-agent",
                "v1",
                "section-ref-1",
                PaperLocationType.SECTION,
                2,
                2,
                "Evaluation",
                null,
                "Agentic eval appears here.",
                "SECTION",
                "SECTION_LOCATION",
                List.of("sectionText"),
                List.of()
        );
        PaperRecommendationCandidate candidate = new PaperRecommendationCandidate(
                "paper-agent",
                "Agentic Eval Benchmark",
                "Ada Lovelace",
                2025,
                "NeurIPS",
                "Preview",
                "title matched all query tokens",
                "SUPPORTED",
                List.of(location)
        );
        when(paperRecommendationCandidateService.search(any(PaperRecommendationSearchRequest.class)))
                .thenReturn(List.of(candidate));

        var response = paperController.recommendationCandidates(
                new PaperController.PaperRecommendationCandidatesRequest("Agentic eval", 999, 99),
                "1",
                "default"
        );

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        List<?> candidates = (List<?>) data.get("candidates");
        PaperRecommendationCandidate first = (PaperRecommendationCandidate) candidates.get(0);

        assertEquals(200, body.get("code"));
        assertEquals("获取论文候选成功", body.get("message"));
        assertEquals("Agentic eval", data.get("queryText"));
        assertEquals("PRODUCT_LIBRARY", data.get("scope"));
        assertEquals("paper-agent", first.paperId());
        assertEquals("SUPPORTED", first.evidenceStatus());

        ArgumentCaptor<PaperRecommendationSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(PaperRecommendationSearchRequest.class);
        verify(paperRecommendationCandidateService).search(requestCaptor.capture());
        assertEquals("1", requestCaptor.getValue().userId());
        assertEquals("default", requestCaptor.getValue().orgTags());
        assertEquals(100, requestCaptor.getValue().paperLimit());
        assertEquals(10, requestCaptor.getValue().perPaperLocationLimit());
    }

    @Test
    void recommendationCandidateEndpointRejectsBlankQuery() {
        var response = paperController.recommendationCandidates(
                new PaperController.PaperRecommendationCandidatesRequest(" ", 20, 3),
                "1",
                "default"
        );

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(400, body.get("code"));
        verify(paperRecommendationCandidateService, never()).search(any(PaperRecommendationSearchRequest.class));
    }

    @Test
    void pdfPreviewResponseUsesApplicationPdfDataUrl() {
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
        assertEquals("/api/v1/papers/paper-preview/preview/pdf-data", data.get("previewUrl"));
        assertEquals("/api/v1/papers/paper-preview/preview/pdf-data", data.get("previewDataUrl"));
        assertFalse(data.containsKey("downloadUrl"));
        verify(paperService, never()).generateDownloadUrl("paper-preview");
        verify(paperService, never()).generateAttachmentDownloadUrl("paper-preview");
    }

    @Test
    void pdfPreviewDataEndpointReturnsRangeMetadataToAvoidNativeBrowserDownload() {
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

        var response = paperController.previewPdfDataByPath("paper-preview", null, null);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");

        assertEquals("paper-preview", data.get("paperId"));
        assertEquals("application/pdf", data.get("contentType"));
        assertEquals("preview-paper.pdf", data.get("originalFilename"));
        assertEquals(8L, data.get("sourceFileSizeBytes"));
        assertEquals(262144, data.get("chunkSizeBytes"));
        assertEquals("/api/v1/papers/paper-preview/preview/pdf-data/range", data.get("rangeUrl"));
        assertFalse(data.containsKey("contentBase64"));
        assertNull(response.getHeaders().getContentType());
        assertNull(response.getHeaders().getFirst("Content-Disposition"));
        verify(paperService, never()).openMergedPdfStream("paper-preview");
    }

    @Test
    void pdfPreviewDataRangeEndpointReturnsJsonChunkToAvoidNativeBrowserDownload() {
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
        when(paperService.openMergedPdfRangeStream("paper-preview", 1L, 4L))
                .thenReturn(new ByteArrayInputStream("PDF-".getBytes()));

        var response = paperController.previewPdfDataRangeByPath("paper-preview", 1L, 5L, null, null);

        assertEquals(206, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");

        assertEquals("paper-preview", data.get("paperId"));
        assertEquals(1L, data.get("begin"));
        assertEquals(5L, data.get("end"));
        assertEquals(1L, data.get("offset"));
        assertEquals(4, data.get("length"));
        assertEquals(8L, data.get("totalSizeBytes"));
        assertEquals("UERGLQ==", data.get("contentBase64"));
        assertEquals("application/pdf", data.get("contentType"));
        assertNull(response.getHeaders().getContentType());
        assertNull(response.getHeaders().getFirst("Content-Disposition"));
        verify(paperService).openMergedPdfRangeStream("paper-preview", 1L, 4L);
        verify(paperService, never()).openMergedPdfStream("paper-preview");
    }

    @Test
    void pdfPreviewDataRangeAllowsPdfJsMultiMegabyteRanges() {
        long requestedLength = 2L * 1024 * 1024;
        Paper paper = paper(
                "paper-preview",
                "Preview Paper",
                "preview-paper.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED,
                Paper.STATUS_COMPLETED
        );
        paper.setTotalSize(requestedLength + 16);

        when(paperRepository.findFirstByPaperIdAndIsPublicTrueOrderByCreatedAtDesc("paper-preview"))
                .thenReturn(Optional.of(paper));
        when(paperService.openMergedPdfRangeStream("paper-preview", 0L, requestedLength))
                .thenReturn(new ByteArrayInputStream(new byte[(int) requestedLength]));

        var response = paperController.previewPdfDataRangeByPath("paper-preview", 0L, requestedLength, null, null);

        assertEquals(206, response.getStatusCode().value());
        verify(paperService).openMergedPdfRangeStream("paper-preview", 0L, requestedLength);
    }

    @Test
    void legacyPdfPreviewEndpointReturnsJsonRangeMetadataToAvoidNativeBrowserDownload() {
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

        var response = paperController.previewPdfByPath("paper-preview", null, null);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");

        assertEquals("/api/v1/papers/paper-preview/preview/pdf-data/range", data.get("rangeUrl"));
        assertFalse(data.containsKey("contentBase64"));
        assertNull(response.getHeaders().getContentType());
        assertNull(response.getHeaders().getFirst("Content-Disposition"));
        verify(paperService, never()).openMergedPdfStream("paper-preview");
    }

    @Test
    void downloadEndpointUsesExplicitAttachmentDownloadUrl() {
        Paper paper = paper(
                "paper-download",
                "Download Paper",
                "download-paper.pdf",
                Paper.VECTORIZATION_STATUS_COMPLETED,
                Paper.STATUS_COMPLETED
        );

        when(paperRepository.findFirstByPaperIdAndIsPublicTrueOrderByCreatedAtDesc("paper-download"))
                .thenReturn(Optional.of(paper));
        when(paperService.generateAttachmentDownloadUrl("paper-download"))
                .thenReturn("http://localhost:9000/uploads/merged/paper-download?download=1");

        var response = paperController.downloadPaperByPath("paper-download", null, null);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("http://localhost:9000/uploads/merged/paper-download?download=1", data.get("downloadUrl"));
        verify(paperService).generateAttachmentDownloadUrl("paper-download");
        verify(paperService, never()).generateDownloadUrl("paper-download");
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
