package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapPaperInitializerTest {

    @Mock
    private ParseService parseService;

    @Mock
    private VectorizationService vectorizationService;

    @Mock
    private ElasticsearchService elasticsearchService;

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
        Path pdfPath = createPdfLikeFile("paismart.pdf", "paismart ready");
        String fileMd5 = md5For(pdfPath);

        Paper existingFile = new Paper();
        existingFile.setPaperId(fileMd5);
        existingFile.setOriginalFilename("paismart.pdf");
        existingFile.setOrgTag("default");
        existingFile.setPublic(true);
        existingFile.setStatus(1);
        existingFile.setUserId("1");

        configureInitializer(pdfPath);
        mockAdminUser(1L, "admin");
        when(paperRepository.findByUserIdAndOriginalFilenameOrderByCreatedAtDesc("1", "paismart.pdf"))
                .thenReturn(Collections.emptyList());
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(fileMd5, "1"))
                .thenReturn(Optional.of(existingFile));
        when(paperRepository.countByPaperIdAndUserId(fileMd5, "1")).thenReturn(1L);
        when(paperTextChunkRepository.countByPaperId(fileMd5)).thenReturn(2L);
        when(paperTextChunkRepository.countByPaperIdAndPageNumberIsNotNull(fileMd5)).thenReturn(2L);
        when(elasticsearchService.countByPaperId(fileMd5)).thenReturn(2L);

        initializer.run();

        verify(parseService, never()).parseAndSave(anyString(), any(), anyString(), anyString(), anyBoolean());
        verify(vectorizationService, never()).vectorize(anyString(), anyString(), anyString(), anyBoolean(), anyString());
        verify(paperRepository, never()).save(any(Paper.class));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void shouldImportWhenBootstrapDocumentMissing() throws Exception {
        Path pdfPath = createPdfLikeFile("paismart.pdf", "paismart import");
        String fileMd5 = md5For(pdfPath);

        configureInitializer(pdfPath);
        mockAdminUser(1L, "admin");
        when(paperRepository.findByUserIdAndOriginalFilenameOrderByCreatedAtDesc("1", "paismart.pdf"))
                .thenReturn(Collections.emptyList());
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(fileMd5, "1"))
                .thenReturn(Optional.empty());
        when(paperRepository.countByPaperIdAndUserId(fileMd5, "1")).thenReturn(0L);
        when(paperTextChunkRepository.countByPaperId(fileMd5)).thenReturn(0L);
        when(paperTextChunkRepository.countByPaperIdAndPageNumberIsNotNull(fileMd5)).thenReturn(0L);
        when(elasticsearchService.countByPaperId(fileMd5)).thenReturn(0L);
        doNothing().when(vectorizationService).vectorize(fileMd5, "1", "default", true, "system-bootstrap");
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);

        initializer.run();

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(parseService).parseAndSave(anyString(), any(), anyString(), anyString(), anyBoolean());
        verify(vectorizationService).vectorize(fileMd5, "1", "default", true, "system-bootstrap");

        ArgumentCaptor<Paper> captor = ArgumentCaptor.forClass(Paper.class);
        verify(paperRepository).save(captor.capture());

        Paper savedFile = captor.getValue();
        assertEquals(fileMd5, savedFile.getPaperId());
        assertEquals("paismart.pdf", savedFile.getOriginalFilename());
        assertEquals("1", savedFile.getUserId());
        assertEquals("default", savedFile.getOrgTag());
        assertTrue(savedFile.isPublic());
    }

    @Test
    void shouldDeleteDuplicateBootstrapRecordsBeforeSkippingImport() throws Exception {
        Path pdfPath = createPdfLikeFile("paismart.pdf", "paismart duplicate");
        String fileMd5 = md5For(pdfPath);

        Paper newest = new Paper();
        newest.setId(1L);
        newest.setPaperId(fileMd5);
        newest.setOriginalFilename("paismart.pdf");
        newest.setOrgTag("default");
        newest.setPublic(true);
        newest.setStatus(1);
        newest.setUserId("1");

        Paper duplicate = new Paper();
        duplicate.setId(2L);
        duplicate.setPaperId(fileMd5);
        duplicate.setOriginalFilename("paismart.pdf");
        duplicate.setOrgTag("default");
        duplicate.setPublic(true);
        duplicate.setStatus(1);
        duplicate.setUserId("1");

        configureInitializer(pdfPath);
        mockAdminUser(1L, "admin");
        when(paperRepository.findByUserIdAndOriginalFilenameOrderByCreatedAtDesc("1", "paismart.pdf"))
                .thenReturn(List.of(newest, duplicate));
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(fileMd5, "1"))
                .thenReturn(Optional.of(newest));
        when(paperRepository.countByPaperIdAndUserId(fileMd5, "1")).thenReturn(1L);
        when(paperTextChunkRepository.countByPaperId(fileMd5)).thenReturn(2L);
        when(paperTextChunkRepository.countByPaperIdAndPageNumberIsNotNull(fileMd5)).thenReturn(2L);
        when(elasticsearchService.countByPaperId(fileMd5)).thenReturn(2L);

        initializer.run();

        verify(paperRepository).deleteAll(List.of(duplicate));
        verify(parseService, never()).parseAndSave(anyString(), any(), anyString(), anyString(), anyBoolean());
        verify(vectorizationService, never()).vectorize(anyString(), anyString(), anyString(), anyBoolean(), anyString());
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
