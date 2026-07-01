package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.PaperProcessingTask;
import com.yizhaoqi.smartpai.service.PaperService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.minio.GetObjectResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperProcessingConsumerTest {

    @Mock
    private ParseService parseService;

    @Mock
    private VectorizationService vectorizationService;

    @Mock
    private PaperService paperService;

    @Mock
    private UploadService uploadService;

    private PaperProcessingConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new PaperProcessingConsumer(parseService, vectorizationService, paperService, uploadService);
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
        when(vectorizationService.vectorizeWithUsage(paperId, "1", "default", false, "1"))
                .thenReturn(new VectorizationService.VectorizationUsageResult(10, 2, "embedding-model"));

        PaperProcessingTask task = new PaperProcessingTask(
                paperId,
                "http://127.0.0.1:9/expired-presigned-url",
                "paper.pdf",
                "1",
                "default",
                false,
                PaperProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                "1"
        );

        consumer.processTask(task);

        verify(uploadService).getMergedFileStream(paperId);
        verify(parseService).parseAndSave(eq(paperId), any(), eq("paper.pdf"), eq("1"), eq("default"), eq(false));
        verify(paperService).markVectorizationCompleted(
                eq(paperId),
                eq(new VectorizationService.VectorizationUsageResult(10, 2, "embedding-model"))
        );
    }
}
