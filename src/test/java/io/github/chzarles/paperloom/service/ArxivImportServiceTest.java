package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivImportServiceTest {

    @Test
    void materializesConfirmedVersionIntoTheExistingUploadTask() throws Exception {
        ArxivClient client = mock(ArxivClient.class);
        PaperRepository repository = mock(PaperRepository.class);
        MinioClient minio = mock(MinioClient.class);
        String arxivId = "2401.01234v2";
        byte[] pdf = "%PDF-1.7 test".getBytes();
        when(client.fetchExact(arxivId)).thenReturn(new ArxivClient.Paper(
                arxivId, "A Paper", List.of("Ada"),
                "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z",
                "Abstract.", "https://arxiv.org/pdf/" + arxivId));
        when(client.downloadPdf(arxivId)).thenReturn(pdf);
        when(repository.findFirstByArxivIdAndUserIdOrderByCreatedAtDesc(arxivId, "7"))
                .thenReturn(Optional.empty());
        when(repository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(any(), org.mockito.ArgumentMatchers.eq("7")))
                .thenReturn(Optional.empty());

        ArxivImportService service = new ArxivImportService(client, repository, minio);
        PaperProcessingTask task = new PaperProcessingTask(
                arxivId, null, arxivId, "7", PaperProcessingTask.TASK_TYPE_ARXIV_IMPORT, "7");
        task.setArxivId(arxivId);

        PaperProcessingTask next = service.materialize(task);

        assertNull(next.getArxivId());
        assertEquals(PaperProcessingTask.TASK_TYPE_UPLOAD_PROCESS, next.getTaskType());
        assertEquals("7", next.getUserId());
        verify(client).fetchExact(arxivId);
        verify(client).downloadPdf(arxivId);
        verify(minio).putObject(any());
        verify(repository).save(any(Paper.class));
    }
}
