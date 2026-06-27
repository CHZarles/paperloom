package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.config.PaperSearchIndex;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTableRepository;
import com.yizhaoqi.smartpai.repository.PaperVisualAssetRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridSearchServicePaperSearchTest {

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrgTagCacheService orgTagCacheService;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperTableRepository paperTableRepository;

    @Mock
    private PaperVisualAssetRepository paperVisualAssetRepository;

    @InjectMocks
    private HybridSearchService hybridSearchService;

    @Test
    void paperCandidateSearchQueriesDedicatedPaperSearchIndex() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("eval-litsearch"));

        PaperSearchDocument source = new PaperSearchDocument();
        source.setPaperId("paper-gold");
        source.setPaperTitle("Post-hoc Hallucination Detection");
        source.setOriginalFilename("post-hoc.pdf");
        source.setAbstractText("Detects hallucinations after generation.");
        source.setSearchText("title: Post-hoc Hallucination Detection\nabstract: Detects hallucinations after generation.");
        source.setUserId("1");
        source.setOrgTag("eval-litsearch");
        source.setPublic(false);

        SearchResponse<PaperSearchDocument> response = SearchResponse.of(search -> search
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(shards -> shards.total(1).successful(1).failed(0)))
                .hits(hits -> hits
                        .total(total -> total.value(1).relation(TotalHitsRelation.Eq))
                        .hits(List.of(Hit.of(hit -> hit
                                .index(PaperSearchIndex.PAPER_INDEX_NAME)
                                .id("paper-gold")
                                .score(4.2d)
                                .source(source)
                        )))
                )
        );
        when(esClient.search(any(SearchRequest.class), eq(PaperSearchDocument.class))).thenReturn(response);

        HybridSearchService.AdaptiveSearchResult result = hybridSearchService.searchPaperCandidatesWithPermission(
                "post-hoc hallucination detection",
                "1",
                RetrievalBudget.forLibrarySearch(),
                List.of()
        );

        assertEquals(1, result.results().size());
        SearchResult candidate = result.results().get(0);
        assertEquals("paper-gold", candidate.getPaperId());
        assertEquals(0, candidate.getChunkId());
        assertEquals("Post-hoc Hallucination Detection", candidate.getPaperTitle());
        assertTrue(candidate.getTextContent().contains("Detects hallucinations"));
        assertEquals("PAPER_METADATA", candidate.getRetrievalMode());

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(requestCaptor.capture(), eq(PaperSearchDocument.class));
        SearchRequest request = requestCaptor.getValue();
        assertEquals(List.of(PaperSearchIndex.PAPER_INDEX_NAME), request.index());
    }

    @Test
    void paperCandidateSearchCarriesStructuredImportReadiness() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("eval-litsearch"));

        PaperSearchDocument source = new PaperSearchDocument();
        source.setPaperId("litsearch:123");
        source.setPaperTitle("Agent Benchmarks");
        source.setOriginalFilename("litsearch:123.json");
        source.setAbstractText("Structured benchmark import about agents.");
        source.setSearchText("title: Agent Benchmarks\nabstract: Structured benchmark import about agents.");
        source.setUserId("1");
        source.setOrgTag("eval-litsearch");
        source.setPublic(true);

        Paper importedPaper = new Paper();
        importedPaper.setPaperId("litsearch:123");
        importedPaper.setOriginalFilename("litsearch:123.json");
        importedPaper.setPaperTitle("Agent Benchmarks");
        importedPaper.setSourceDataset("litsearch");
        importedPaper.setEval(true);

        SearchResponse<PaperSearchDocument> response = SearchResponse.of(search -> search
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(shards -> shards.total(1).successful(1).failed(0)))
                .hits(hits -> hits
                        .total(total -> total.value(1).relation(TotalHitsRelation.Eq))
                        .hits(List.of(Hit.of(hit -> hit
                                .index(PaperSearchIndex.PAPER_INDEX_NAME)
                                .id("litsearch:123")
                                .score(4.2d)
                                .source(source)
                        )))
                )
        );
        when(esClient.search(any(SearchRequest.class), eq(PaperSearchDocument.class))).thenReturn(response);
        when(paperRepository.findByPaperIdIn(List.of("litsearch:123"))).thenReturn(List.of(importedPaper));

        HybridSearchService.AdaptiveSearchResult result = hybridSearchService.searchPaperCandidatesWithPermission(
                "agent benchmarks",
                "1",
                RetrievalBudget.forLibrarySearch(),
                List.of()
        );

        assertEquals(1, result.results().size());
        SearchResult candidate = result.results().get(0);
        assertEquals("EVAL_IMPORT", candidate.getSourceType());
        assertEquals("TEXT_ONLY", candidate.getEvidenceAssetLevel());
        assertEquals(false, candidate.getPdfEvidenceAvailable());
        assertEquals(true, candidate.getStructuredImport());
        assertEquals(true, candidate.getEvalImport());
        assertEquals(List.of("structured_import_text_only"), candidate.getAssetWarnings());
    }

    @Test
    void semanticKnnSearchCarriesPermissionAndPaperScopeFilters() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("eval-litsearch"));
        when(embeddingClient.embed(
                eq(List.of("agent")),
                eq("1"),
                eq(EmbeddingClient.UsageType.QUERY),
                any(Duration.class)
        )).thenReturn(List.of(new float[] { 1.0f, 0.0f }));
        when(esClient.search(any(SearchRequest.class), eq(PaperChunkDocument.class))).thenReturn(emptyChunkResponse());

        hybridSearchService.adaptiveSearchWithPermission(
                "agent",
                "1",
                RetrievalBudget.forQa(),
                List.of("paper-a")
        );

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient, org.mockito.Mockito.times(2)).search(requestCaptor.capture(), eq(PaperChunkDocument.class));
        SearchRequest semanticRequest = requestCaptor.getAllValues().stream()
                .filter(request -> !request.knn().isEmpty())
                .findFirst()
                .orElseThrow();
        List<Query> knnFilters = semanticRequest.knn().get(0).filter();

        assertTrue(containsTermFilter(knnFilters, "paperId", "paper-a"));
        assertTrue(containsTermFilter(knnFilters, "userId", "1"));
        assertTrue(containsTermFilter(knnFilters, "orgTag", "eval-litsearch"));
    }

    @Test
    void multiPaperScopeUsesTermsFilter() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("eval-litsearch"));
        when(esClient.search(any(SearchRequest.class), eq(PaperSearchDocument.class))).thenReturn(emptyPaperSearchResponse());

        hybridSearchService.searchPaperCandidatesWithPermission(
                "retrieval augmented generation",
                "1",
                RetrievalBudget.forLibrarySearch(),
                List.of("paper-a", "paper-b")
        );

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(requestCaptor.capture(), eq(PaperSearchDocument.class));
        SearchRequest request = requestCaptor.getValue();

        assertTrue(containsTerms(request.query(), "paperId", List.of("paper-a", "paper-b")));
    }

    private SearchResponse<PaperSearchDocument> emptyPaperSearchResponse() {
        return SearchResponse.of(search -> search
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(shards -> shards.total(1).successful(1).failed(0)))
                .hits(hits -> hits
                        .total(total -> total.value(0).relation(TotalHitsRelation.Eq))
                        .hits(List.of())
                )
        );
    }

    private SearchResponse<PaperChunkDocument> emptyChunkResponse() {
        return SearchResponse.of(search -> search
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(shards -> shards.total(1).successful(1).failed(0)))
                .hits(hits -> hits
                        .total(total -> total.value(0).relation(TotalHitsRelation.Eq))
                        .hits(List.of())
                )
        );
    }

    private boolean containsTermFilter(List<Query> filters, String field, String expectedValue) {
        return filters.stream().anyMatch(query -> containsTerm(query, field, expectedValue));
    }

    private boolean containsTerm(Query query, String field, String expectedValue) {
        if (query == null) {
            return false;
        }
        if (query.isTerm()) {
            return field.equals(query.term().field()) && fieldValueEquals(query.term().value(), expectedValue);
        }
        if (query.isBool()) {
            return query.bool().filter().stream().anyMatch(inner -> containsTerm(inner, field, expectedValue))
                    || query.bool().must().stream().anyMatch(inner -> containsTerm(inner, field, expectedValue))
                    || query.bool().should().stream().anyMatch(inner -> containsTerm(inner, field, expectedValue))
                    || query.bool().mustNot().stream().anyMatch(inner -> containsTerm(inner, field, expectedValue));
        }
        return false;
    }

    private boolean containsTerms(Query query, String field, List<String> expectedValues) {
        if (query == null) {
            return false;
        }
        if (query.isTerms()) {
            List<FieldValue> values = query.terms().terms().value();
            if (!field.equals(query.terms().field()) || values.size() != expectedValues.size()) {
                return false;
            }
            for (int i = 0; i < values.size(); i++) {
                if (!fieldValueEquals(values.get(i), expectedValues.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (query.isBool()) {
            return query.bool().filter().stream().anyMatch(inner -> containsTerms(inner, field, expectedValues))
                    || query.bool().must().stream().anyMatch(inner -> containsTerms(inner, field, expectedValues))
                    || query.bool().should().stream().anyMatch(inner -> containsTerms(inner, field, expectedValues))
                    || query.bool().mustNot().stream().anyMatch(inner -> containsTerms(inner, field, expectedValues));
        }
        return false;
    }

    private boolean fieldValueEquals(FieldValue fieldValue, String expectedValue) {
        if (fieldValue == null) {
            return false;
        }
        if (fieldValue.isString()) {
            return expectedValue.equals(fieldValue.stringValue());
        }
        if (fieldValue.isLong()) {
            return expectedValue.equals(Long.toString(fieldValue.longValue()));
        }
        if (fieldValue.isBoolean()) {
            return expectedValue.equals(Boolean.toString(fieldValue.booleanValue()));
        }
        return expectedValue.equals(fieldValue._toJsonString());
    }
}
