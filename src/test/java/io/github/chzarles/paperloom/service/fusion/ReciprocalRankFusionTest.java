package io.github.chzarles.paperloom.service.fusion;

import io.github.chzarles.paperloom.service.fusion.ReciprocalRankFusion.RankedList;
import io.github.chzarles.paperloom.service.fusion.ReciprocalRankFusion.RrfResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReciprocalRankFusionTest {

    @Test
    void mergesTwoRankedListsByReciprocalRank() {
        // Two retrievers, k=60.
        // Item A: rank 0 in sparse, rank 1 in dense  -> 1/60 + 1/61
        // Item B: rank 1 in sparse, rank 0 in dense  -> 1/61 + 1/60  (tie with A)
        // Item C: rank 2 in sparse, absent in dense  -> 1/62
        // Expected order (stable): A/B (tied) before C. A wins on insertion order.
        List<String> sparse = List.of("A", "B", "C");
        List<String> dense = List.of("B", "A");
        RrfResult<String> fused = ReciprocalRankFusion.fuse(
                new RankedList<>("sparse", sparse),
                new RankedList<>("dense", dense)
        );

        List<String> ordered = fused.orderedItems();
        assertEquals("A", ordered.get(0));
        assertEquals("B", ordered.get(1));
        assertEquals("C", ordered.get(2));
    }

    @Test
    void emptyInputProducesEmptyResult() {
        RrfResult<String> fused = ReciprocalRankFusion.fuse();
        assertEquals(List.of(), fused.orderedItems());
    }

    @Test
    void singleSourcePassesThrough() {
        RrfResult<String> fused = ReciprocalRankFusion.fuse(
                new RankedList<>("sparse", List.of("X", "Y", "Z"))
        );
        assertEquals(List.of("X", "Y", "Z"), fused.orderedItems());
    }
}
