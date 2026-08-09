package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.exception.QuotaExceededException;
import io.github.chzarles.paperloom.service.embedding.EmbeddingProvider;
import io.github.chzarles.paperloom.service.embedding.EmbeddingProviderFactory;
import io.github.chzarles.paperloom.service.RankedLocationCandidate;
import io.github.chzarles.paperloom.service.fusion.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridReadingLocationRetrieverTest {

    @Test
    void chargesSuccessfulDenseQueriesAndDoesNotBypassQuotaExhaustion() {
        QdrantReadingLocationRetriever sparse = mock(QdrantReadingLocationRetriever.class);
        EmbeddingProviderFactory providers = mock(EmbeddingProviderFactory.class);
        EmbeddingProvider provider = mock(EmbeddingProvider.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        UsageQuotaService quota = mock(UsageQuotaService.class);
        RetrievalCandidates sparseResult = new RetrievalCandidates(List.of(), 0, "index-v1");
        UsageQuotaService.TokenReservation reservation = new UsageQuotaService.TokenReservation(
                "embedding", "user-1", "", "", 8, 8, 0, false, true);
        LocationRetrievalRequest request = new LocationRetrievalRequest(
                "user-1", Map.of("paper-a", "rm-1"), "中文问题", "", Set.of(), null, null, 10);

        when(sparse.retrieve(request)).thenReturn(sparseResult);
        when(qdrant.isHybridContract()).thenReturn(true);
        when(providers.activeProvider()).thenReturn(provider);
        when(quota.reserveEmbeddingTokens("user-1", List.of("中文问题"))).thenReturn(reservation);
        when(quota.estimateEmbeddingTokens(List.of("中文问题"))).thenReturn(8);
        when(provider.embedQueries(List.of("中文问题"))).thenReturn(Mono.just(List.of(new float[]{1.0f})));
        when(qdrant.filter(Map.of("paper-a", "rm-1"), null, null, Set.of())).thenReturn(Map.of());
        when(qdrant.searchDense(any(float[].class), eq(Map.of()), eq(100))).thenReturn(List.of());

        assertEquals(sparseResult, new HybridReadingLocationRetriever(sparse, providers, qdrant, quota)
                .retrieve(request));
        verify(quota).settleReservation(reservation, 8);

        when(quota.reserveEmbeddingTokens("user-1", List.of("中文问题")))
                .thenThrow(new QuotaExceededException("no embedding quota", 0));
        assertThrows(QuotaExceededException.class, () ->
                new HybridReadingLocationRetriever(sparse, providers, qdrant, quota).retrieve(request));
    }

    @Test
    void fusesSparseAndDenseCandidatesByReciprocalRankFusion() {
        Map<String, Object> payloadA = Map.of("location_ref", "loc-A", "text", "A");
        Map<String, Object> payloadB = Map.of("location_ref", "loc-B", "text", "B");
        Map<String, Object> payloadC = Map.of("location_ref", "loc-C", "text", "C");
        Map<String, Object> payloadD = Map.of("location_ref", "loc-D", "text", "D");

        // Sparse: A (0.9), B (0.6), C (0.3) — sparse-only hits have no dense score.
        List<RankedLocationCandidate> sparse = List.of(
                new RankedLocationCandidate("loc-A", payloadA, 0.9, 0.0, 0.0),
                new RankedLocationCandidate("loc-B", payloadB, 0.6, 0.0, 0.0),
                new RankedLocationCandidate("loc-C", payloadC, 0.3, 0.0, 0.0)
        );
        // Dense: B (0.95), A (0.80), D (0.70) — C absent. Dense-only hits carry dense score only.
        List<RankedLocationCandidate> dense = List.of(
                new RankedLocationCandidate("loc-B", payloadB, 0.0, 0.95, 0.0),
                new RankedLocationCandidate("loc-A", payloadA, 0.0, 0.80, 0.0),
                new RankedLocationCandidate("loc-D", payloadD, 0.0, 0.70, 0.0)
        );

        List<String> sparseOrder = sparse.stream().map(RankedLocationCandidate::locationRef).toList();
        List<String> denseOrder = dense.stream().map(RankedLocationCandidate::locationRef).toList();

        ReciprocalRankFusion.RrfResult<String> fused = ReciprocalRankFusion.fuse(
                new ReciprocalRankFusion.RankedList<>("sparse", sparseOrder),
                new ReciprocalRankFusion.RankedList<>("dense", denseOrder)
        );
        List<String> ordered = fused.orderedItems();
        // A: rank 0 in sparse, rank 1 in dense  -> 1/60 + 1/61 = top
        // B: rank 1 in sparse, rank 0 in dense  -> 1/61 + 1/60 = same as A; stable order -> A first
        // D: rank 2 in dense, absent in sparse   -> 1/62
        // C: rank 2 in sparse, absent in dense   -> 1/62; insertion order: C added by sparse first
        assertEquals("loc-A", ordered.get(0));
        assertEquals("loc-B", ordered.get(1));
        assertTrue(ordered.contains("loc-D"));
        assertTrue(ordered.contains("loc-C"));
        assertTrue(ordered.indexOf("loc-C") < ordered.indexOf("loc-D"),
                "C inserted first should win tie");
    }
}
