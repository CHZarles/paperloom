package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.ChunkInfo;
import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.paper.parser.MinerUUnavailableException;
import io.github.chzarles.paperloom.repository.ChunkInfoRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private PaperParserArtifactService paperParserArtifactService;

    @Mock
    private PaperVisualAssetService paperVisualAssetService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private KafkaConfig kafkaConfig;

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
        ReflectionTestUtils.setField(paperService, "paperParserArtifactService", paperParserArtifactService);
        ReflectionTestUtils.setField(paperService, "paperVisualAssetService", paperVisualAssetService);
        ReflectionTestUtils.setField(paperService, "kafkaTemplate", kafkaTemplate);
        ReflectionTestUtils.setField(paperService, "kafkaConfig", kafkaConfig);
    }

    @Test
    void generateDownloadUrlRequestsInlinePdfResponseHeaders() throws Exception {
        Paper paper = paper("paper-md5", "1");
        paper.setOriginalFilename("paper-md5");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-md5"))
                .thenReturn(Optional.of(paper));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/uploads/merged/paper-md5");
        when(uploadService.transToPublicUrl("http://localhost:9000/uploads/merged/paper-md5"))
                .thenReturn("http://localhost:9000/uploads/merged/paper-md5");

        paperService.generateDownloadUrl("paper-md5");

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertEquals("merged/paper-md5", args.object());
        assertEquals("application/pdf", firstQueryParam(args, "response-content-type"));
        assertEquals("inline; filename=\"paper-md5.pdf\"",
                firstQueryParam(args, "response-content-disposition"));
    }

    @Test
    void generateAttachmentDownloadUrlRequestsAttachmentPdfResponseHeaders() throws Exception {
        Paper paper = paper("paper-md5", "1");
        paper.setOriginalFilename("paper-md5");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-md5"))
                .thenReturn(Optional.of(paper));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/uploads/merged/paper-md5");
        when(uploadService.transToPublicUrl("http://localhost:9000/uploads/merged/paper-md5"))
                .thenReturn("http://localhost:9000/uploads/merged/paper-md5");

        paperService.generateAttachmentDownloadUrl("paper-md5");

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertEquals("application/pdf", firstQueryParam(args, "response-content-type"));
        assertEquals("attachment; filename=\"paper-md5.pdf\"",
                firstQueryParam(args, "response-content-disposition"));
    }

    @Test
    void openMergedPdfRangeStreamDelegatesToUploadRangeStream() throws Exception {
        InputStream rangeStream = new ByteArrayInputStream("%PDF".getBytes());
        when(uploadService.getMergedFileRangeStream("paper-md5", 10L, 20L)).thenReturn(rangeStream);

        InputStream result = paperService.openMergedPdfRangeStream("paper-md5", 10L, 20L);

        assertSame(rangeStream, result);
        verify(uploadService).getMergedFileRangeStream("paper-md5", 10L, 20L);
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

    @Test
    void vectorizationFailureKeepsMinerUUnavailableMessage() {
        Paper paper = paper("paper-md5", "1");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-md5"))
                .thenReturn(Optional.of(paper));

        paperService.markVectorizationFailed(
                "paper-md5",
                new RuntimeException("Error processing task",
                        new MinerUUnavailableException(
                                "MinerU sidecar unavailable at http://localhost:8000/health. Start the self-hosted MinerU service or explicitly set PAPER_PARSING_PROVIDER=opendataloader for local fallback.",
                                new RuntimeException("finishConnect(..) failed: Connection refused")
                        ))
        );

        ArgumentCaptor<Paper> captor = ArgumentCaptor.forClass(Paper.class);
        verify(paperRepository).save(captor.capture());
        Paper saved = captor.getValue();
        assertEquals(Paper.VECTORIZATION_STATUS_FAILED, saved.getVectorizationStatus());
        assertTrue(saved.getVectorizationErrorMessage().startsWith("MinerU sidecar unavailable"));
        assertTrue(saved.getVectorizationErrorMessage().contains("PAPER_PARSING_PROVIDER=opendataloader"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryVectorizationSendsPaperIdAsKafkaKey() {
        Paper paper = paper("paper-md5", "1");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-md5"))
                .thenReturn(Optional.of(paper));
        when(kafkaConfig.getPaperProcessingTopic()).thenReturn("paper-processing-topic");
        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaOperations.OperationsCallback<String, Object, Boolean> callback = invocation.getArgument(0);
            return callback.doInOperations(kafkaTemplate);
        });

        paperService.enqueueAsyncVectorizationRetry("paper-md5", "admin");

        verify(kafkaTemplate).send(eq("paper-processing-topic"), eq("paper-md5"), any());
    }

    @Test
    void reindexPaperDoesNotWrapOcrAndEmbeddingInOneTransaction() throws NoSuchMethodException {
        Method method = PaperService.class.getMethod("reindexPaper", String.class, String.class);

        assertFalse(method.isAnnotationPresent(Transactional.class));
    }

    private Paper paper(String paperId, String userId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setOriginalFilename(paperId + ".pdf");
        paper.setUserId(userId);
        return paper;
    }

    private String firstQueryParam(GetPresignedObjectUrlArgs args, String name) {
        return args.extraQueryParams().get(name).stream().findFirst().orElse(null);
    }
}
