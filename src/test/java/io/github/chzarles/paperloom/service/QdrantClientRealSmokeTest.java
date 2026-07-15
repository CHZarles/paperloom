package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.config.QdrantProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "QDRANT_REAL_SMOKE_URL", matches = ".+")
class QdrantClientRealSmokeTest {

    @Test
    void authenticatedCollectionKeepsActivePaperGenerationPairsExact() {
        String collection = requiredEnvironment("QDRANT_REAL_SMOKE_COLLECTION");
        QdrantClient client = client(collection, requiredEnvironment("QDRANT_REAL_SMOKE_API_KEY"));

        client.ensureCollection(3);
        client.upsert(List.of(
                point("10000000-0000-0000-0000-000000000001", "paper-a", "generation-a", "a-active"),
                point("10000000-0000-0000-0000-000000000002", "paper-a", "generation-b", "a-stale"),
                point("10000000-0000-0000-0000-000000000003", "paper-b", "generation-b", "b-active"),
                point("10000000-0000-0000-0000-000000000004", "paper-b", "generation-a", "b-stale")
        ));

        List<QdrantSearchHit> hits = client.searchDense(
                new float[]{1.0f, 0.0f, 0.0f},
                client.filter(Map.of(
                        "paper-a", "generation-a",
                        "paper-b", "generation-b"
                ), null, null),
                10
        );

        assertEquals(Set.of("a-active", "b-active"), hits.stream()
                .map(hit -> hit.payload().get("location_ref").toString())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("paper-a", "paper-b"), client.indexedPaperIds(10));

        client.deleteByPaperIdAndGeneration("paper-a", "generation-b");
        assertEquals(1, client.countByPaperId("paper-a"));
        assertEquals(1, client.countByPaperIdAndGeneration("paper-a", "generation-a"));

        IllegalStateException dimensionMismatch = assertThrows(
                IllegalStateException.class,
                () -> client.verifyCollection(4)
        );
        assertTrue(dimensionMismatch.getMessage().contains("dense dimension"));

        QdrantClient missingCollection = client(collection + "_missing", requiredEnvironment("QDRANT_REAL_SMOKE_API_KEY"));
        assertTrue(assertThrows(IllegalStateException.class, () -> missingCollection.verifyCollection(3))
                .getMessage().contains("missing"));

        QdrantClient wrongKey = client(collection, "wrong-key");
        assertTrue(assertThrows(IllegalStateException.class, () -> wrongKey.verifyCollection(3))
                .getMessage().contains("HTTP 401"));
    }

    private QdrantClient client(String collection, String apiKey) {
        QdrantProperties properties = new QdrantProperties();
        properties.setBaseUrl(requiredEnvironment("QDRANT_REAL_SMOKE_URL"));
        properties.setApiKey(apiKey);
        properties.setCollection(collection);
        return new QdrantClient(new ObjectMapper(), properties);
    }

    private QdrantPoint point(String id, String paperId, String generation, String locationRef) {
        return new QdrantPoint(
                id,
                new float[]{1.0f, 0.0f, 0.0f},
                new QdrantSparseVector(List.of(7), List.of(1.0f)),
                Map.of(
                        "paper_id", paperId,
                        "model_version", "rm-1",
                        "index_generation", generation,
                        "location_ref", locationRef,
                        "element_types", List.of("paragraph"),
                        "page_number", 1
                )
        );
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the real Qdrant smoke");
        }
        return value.trim();
    }
}
