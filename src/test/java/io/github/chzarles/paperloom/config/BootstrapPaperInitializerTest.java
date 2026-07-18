package io.github.chzarles.paperloom.config;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.ParseService;
import io.github.chzarles.paperloom.service.ReadingModelQdrantIndexService;
import io.github.chzarles.paperloom.service.RetrievalIndexingService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapPaperInitializerTest {

    @Mock
    private ParseService parseService;

    @Mock
    private RetrievalIndexingService retrievalIndexingService;

    @Mock
    private ReadingModelQdrantIndexService qdrantIndexService;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BootstrapPaperInitializer initializer;

    @TempDir
    Path tempDir;

    @Test
    void shouldSkipWhenBootstrapDocumentAlreadyReady() throws Exception {
        Path pdfPath = createPdfLikeFile("paperloom.pdf", "paperloom ready");
        String fileMd5 = md5For(pdfPath);

        Paper existingFile = new Paper();
        existingFile.setPaperId(fileMd5);
        existingFile.setOriginalFilename("paperloom.pdf");
        existingFile.setOrgTag("default");
        existingFile.setPublic(true);
        existingFile.setStatus(1);
        existingFile.setUserId("1");

        configureInitializer(pdfPath);
        mockAdminUser(1L, "admin");
        when(paperRepository.findByUserIdAndOriginalFilenameOrderByCreatedAtDesc("1", "paperloom.pdf"))
                .thenReturn(Collections.emptyList());
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(fileMd5, "1"))
                .thenReturn(Optional.of(existingFile));
        when(paperRepository.countByPaperIdAndUserId(fileMd5, "1")).thenReturn(1L);
        when(paperTextChunkRepository.countByPaperId(fileMd5)).thenReturn(2L);
        when(paperTextChunkRepository.countByPaperIdAndPageNumberIsNotNull(fileMd5)).thenReturn(2L);
        when(qdrantIndexService.countByPaperId(fileMd5)).thenReturn(2L);

        initializer.run();

        verify(parseService, never()).parseAndSave(anyString(), any(), anyString(), anyString(), anyString(), anyBoolean());
        verify(retrievalIndexingService, never()).index(anyString(), anyString());
        verify(paperRepository, never()).save(any(Paper.class));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void shouldImportWhenBootstrapDocumentMissing() throws Exception {
        Path pdfPath = createPdfLikeFile("paperloom.pdf", "paperloom import");
        String fileMd5 = md5For(pdfPath);

        configureInitializer(pdfPath);
        mockAdminUser(1L, "admin");
        when(paperRepository.findByUserIdAndOriginalFilenameOrderByCreatedAtDesc("1", "paperloom.pdf"))
                .thenReturn(Collections.emptyList());
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(fileMd5, "1"))
                .thenReturn(Optional.empty());
        when(paperRepository.countByPaperIdAndUserId(fileMd5, "1")).thenReturn(0L);
        when(paperTextChunkRepository.countByPaperId(fileMd5)).thenReturn(0L);
        when(paperTextChunkRepository.countByPaperIdAndPageNumberIsNotNull(fileMd5)).thenReturn(0L);
        when(qdrantIndexService.countByPaperId(fileMd5)).thenReturn(0L);
        doNothing().when(retrievalIndexingService).index(fileMd5, "system-bootstrap");
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);

        initializer.run();

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(parseService).parseAndSave(eq(fileMd5), any(), eq("paperloom.pdf"), eq("1"), eq("default"), eq(true));
        verify(retrievalIndexingService).index(fileMd5, "system-bootstrap");

        ArgumentCaptor<Paper> captor = ArgumentCaptor.forClass(Paper.class);
        verify(paperRepository).save(captor.capture());

        Paper savedFile = captor.getValue();
        assertEquals(fileMd5, savedFile.getPaperId());
        assertEquals("paperloom.pdf", savedFile.getOriginalFilename());
        assertEquals("1", savedFile.getUserId());
        assertEquals("default", savedFile.getOrgTag());
        assertTrue(savedFile.isPublic());
    }

    @Test
    void shouldDeleteDuplicateBootstrapRecordsBeforeSkippingImport() throws Exception {
        Path pdfPath = createPdfLikeFile("paperloom.pdf", "paperloom duplicate");
        String fileMd5 = md5For(pdfPath);

        Paper newest = new Paper();
        newest.setId(1L);
        newest.setPaperId(fileMd5);
        newest.setOriginalFilename("paperloom.pdf");
        newest.setOrgTag("default");
        newest.setPublic(true);
        newest.setStatus(1);
        newest.setUserId("1");

        Paper duplicate = new Paper();
        duplicate.setId(2L);
        duplicate.setPaperId(fileMd5);
        duplicate.setOriginalFilename("paperloom.pdf");
        duplicate.setOrgTag("default");
        duplicate.setPublic(true);
        duplicate.setStatus(1);
        duplicate.setUserId("1");

        configureInitializer(pdfPath);
        mockAdminUser(1L, "admin");
        when(paperRepository.findByUserIdAndOriginalFilenameOrderByCreatedAtDesc("1", "paperloom.pdf"))
                .thenReturn(List.of(newest, duplicate));
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(fileMd5, "1"))
                .thenReturn(Optional.of(newest));
        when(paperRepository.countByPaperIdAndUserId(fileMd5, "1")).thenReturn(1L);
        when(paperTextChunkRepository.countByPaperId(fileMd5)).thenReturn(2L);
        when(paperTextChunkRepository.countByPaperIdAndPageNumberIsNotNull(fileMd5)).thenReturn(2L);
        when(qdrantIndexService.countByPaperId(fileMd5)).thenReturn(2L);

        initializer.run();

        verify(paperRepository).deleteAll(List.of(duplicate));
        verify(parseService, never()).parseAndSave(anyString(), any(), anyString(), anyString(), anyString(), anyBoolean());
        verify(retrievalIndexingService, never()).index(anyString(), anyString());
    }

    private void configureInitializer(Path pdfPath) {
        ReflectionTestUtils.setField(initializer, "bootstrapPaperPath", pdfPath.toString());
        ReflectionTestUtils.setField(initializer, "bootstrapOrgTag", "default");
        ReflectionTestUtils.setField(initializer, "bootstrapPublic", true);
        ReflectionTestUtils.setField(initializer, "bootstrapUserId", "system-bootstrap");
        ReflectionTestUtils.setField(initializer, "minioBucketName", "uploads");
        ReflectionTestUtils.setField(initializer, "adminUsername", "admin");
    }

    private Path createPdfLikeFile(String fileName, String content) throws IOException {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, content);
        return path;
    }

    private String md5For(Path path) {
        return (String) ReflectionTestUtils.invokeMethod(initializer, "calculateMd5", path);
    }

    private void mockAdminUser(Long id, String username) {
        User adminUser = new User();
        adminUser.setId(id);
        adminUser.setUsername(username);
        adminUser.setRole(User.Role.ADMIN);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(adminUser));
    }
}
