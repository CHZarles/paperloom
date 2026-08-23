package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarnessReadingServiceTest {

    private final PaperReadingService readingService = mock(PaperReadingService.class);
    private final PaperSourceQuoteReadService quoteService = mock(PaperSourceQuoteReadService.class);
    private final HarnessReadingService service = new HarnessReadingService(readingService, quoteService);

    @Test
    void resolverFailureDoesNotCreateSourceQuotes() {
        PaperReadingService.ContentReadQuery query =
                new PaperReadingService.ContentReadQuery(7L, List.of("paper-a"), List.of("missing-ref"));
        when(readingService.readPaperContent(query)).thenReturn(
                new PaperReadingService.ContentReadResult(List.of(), List.of("missing-ref")));

        PaperSourceQuoteReadService.ReadResult result = service.readAndCreateQuotes(query);

        assertEquals(List.of("missing-ref"), result.missingLocationRefs());
        verify(quoteService, never()).createQuotes(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void quoteMaterializationFailureEscapesTheTransactionalBoundary() throws Exception {
        PaperReadingService.ContentReadQuery query =
                new PaperReadingService.ContentReadQuery(7L, List.of("paper-a"), List.of("location-a"));
        CanonicalReadingLocationService.CanonicalLocation location =
                new CanonicalReadingLocationService.CanonicalLocation(
                        "paper-a", "Paper A", "rm-1", "location-a", "passage",
                        1, 1, "Methods", "content", "", "mineru", "1", "passage-a",
                        false, false, false, false, List.of());
        when(readingService.readPaperContent(query)).thenReturn(
                new PaperReadingService.ContentReadResult(List.of(location), List.of()));
        when(quoteService.createQuotes(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("quote persistence failed"));

        assertThrows(IllegalStateException.class, () -> service.readAndCreateQuotes(query));
        assertTrue(HarnessReadingService.class.getMethod(
                "readAndCreateQuotes", PaperReadingService.ContentReadQuery.class
        ).isAnnotationPresent(Transactional.class));
    }
}
