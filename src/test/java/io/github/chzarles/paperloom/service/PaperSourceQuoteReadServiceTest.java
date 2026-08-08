package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperSourceQuote;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperSourceQuoteRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperSourceQuoteReadServiceTest {

    @Test
    void crossPagePassageCreatesOnePageScopedQuotePerPage() {
        PaperLocationRepository locations = mock(PaperLocationRepository.class);
        PaperSourceQuoteRepository quotes = mock(PaperSourceQuoteRepository.class);
        PaperReadingElementRepository elements = mock(PaperReadingElementRepository.class);
        PaperSourceQuoteReadService service = new PaperSourceQuoteReadService(
                quotes, locations, elements, new ObjectMapper());
        PaperLocation passage = new PaperLocation();
        passage.setPaperId("paper-a");
        passage.setModelVersion("rm-1");
        passage.setLocationRef("passage_ref_a");
        passage.setLocationType(PaperLocationType.PASSAGE);
        passage.setPageNumber(1);
        passage.setPageEndNumber(2);
        passage.setSectionTitle("Methods");
        passage.setContentKind("PASSAGE_TEXT");
        passage.setSourceSpanJson("""
                {"spans":[
                  {"pageNumber":1,"readingOrder":1,"char_from":0,"char_to":4,
                   "content_char_from":0,"content_char_to":4,"elementSourceSpan":{"bbox":{"x":1}}},
                  {"pageNumber":2,"readingOrder":2,"char_from":0,"char_to":4,
                   "content_char_from":6,"content_char_to":10,"elementSourceSpan":{"bbox":{"x":2}}}
                ]}
                """);
        when(locations.findByLocationRefIn(List.of("passage_ref_a"))).thenReturn(List.of(passage));
        when(quotes.findFirstByPaperIdAndModelVersionAndLocationRefAndSplitPolicyVersionAndSplitIndexAndContentHash(
                anyString(), anyString(), anyString(), anyString(), anyInt(), anyString())).thenReturn(Optional.empty());
        when(quotes.save(any(PaperSourceQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperSourceQuoteReadService.ReadResult result = service.createQuotes(
                new CanonicalReadingLocationService.ReadBatch(List.of(
                        new CanonicalReadingLocationService.CanonicalLocation(
                                "paper-a", "Paper A", "rm-1", "passage_ref_a", "passage", 1, 2, "Methods",
                                "One.\n\nTwo.", "", "mineru", "1", "passage_ref_a",
                                false, false, false, false, List.of())
                ), List.of()));

        assertEquals(1, result.items().size());
        List<PaperSourceQuoteReadService.SourceQuote> resultQuotes = result.items().get(0).sourceQuotes();
        assertEquals(2, resultQuotes.size());
        assertEquals("One.", resultQuotes.get(0).content());
        assertEquals("Two.", resultQuotes.get(1).content());
        assertEquals(1, resultQuotes.get(0).pageNumber());
        assertEquals(2, resultQuotes.get(1).pageNumber());
        assertTrue(resultQuotes.stream().allMatch(quote -> quote.sourceQuoteRef().startsWith("source_quote_")));
    }
}
