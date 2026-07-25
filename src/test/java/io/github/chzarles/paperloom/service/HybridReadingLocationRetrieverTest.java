package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.service.RankedLocationCandidate;
import io.github.chzarles.paperloom.service.fusion.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridReadingLocationRetrieverTest {

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
