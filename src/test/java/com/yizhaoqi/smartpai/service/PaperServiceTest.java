package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaperServiceTest {

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private ChunkInfoRepository chunkInfoRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private ElasticsearchService elasticsearchService;

    @Mock
    private UploadService uploadService;

    private PaperService paperService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        paperService = new PaperService();
        ReflectionTestUtils.setField(paperService, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(paperService, "paperTextChunkRepository", paperTextChunkRepository);
        ReflectionTestUtils.setField(paperService, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(paperService, "minioClient", minioClient);
        ReflectionTestUtils.setField(paperService, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(paperService, "uploadService", uploadService);
    }

    @Test
    void adminDeleteUsesTargetOwnerAndKeepsSharedPhysicalArtifacts() {
        Paper target = paper("same-hash", "2");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("same-hash"))
                .thenReturn(Optional.of(target));
        when(paperRepository.countByPaperId("same-hash")).thenReturn(1L);

        paperService.deletePaper("same-hash", "1", "ADMIN");

        verify(uploadService).deleteFileMark("same-hash", "2");
        verify(paperRepository).delete(target);
        verify(paperRepository).flush();
        verify(paperRepository).countByPaperId("same-hash");
        verifyNoInteractions(elasticsearchService);
        verifyNoInteractions(paperTextChunkRepository);
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void deleteRemovesPhysicalArtifactsWhenNoPaperRecordsRemain() throws Exception {
        Paper target = paper("only-hash", "2");
        ChunkInfo chunk = new ChunkInfo();
        chunk.setPaperId("only-hash");
        chunk.setChunkIndex(0);
        chunk.setChunkMd5("chunk-md5");
        chunk.setStoragePath("chunks/only-hash/0");

        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("only-hash", "2"))
                .thenReturn(Optional.of(target));
        when(paperRepository.countByPaperId("only-hash")).thenReturn(0L);
        when(chunkInfoRepository.findByPaperIdOrderByChunkIndexAsc("only-hash"))
                .thenReturn(List.of(chunk));

        paperService.deletePaper("only-hash", "2", "USER");

        verify(uploadService).deleteFileMark("only-hash", "2");
        verify(elasticsearchService).deleteByPaperId("only-hash");
        verify(minioClient, times(2)).removeObject(any(RemoveObjectArgs.class));
        verify(paperTextChunkRepository).deleteByPaperId("only-hash");
        verify(chunkInfoRepository).deleteByPaperId("only-hash");
    }

    private Paper paper(String paperId, String userId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setOriginalFilename(paperId + ".pdf");
        paper.setUserId(userId);
        return paper;
    }
}
