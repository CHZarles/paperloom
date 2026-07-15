package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.client.EmbeddingClient;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CorpusRetrievalServiceTest {

    @Test
    void locationSearchRejectsPaperOutsideLockedScopeBeforeRetrieval() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);

        assertThrows(IllegalArgumentException.class, () -> service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L,
                        List.of("paper-a"),
                        List.of("paper-b"),
                        "query",
                        "",
                        List.of(),
                        null,
                        null,
                        8
                )
        ));

        verifyNoInteractions(paperService, embeddingClient, qdrantClient, readService);
    }
}
