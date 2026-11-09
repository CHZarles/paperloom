package io.github.chzarles.paperloom.service.discovery;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.Paper;

import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
import io.github.chzarles.paperloom.service.UploadService;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivDiscoveryServiceTest {

    private static final byte[] PDF = "%PDF-1.5 test document".getBytes();
    private static final ArxivPaper METADATA = new ArxivPaper(
            "2409.04701",
            "Retrieval Augmented Generation for Research",
            "We study how retrieval helps a research agent.",
            List.of("Ada Lovelace", "Alan Turing"),
            List.of("cs.CL"),
            2024,
            "https://arxiv.org/abs/2409.04701",
            "https://arxiv.org/pdf/2409.04701"
    );

    private ArxivClient arxivClient;
    private PaperRepository paperRepository;
    private UploadService uploadService;
    private PaperSearchabilityService searchabilityService;
    private io.github.chzarles.paperloom.repository.PaperReadingModelRepository readingModelRepository;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaConfig kafkaConfig;
    private RedisTemplate<String, Object> redisTemplate;
    private ArxivDiscoveryService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        arxivClient = mock(ArxivClient.class);
        paperRepository = mock(PaperRepository.class);
        uploadService = mock(UploadService.class);
        searchabilityService = mock(PaperSearchabilityService.class);
        readingModelRepository = mock(io.github.chzarles.paperloom.repository.PaperReadingModelRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        kafkaConfig = mock(KafkaConfig.class);
        redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(kafkaConfig.getPaperProcessingTopic()).thenReturn("paper-processing");
        when(paperRepository.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service = new ArxivDiscoveryService(
                arxivClient,
                paperRepository,
                uploadService,
                searchabilityService,
                readingModelRepository,
                kafkaTemplate,
                kafkaConfig,
                redisTemplate
        );
        ReflectionTestUtils.setField(service, "importsPerHour", 20);
    }

    @Test
    void importStoresPdfWithContentHashAndQueuesParse() throws Exception {
        String expectedPaperId = DigestUtils.md5Hex(PDF);
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.empty());
        when(arxivClient.findById("2409.04701")).thenReturn(Optional.of(METADATA));
        when(arxivClient.downloadPdf("2409.04701")).thenReturn(PDF);
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(expectedPaperId, "u1"))
                .thenReturn(Optional.empty());

        ArxivImportResult result = service.importPaper("https://arxiv.org/abs/2409.04701v2", "u1");

        assertEquals(ArxivImportResult.STATUS_IMPORTED, result.status());
        assertEquals(expectedPaperId, result.paperId());
        verify(uploadService).storeMergedPdf(expectedPaperId, PDF);

        ArgumentCaptor<Paper> saved = ArgumentCaptor.forClass(Paper.class);
        verify(paperRepository).save(saved.capture());
        Paper paper = saved.getValue();
        assertEquals("u1", paper.getUserId());
        assertEquals("2409.04701.pdf", paper.getOriginalFilename());
        assertEquals("2409.04701", paper.getArxivId());
        assertEquals("Ada Lovelace, Alan Turing", paper.getAuthors());
        assertEquals(2024, paper.getPublicationYear());
        assertEquals(Paper.STATUS_COMPLETED, paper.getStatus());
        assertEquals(Paper.VECTORIZATION_STATUS_PROCESSING, paper.getVectorizationStatus());

        verify(kafkaTemplate).executeInTransaction(any());
    }

    @Test
    void retryWithChangedPdfCreatesSecondRecord() {
        String expectedPaperId = DigestUtils.md5Hex(PDF);
        Paper failed = paper("old-paper", "2409.04701", Paper.VECTORIZATION_STATUS_FAILED);
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.of(failed));
        when(arxivClient.findById("2409.04701")).thenReturn(Optional.of(METADATA));
        when(arxivClient.downloadPdf("2409.04701")).thenReturn(PDF);
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(expectedPaperId, "u1"))
                .thenReturn(Optional.empty());

        ArxivImportResult result = service.importPaper("2409.04701", "u1");

        assertEquals(ArxivImportResult.STATUS_IMPORTED, result.status());
        ArgumentCaptor<Paper> saved = ArgumentCaptor.forClass(Paper.class);
        verify(paperRepository).save(saved.capture());
        assertEquals(expectedPaperId, saved.getValue().getPaperId());
        verify(kafkaTemplate).executeInTransaction(any());
    }

    @Test
    void importProceedsWhenRedisIsUnavailable() {
        when(redisTemplate.opsForValue())
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"));
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.empty());
        when(arxivClient.findById("2409.04701")).thenReturn(Optional.of(METADATA));
        when(arxivClient.downloadPdf("2409.04701")).thenReturn(PDF);
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(DigestUtils.md5Hex(PDF), "u1"))
                .thenReturn(Optional.empty());

        ArxivImportResult result = service.importPaper("2409.04701", "u1");

        assertEquals(ArxivImportResult.STATUS_IMPORTED, result.status());
        verify(kafkaTemplate).executeInTransaction(any());
    }

    @Test
    void alreadySearchablePaperDoesNotDownloadOrRequeue() throws Exception {
        Paper existing = paper("p-existing", "2409.04701", Paper.VECTORIZATION_STATUS_COMPLETED);
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.of(existing));
        when(searchabilityService.isSearchable(existing)).thenReturn(true);

        ArxivImportResult result = service.importPaper("2409.04701", "u1");

        assertEquals(ArxivImportResult.STATUS_READY, result.status());
        verify(arxivClient, never()).downloadPdf(anyString());
        verify(uploadService, never()).storeMergedPdf(anyString(), any());
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }

    @Test
    void contentMatchBackfillsMissingArxivId() throws Exception {
        String expectedPaperId = DigestUtils.md5Hex(PDF);
        Paper manualUpload = paper(expectedPaperId, null, Paper.VECTORIZATION_STATUS_COMPLETED);
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.empty());
        when(arxivClient.findById("2409.04701")).thenReturn(Optional.of(METADATA));
        when(arxivClient.downloadPdf("2409.04701")).thenReturn(PDF);
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(expectedPaperId, "u1"))
                .thenReturn(Optional.of(manualUpload));
        when(searchabilityService.isSearchable(manualUpload)).thenReturn(true);

        ArxivImportResult result = service.importPaper("2409.04701", "u1");

        assertEquals(ArxivImportResult.STATUS_READY, result.status());
        assertEquals("2409.04701", manualUpload.getArxivId());
        verify(paperRepository).backfillArxivIdIfAbsent(expectedPaperId, "u1", "2409.04701");
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }

    @Test
    void importRetriesPaperWithMissingVectorizationStatus() throws Exception {
        String expectedPaperId = DigestUtils.md5Hex(PDF);
        Paper existing = paper(expectedPaperId, "2409.04701", null);
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.of(existing));
        when(arxivClient.findById("2409.04701")).thenReturn(Optional.of(METADATA));
        when(arxivClient.downloadPdf("2409.04701")).thenReturn(PDF);
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(expectedPaperId, "u1"))
                .thenReturn(Optional.of(existing));

        ArxivImportResult result = service.importPaper("2409.04701", "u1");

        assertEquals(ArxivImportResult.STATUS_IMPORTED, result.status());
        verify(paperRepository).save(existing);
        verify(kafkaTemplate).executeInTransaction(any());
    }

    @Test
    void searchTreatsCompletedButUnsearchablePaperAsRetryable() {
        when(arxivClient.search(eq("rag"), eq(0), eq(20), any()))
                .thenReturn(new ArxivAtomParser.ArxivFeed(List.of(METADATA), 1));
        Paper existing = paper("p1", "2409.04701", Paper.VECTORIZATION_STATUS_COMPLETED);
        when(paperRepository.findByUserIdAndArxivIdIn("u1", List.of("2409.04701")))
                .thenReturn(List.of(existing));
        when(searchabilityService.searchablePaperIds(List.of(existing))).thenReturn(Set.of());

        ArxivSearchPage page = service.search("rag", 0, 20, "relevance", "u1");

        assertEquals(ArxivSearchHit.LIBRARY_FAILED, page.items().get(0).libraryStatus());
    }

    @Test
    void searchTreatsMissingVectorizationStatusAsRetryable() {
        when(arxivClient.search(eq("rag"), eq(0), eq(20), any()))
                .thenReturn(new ArxivAtomParser.ArxivFeed(List.of(METADATA), 1));
        Paper existing = paper("p1", "2409.04701", null);
        when(paperRepository.findByUserIdAndArxivIdIn("u1", List.of("2409.04701")))
                .thenReturn(List.of(existing));
        when(searchabilityService.searchablePaperIds(List.of(existing))).thenReturn(Set.of());

        ArxivSearchPage page = service.search("rag", 0, 20, "relevance", "u1");

        assertEquals(ArxivSearchHit.LIBRARY_FAILED, page.items().get(0).libraryStatus());
    }

    @Test
    void readyPaperIsNotBlockedByImportBudget() {
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        when(valueOperations.get(anyString())).thenReturn(20);
        Paper existing = paper("p-existing", "2409.04701", Paper.VECTORIZATION_STATUS_COMPLETED);
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.of(existing));
        when(searchabilityService.isSearchable(existing)).thenReturn(true);

        ArxivImportResult result = service.importPaper("2409.04701", "u1");

        assertEquals(ArxivImportResult.STATUS_READY, result.status());
    }

    @Test
    void concurrentDuplicateImportReportsProcessingInsteadOfFailing() throws Exception {
        String expectedPaperId = DigestUtils.md5Hex(PDF);
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.empty());
        when(arxivClient.findById("2409.04701")).thenReturn(Optional.of(METADATA));
        when(arxivClient.downloadPdf("2409.04701")).thenReturn(PDF);
        Paper concurrent = paper(expectedPaperId, "2409.04701", Paper.VECTORIZATION_STATUS_PROCESSING);
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(expectedPaperId, "u1"))
                .thenReturn(Optional.empty(), Optional.of(concurrent));
        when(paperRepository.save(any(Paper.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        when(searchabilityService.isSearchable(concurrent)).thenReturn(false);

        ArxivImportResult result = service.importPaper("2409.04701", "u1");

        assertEquals(ArxivImportResult.STATUS_PROCESSING, result.status());
        assertEquals(expectedPaperId, result.paperId());
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }

    @Test
    void paperAlreadyBeingParsedReportsProcessingInsteadOfDuplicatingWork() throws Exception {
        Paper existing = paper("p-existing", "2409.04701", Paper.VECTORIZATION_STATUS_MINERU_RUNNING);
        when(paperRepository.findFirstByUserIdAndArxivIdOrderByCreatedAtDesc("u1", "2409.04701"))
                .thenReturn(Optional.of(existing));
        when(searchabilityService.isSearchable(existing)).thenReturn(false);

        ArxivImportResult result = service.importPaper("2409.04701", "u1");

        assertEquals(ArxivImportResult.STATUS_PROCESSING, result.status());
        verify(arxivClient, never()).downloadPdf(anyString());
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }

    @Test
    void importBudgetIsEnforcedPerUser() {
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        when(valueOperations.get(anyString())).thenReturn(20);

        CustomException error = assertThrows(CustomException.class, () -> service.importPaper("2409.04701", "u1"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        verify(arxivClient, never()).findById(anyString());
    }

    @Test
    void invalidIdentifierIsRejectedBeforeAnyNetworkCall() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importPaper("", "u1"));

        assertTrue(error.getMessage().contains("无法识别"));
        verify(arxivClient, never()).findById(anyString());
    }

    @Test
    void searchClampsLimitAndMarksOwnedPapers() {
        when(arxivClient.search(eq("rag"), eq(0), eq(50), eq(ArxivClient.ArxivSortOrder.RELEVANCE)))
                .thenReturn(new ArxivAtomParser.ArxivFeed(List.of(METADATA), 120));
        Paper owned = paper("p-owned", "2409.04701", Paper.VECTORIZATION_STATUS_COMPLETED);
        when(paperRepository.findByUserIdAndArxivIdIn("u1", List.of("2409.04701")))
                .thenReturn(List.of(owned));
        when(searchabilityService.searchablePaperIds(List.of(owned))).thenReturn(java.util.Set.of("p-owned"));

        ArxivSearchPage page = service.search("rag", 0, 500, null, "u1");

        assertEquals(1, page.items().size());
        assertEquals(ArxivSearchHit.LIBRARY_READY, page.items().get(0).libraryStatus());
        assertEquals(120, page.total());
        assertTrue(page.hasNext());
    }

    @Test
    void searchPassesOffsetThroughAndStopsAtTheLastPage() {
        when(arxivClient.search(eq("rag"), eq(100), eq(20), eq(ArxivClient.ArxivSortOrder.SUBMITTED_DATE)))
                .thenReturn(new ArxivAtomParser.ArxivFeed(List.of(), 100));

        ArxivSearchPage page = service.search("rag", 100, 20, "submitted", "u1");

        assertEquals(100, page.start());
        assertFalse(page.hasNext());
    }

    private Paper paper(String paperId, String arxivId, String vectorizationStatus) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setArxivId(arxivId);
        paper.setUserId("u1");
        paper.setPaperTitle("Existing paper");
        paper.setVectorizationStatus(vectorizationStatus);
        paper.setStatus(Paper.STATUS_COMPLETED);
        return paper;
    }
}
