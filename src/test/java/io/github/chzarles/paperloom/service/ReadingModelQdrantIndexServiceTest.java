package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReadingModelQdrantIndexServiceTest {

    @Test
    void pointIdIsStableAndModelVersionSpecific() {
        String first = ReadingModelQdrantIndexService.stablePointId("paper-a", "rm-1", "location-a");
        String replay = ReadingModelQdrantIndexService.stablePointId("paper-a", "rm-1", "location-a");
        String newModel = ReadingModelQdrantIndexService.stablePointId("paper-a", "rm-2", "location-a");

        assertEquals(first, replay);
        assertNotEquals(first, newModel);
    }

    @Test
    void sparseVectorIsDeterministicAndKeepsTermFrequency() {
        QdrantSparseVector first = ReadingModelQdrantIndexService.sparseVector("attention attention transformer");
        QdrantSparseVector replay = ReadingModelQdrantIndexService.sparseVector("attention attention transformer");

        assertEquals(first, replay);
        assertEquals(2, first.indices().size());
        assertFalse(first.values().stream().allMatch(value -> value == 1.0f));
    }
}
