package io.github.chzarles.paperloom.consumer;

import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.service.PaperService;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
import io.github.chzarles.paperloom.service.ParseService;
import io.github.chzarles.paperloom.service.UploadService;
import io.github.chzarles.paperloom.service.RetrievalIndexingService;
import io.minio.GetObjectResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperProcessingConsumerTest {

    @Mock
    private ParseService parseService;

    @Mock
    private RetrievalIndexingService retrievalIndexingService;

    @Mock
    private PaperService paperService;

    @Mock
    private UploadService uploadService;

    @Mock
    private PaperSearchabilityService searchabilityService;

    @Mock
    private PaperReadingModelRepository readingModelRepository;

    private PaperProcessingConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new PaperProcessingConsumer(
                parseService, retrievalIndexingService, paperService, uploadService,
                searchabilityService, readingModelRepository);
    }

    @Test
    void uploadProcessReadsMergedObjectByPaperIdInsteadOfTaskUrl() throws Exception {
        String paperId = "paper-1";
        GetObjectResponse pdfStream = new GetObjectResponse(
                Headers.of(),
                "uploads",
                "us-east-1",
                "merged/" + paperId,
                new ByteArrayInputStream("%PDF-1.7".getBytes())
        );
        when(uploadService.getMergedFileStream(paperId)).thenReturn(pdfStream);
        when(retrievalIndexingService.indexWithMetrics(paperId, "1"))
                .thenReturn(new RetrievalIndexingService.IndexingResult(10, 2, "lexical-contract"));

        PaperProcessingTask task = new PaperProcessingTask(
                paperId,
                "http://127.0.0.1:9/expired-presigned-url",
                "paper.pdf",
                "1",
                PaperProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                "1"
        );

        consumer.processTask(task);

        verify(uploadService).getMergedFileStream(paperId);
        verify(parseService).parseAndSave(eq(paperId), any(), eq("paper.pdf"), eq("1"), isNull(), eq(false));
        verify(paperService).markVectorizationCompleted(
                eq(paperId),
                eq(new RetrievalIndexingService.IndexingResult(10, 2, "lexical-contract"))
        );
    }
}
