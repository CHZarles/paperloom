package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.MinioConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private MinioClient minioClient;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private ChunkInfoRepository chunkInfoRepository;

    @Mock
    private MinioConfig minioConfig;

    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadService = new UploadService();
        ReflectionTestUtils.setField(uploadService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(uploadService, "minioClient", minioClient);
        ReflectionTestUtils.setField(uploadService, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(uploadService, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(uploadService, "minioConfig", minioConfig);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void uploadChunkRejectsWhenFileAlreadyCompleted() throws Exception {
        Paper paper = new Paper();
        paper.setPaperId("md5");
        paper.setUserId("1");
        paper.setStatus(Paper.STATUS_COMPLETED);

        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(paper));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("论文 PDF 已完成合并，不允许继续上传分片", exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void uploadChunkRejectsWhenFileIsMerging() throws Exception {
        Paper paper = new Paper();
        paper.setPaperId("md5");
        paper.setUserId("1");
        paper.setStatus(Paper.STATUS_MERGING);

        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(paper));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("论文 PDF 正在合并中，请稍后重试", exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void uploadChunkSkipsDatabaseWhenRedisBitmapHit() throws Exception {
        Paper paper = uploadingFile();
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(paper));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        verifyNoInteractions(chunkInfoRepository);
        verifyNoInteractions(minioClient);
    }

    @Test
    void uploadChunkBackfillsRedisWhenDatabaseHasChunkAfterRedisMiss() throws Exception {
        Paper paper = uploadingFile();
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(paper));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(false);
        when(chunkInfoRepository.existsByPaperIdAndChunkIndex("md5", 0)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        verify(valueOperations).setBit("upload:1:md5", 0, true);
        verifyNoInteractions(minioClient);
    }

    @Test
    void uploadChunkWritesDatabaseBeforeRedisForNewChunk() throws Exception {
        Paper paper = uploadingFile();
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(paper));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(false);
        when(chunkInfoRepository.existsByPaperIdAndChunkIndex("md5", 0)).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        InOrder inOrder = inOrder(minioClient, chunkInfoRepository, valueOperations);
        inOrder.verify(minioClient).putObject(any(PutObjectArgs.class));
        inOrder.verify(chunkInfoRepository).save(any(ChunkInfo.class));
        inOrder.verify(valueOperations).setBit("upload:1:md5", 0, true);
    }

    @Test
    void generateMergedObjectUrlRequestsInlinePdfResponseHeaders() throws Exception {
        Paper paper = new Paper();
        paper.setPaperId("md5");
        paper.setOriginalFilename("uploaded paper.pdf");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("md5"))
                .thenReturn(Optional.of(paper));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/uploads/merged/md5");

        uploadService.generateMergedObjectUrl("md5");

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertEquals("merged/md5", args.object());
        assertEquals("application/pdf", firstQueryParam(args, "response-content-type"));
        assertEquals("inline; filename=\"uploaded paper.pdf\"",
                firstQueryParam(args, "response-content-disposition"));
    }

    private Paper uploadingFile() {
        Paper paper = new Paper();
        paper.setPaperId("md5");
        paper.setUserId("1");
        paper.setStatus(Paper.STATUS_UPLOADING);
        return paper;
    }

    private String firstQueryParam(GetPresignedObjectUrlArgs args, String name) {
        return args.extraQueryParams().get(name).stream().findFirst().orElse(null);
    }
}
