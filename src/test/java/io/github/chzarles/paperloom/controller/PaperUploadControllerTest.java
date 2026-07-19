package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.OrganizationTag;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.service.FileTypeValidationService;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
import io.github.chzarles.paperloom.service.ParseService;
import io.github.chzarles.paperloom.service.UploadService;
import io.github.chzarles.paperloom.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PaperUploadControllerTest {

    @Mock
    private UploadService uploadService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private KafkaConfig kafkaConfig;

    @Mock
    private UserService userService;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private FileTypeValidationService fileTypeValidationService;

    @Mock
    private ParseService parseService;

    @Mock
    private PaperSearchabilityService paperSearchabilityService;

    private PaperUploadController uploadController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadController = new PaperUploadController(uploadService, kafkaTemplate);
        ReflectionTestUtils.setField(uploadController, "kafkaConfig", kafkaConfig);
        ReflectionTestUtils.setField(uploadController, "userService", userService);
        ReflectionTestUtils.setField(uploadController, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(uploadController, "fileTypeValidationService", fileTypeValidationService);
        ReflectionTestUtils.setField(uploadController, "parseService", parseService);
        ReflectionTestUtils.setField(uploadController, "paperSearchabilityService", paperSearchabilityService);
        when(fileTypeValidationService.getSupportedFileTypes()).thenReturn(Set.of("pdf"));
    }

    @Test
    void testUploadChunkRejectsOversizedFileForNonAdmin() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());
        OrganizationTag orgTag = new OrganizationTag();
        orgTag.setTagId("TEAM_A");
        orgTag.setUploadMaxSizeBytes(1024L * 1024L);

        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF论文", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(false);
        when(userService.getUserPrimaryOrg("1")).thenReturn("TEAM_A");
        when(userService.getOrganizationTag("TEAM_A")).thenReturn(orgTag);

        var response = uploadController.uploadChunk(
                "md5",
                0,
                2L * 1024 * 1024,
                "test.pdf",
                1,
                file,
                "1"
        );

        assertEquals(413, response.getStatusCode().value());
        assertEquals(413, response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("不超过"));
        verify(uploadService, never()).uploadChunk(anyString(), anyInt(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void testUploadChunkAllowsAdminToBypassOrgLimit() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF论文", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(true);
        when(uploadService.getUploadedChunks("md5", "1")).thenReturn(List.of(0));
        when(uploadService.getTotalChunks("md5", "1")).thenReturn(1);

        var response = uploadController.uploadChunk(
                "md5",
                0,
                20L * 1024 * 1024,
                "test.pdf",
                1,
                file,
                "1"
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals(
                Map.of(
                        "uploaded", List.of(0),
                        "progress", 100.0d,
                        "paperId", "md5",
                        "paperTitle", "test.pdf",
                        "originalFilename", "test.pdf"
                ),
                response.getBody().get("data")
        );
        verify(uploadService).uploadChunk("md5", 0, 20L * 1024 * 1024, "test.pdf", file, "1");
        verify(userService, never()).getOrganizationTag(anyString());
    }

    @Test
    void testUploadChunkRejectsWhenLaterChunkExceedsOrgLimitEvenIfTotalSizeIsUnderreported() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());
        OrganizationTag orgTag = new OrganizationTag();
        orgTag.setTagId("TEAM_A");
        orgTag.setUploadMaxSizeBytes(5L * 1024 * 1024L);

        when(userService.isAdminUser("1")).thenReturn(false);
        when(userService.getUserPrimaryOrg("1")).thenReturn("TEAM_A");
        when(userService.getOrganizationTag("TEAM_A")).thenReturn(orgTag);

        var response = uploadController.uploadChunk(
                "md5",
                1,
                1024L,
                "test.pdf",
                2,
                file,
                "1"
        );

        assertEquals(413, response.getStatusCode().value());
        verify(uploadService, never()).uploadChunk(anyString(), anyInt(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void testMergeFileReturnsExistingResultWhenAlreadyCompleted() throws Exception {
        Paper paper = new Paper();
        paper.setPaperId("md5");
        paper.setOriginalFilename("test.pdf");
        paper.setUserId("1");
        paper.setStatus(Paper.STATUS_COMPLETED);

        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(paper));
        when(uploadService.generateMergedObjectUrl("md5")).thenReturn("https://example.com/merged/md5");

        var response = uploadController.mergeFile(new PaperUploadController.MergeRequest("md5", "test.pdf"), "1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("论文 PDF 已完成合并", response.getBody().get("message"));
        assertEquals("https://example.com/merged/md5", ((Map<?, ?>) response.getBody().get("data")).get("objectUrl"));
        verify(uploadService, never()).mergeChunks(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }

    @Test
    void testMergeFileReusesExistingSearchableCanonicalPaper() throws Exception {
        Paper paper = new Paper();
        paper.setPaperId("md5");
        paper.setOriginalFilename("test.pdf");
        paper.setUserId("1");
        paper.setStatus(Paper.STATUS_UPLOADING);

        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(paper));
        when(uploadService.getUploadedChunks("md5", "1")).thenReturn(List.of(0));
        when(uploadService.getTotalChunks("md5", "1")).thenReturn(1);
        when(paperSearchabilityService.isSearchable("md5")).thenReturn(true);
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("md5"))
                .thenReturn(Optional.of(paper));
        when(uploadService.generateMergedObjectUrl("md5")).thenReturn("https://example.com/merged/md5");

        var response = uploadController.mergeFile(new PaperUploadController.MergeRequest("md5", "test.pdf"), "1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Paper.STATUS_COMPLETED, paper.getStatus());
        assertEquals(Paper.VECTORIZATION_STATUS_COMPLETED, paper.getVectorizationStatus());
        verify(paperRepository).save(paper);
        verify(uploadService, never()).mergeChunks(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }
}
