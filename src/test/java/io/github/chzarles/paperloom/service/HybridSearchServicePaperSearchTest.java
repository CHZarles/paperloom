package io.github.chzarles.paperloom.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import io.github.chzarles.paperloom.client.EmbeddingClient;
import io.github.chzarles.paperloom.config.PaperSearchIndex;
import io.github.chzarles.paperloom.entity.PaperChunkDocument;
import io.github.chzarles.paperloom.entity.PaperSearchDocument;
import io.github.chzarles.paperloom.entity.SearchResult;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
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
    private PaperReadingModelRepository paperReadingModelRepository;

    @Mock
    private PaperReadingElementRepository paperReadingElementRepository;

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
    void paperCandidateSearchCarriesPdfReadinessOnly() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("eval-litsearch"));

        PaperSearchDocument source = new PaperSearchDocument();
        source.setPaperId("paper-123");
        source.setPaperTitle("Agent Benchmarks");
        source.setOriginalFilename("agent-benchmarks.pdf");
        source.setAbstractText("PDF paper about agents.");
        source.setSearchText("title: Agent Benchmarks\nabstract: PDF paper about agents.");
        source.setUserId("1");
        source.setOrgTag("team-a");
        source.setPublic(true);

        Paper paper = new Paper();
        paper.setPaperId("paper-123");
        paper.setOriginalFilename("agent-benchmarks.pdf");
        paper.setPaperTitle("Agent Benchmarks");

        SearchResponse<PaperSearchDocument> response = SearchResponse.of(search -> search
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(shards -> shards.total(1).successful(1).failed(0)))
                .hits(hits -> hits
                        .total(total -> total.value(1).relation(TotalHitsRelation.Eq))
                        .hits(List.of(Hit.of(hit -> hit
                                .index(PaperSearchIndex.PAPER_INDEX_NAME)
                                .id("paper-123")
                                .score(4.2d)
                                .source(source)
                        )))
                )
        );
        when(esClient.search(any(SearchRequest.class), eq(PaperSearchDocument.class))).thenReturn(response);
        when(paperRepository.findByPaperIdIn(List.of("paper-123"))).thenReturn(List.of(paper));

        HybridSearchService.AdaptiveSearchResult result = hybridSearchService.searchPaperCandidatesWithPermission(
                "agent benchmarks",
                "1",
                RetrievalBudget.forLibrarySearch(),
                List.of()
        );

        assertEquals(1, result.results().size());
        SearchResult candidate = result.results().get(0);
        assertEquals("PDF", candidate.getSourceType());
        assertEquals("PDF_PENDING_ASSETS", candidate.getEvidenceAssetLevel());
        assertEquals(false, candidate.getPdfEvidenceAvailable());
        assertEquals(List.of(), candidate.getAssetWarnings());
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

    @Test
    void tableEvidenceComesFromCurrentReadingElementInsteadOfLegacyTableStore() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("team-a"));

        Paper paper = new Paper();
        paper.setPaperId("paper-123");
        paper.setOriginalFilename("paper.pdf");
        paper.setPaperTitle("Reading Elements");
        when(paperRepository.findByPaperIdIn(List.of("paper-123"))).thenReturn(List.of(paper));

        PaperReadingModel currentModel = new PaperReadingModel();
        currentModel.setPaperId("paper-123");
        currentModel.setModelVersion("rm-1");
        when(paperReadingModelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-123"))
                .thenReturn(Optional.of(currentModel));

        PaperReadingElement table = new PaperReadingElement();
        table.setPaperId("paper-123");
        table.setModelVersion("rm-1");
        table.setReadingElementId("reading-table-1");
        table.setParserElementId("table-el-1");
        table.setSourceObjectId("table-1");
        table.setElementType("TABLE");
        table.setBodyText("Metric Value\nAccuracy 92");
        table.setStructuredPayloadJson("{\"tableMarkdown\":\"Metric | Value\\nAccuracy | 92\"}");
        when(paperReadingElementRepository.findByPaperIdAndModelVersionAndElementTypeInOrderByPageNumberAscReadingOrderAscIdAsc(
                "paper-123",
                "rm-1",
                List.of("TABLE")
        )).thenReturn(List.of(table));

        io.github.chzarles.paperloom.model.PaperVisualAsset tableCrop = new io.github.chzarles.paperloom.model.PaperVisualAsset();
        tableCrop.setPaperId("paper-123");
        tableCrop.setAssetStatus(io.github.chzarles.paperloom.model.PaperVisualAsset.STATUS_AVAILABLE);
        tableCrop.setReadingElementId("reading-table-1");
        tableCrop.setObjectKey("paper-assets/paper-123/tables/reading-table-1.png");
        when(paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                eq("paper-123"),
                eq(io.github.chzarles.paperloom.model.PaperVisualAsset.TYPE_TABLE_CROP),
                eq("reading-table-1")
        )).thenReturn(Optional.of(tableCrop));

        PaperChunkDocument chunk = new PaperChunkDocument(
                "chunk-1",
                "paper-123",
                1,
                "This table reports the model accuracy and related benchmark values.",
                3,
                "Table 1",
                "TABLE",
                "Results",
                1,
                null,
                "MinerU",
                "self-hosted",
                "TABLE",
                "table-1",
                null,
                "rm-1",
                "1",
                "team-a",
                false
        );
        when(esClient.search(any(SearchRequest.class), eq(PaperChunkDocument.class))).thenReturn(singleChunkResponse(chunk));

        HybridSearchService.AdaptiveSearchResult result = hybridSearchService.adaptiveSearchWithPermission(
                "model accuracy",
                "1",
                RetrievalBudget.forQa(),
                List.of("paper-123")
        );

        assertEquals(1, result.results().size());
        SearchResult match = result.results().get(0);
        assertEquals("Metric Value\nAccuracy 92", match.getTableText());
        assertEquals("Metric | Value\nAccuracy | 92", match.getTableMarkdown());
        assertEquals(true, match.getTableScreenshotAvailable());
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

    private SearchResponse<PaperChunkDocument> singleChunkResponse(PaperChunkDocument document) {
        return SearchResponse.of(search -> search
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(shards -> shards.total(1).successful(1).failed(0)))
                .hits(hits -> hits
                        .total(total -> total.value(1).relation(TotalHitsRelation.Eq))
                        .hits(List.of(Hit.of(hit -> hit
                                .index(PaperSearchIndex.INDEX_NAME)
                                .id(document.getId())
                                .score(4.2d)
                                .source(document)
                        )))
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
