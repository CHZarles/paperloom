package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperTextChunk;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorizationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private ElasticsearchService elasticsearchService;

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private PaperRepository paperRepository;

    @InjectMocks
    private VectorizationService vectorizationService;

    @Test
    void vectorizeIndexesRawChunkTextAndMetadataInjectedRetrievalText() {
        Paper paper = new Paper();
        paper.setPaperId("paper-a");
        paper.setPaperTitle("Post-hoc Hallucination Detection for RAG");
        paper.setOriginalFilename("post-hoc-hallucination.pdf");
        paper.setAbstractText("We study factuality checks for retrieval augmented generation.");

        PaperTextChunk chunk = new PaperTextChunk();
        chunk.setPaperId("paper-a");
        chunk.setChunkId(7);
        chunk.setTextContent("The detector compares generated claims against retrieved evidence.");
        chunk.setPageNumber(3);
        chunk.setElementType("PARAGRAPH");
        chunk.setSectionTitle("Experiments");
        chunk.setSectionLevel(2);
        chunk.setParserName("mineru");
        chunk.setParserVersion("1.0");
        chunk.setSourceKind("TEXT");
        chunk.setEvidenceRole("EXPERIMENT_RESULT");

        when(paperTextChunkRepository.findByPaperIdOrderByChunkIdAsc("paper-a"))
                .thenReturn(List.of(chunk));
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a"))
                .thenReturn(Optional.of(paper));
        when(paperRepository.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(embeddingClient.embedWithUsage(
                eq(List.of("The detector compares generated claims against retrieved evidence.")),
                eq("requester-1"),
                eq(EmbeddingClient.UsageType.UPLOAD)
        )).thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                List.of(new float[]{0.1f, 0.2f}),
                12,
                "dashscope:text-embedding-v4:2048"
        ));

        vectorizationService.vectorizeWithUsage("paper-a", "u1", "lab", true, "requester-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperChunkDocument>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndex(documentsCaptor.capture());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperSearchDocument>> paperDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndexPaperSearch(paperDocumentsCaptor.capture());

        JsonNode serialized = objectMapper.valueToTree(documentsCaptor.getValue().get(0));
        String retrievalText = serialized.path("retrievalTextContent").asText();
        JsonNode serializedPaper = objectMapper.valueToTree(paperDocumentsCaptor.getValue().get(0));
        String paperSearchText = serializedPaper.path("searchText").asText();

        assertAll(
                () -> assertEquals(
                        "The detector compares generated claims against retrieved evidence.",
                        serialized.path("textContent").asText()
                ),
                () -> assertTrue(retrievalText.contains("Post-hoc Hallucination Detection for RAG")),
                () -> assertTrue(retrievalText.contains("post-hoc-hallucination.pdf")),
                () -> assertTrue(retrievalText.contains("factuality checks")),
                () -> assertTrue(retrievalText.contains("Experiments")),
                () -> assertTrue(retrievalText.contains("TEXT")),
                () -> assertTrue(retrievalText.contains("EXPERIMENT_RESULT")),
                () -> assertTrue(retrievalText.contains("The detector compares generated claims against retrieved evidence.")),
                () -> assertEquals("paper-a", serializedPaper.path("paperId").asText()),
                () -> assertEquals("Post-hoc Hallucination Detection for RAG", serializedPaper.path("paperTitle").asText()),
                () -> assertTrue(paperSearchText.contains("title: Post-hoc Hallucination Detection for RAG")),
                () -> assertTrue(paperSearchText.contains("filename: post-hoc-hallucination.pdf")),
                () -> assertTrue(paperSearchText.contains("abstract: We study factuality checks"))
        );
    }
}
