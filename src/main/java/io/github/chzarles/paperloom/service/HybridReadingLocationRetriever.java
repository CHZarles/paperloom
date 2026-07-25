package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.service.embedding.EmbeddingProvider;
import io.github.chzarles.paperloom.service.embedding.EmbeddingProviderFactory;
import io.github.chzarles.paperloom.service.fusion.ReciprocalRankFusion;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Two-tower retriever: sparse (BM25) + dense (MiniMax embo-01) → Reciprocal Rank Fusion.
 * Falls back to sparse-only when either side is empty.
 */
@Service
public class HybridReadingLocationRetriever implements ReadingLocationRetriever {

    private final QdrantReadingLocationRetriever sparseRetriever;
    private final EmbeddingProviderFactory embeddingProviderFactory;
    private final QdrantClient qdrantClient;

    public HybridReadingLocationRetriever(QdrantReadingLocationRetriever sparseRetriever,
                                          EmbeddingProviderFactory embeddingProviderFactory,
                                          QdrantClient qdrantClient) {
        this.sparseRetriever = sparseRetriever;
        this.embeddingProviderFactory = embeddingProviderFactory;
        this.qdrantClient = qdrantClient;
    }

    @Override
    public RetrievalCandidates retrieve(LocationRetrievalRequest request) {
        RetrievalCandidates sparse = sparseRetriever.retrieve(request);
        if (!qdrantClient.isHybridContract()) {
            return sparse;
        }
        List<RankedLocationCandidate> sparseRanked = sparse.ranked();

        EmbeddingProvider provider;
        try {
            provider = embeddingProviderFactory.activeProvider();
        } catch (Exception e) {
            return sparse;
        }
        String query = (request.queryText() + " " + request.sectionQuery()).trim();
        if (query.isBlank()) {
            return sparse;
        }
        float[] denseVector;
        try {
            List<float[]> vectors = provider.embed(List.of(query)).block();
            denseVector = (vectors == null || vectors.isEmpty()) ? null : vectors.get(0);
        } catch (Exception e) {
            return sparse;
        }
        if (denseVector == null) {
            return sparse;
        }
        Map<String, Object> filter = qdrantClient.filter(
                request.activeModels(), request.pageFrom(), request.pageTo());
        List<RankedLocationCandidate> denseRanked;
        try {
            denseRanked = qdrantClient.searchDense(denseVector, filter, 100).stream()
                    .map(hit -> {
                        String locationRef = stringPayload(hit.payload(), "location_ref");
                        if (locationRef.isBlank()) {
                            return null;
                        }
                        return new RankedLocationCandidate(locationRef, hit.payload(), 0.0, hit.score(), hit.score());
                    })
                    .filter(c -> c != null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return sparse;
        }

        if (denseRanked.isEmpty()) {
            return sparse;
        }

        List<String> sparseOrder = sparseRanked.stream().map(RankedLocationCandidate::locationRef).toList();
        List<String> denseOrder = denseRanked.stream().map(RankedLocationCandidate::locationRef).toList();
        ReciprocalRankFusion.RrfResult<String> fused = ReciprocalRankFusion.fuse(
                new ReciprocalRankFusion.RankedList<>("sparse", sparseOrder),
                new ReciprocalRankFusion.RankedList<>("dense", denseOrder)
        );

        // Build per-locationRef sparse + dense score maps and emit fused candidates.
        Map<String, Double> sparseScores = new LinkedHashMap<>();
        for (RankedLocationCandidate c : sparseRanked) {
            sparseScores.putIfAbsent(c.locationRef(), c.lexicalScore());
        }
        Map<String, Double> denseScores = new LinkedHashMap<>();
        for (RankedLocationCandidate c : denseRanked) {
            denseScores.putIfAbsent(c.locationRef(), c.denseScore());
        }

        Map<String, RankedLocationCandidate> byRef = new LinkedHashMap<>();
        for (RankedLocationCandidate c : sparseRanked) byRef.put(c.locationRef(), c);
        for (RankedLocationCandidate c : denseRanked) byRef.putIfAbsent(c.locationRef(), c);

        List<RankedLocationCandidate> merged = fused.orderedItems().stream()
                .map(byRef::get)
                .filter(c -> c != null)
                .map(c -> new RankedLocationCandidate(
                        c.locationRef(),
                        c.payload(),
                        sparseScores.getOrDefault(c.locationRef(), 0.0),
                        denseScores.getOrDefault(c.locationRef(), 0.0),
                        fused.scores().getOrDefault(c.locationRef(), 0.0)
                ))
                .toList();
        return new RetrievalCandidates(merged, merged.size(), qdrantClient.indexVersion());
    }

    private String stringPayload(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
