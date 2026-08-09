package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperSourceQuote;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
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
        PaperSourceQuoteReadService service = new PaperSourceQuoteReadService(
                quotes, locations, new ObjectMapper());
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

    @Test
    void crossPageSectionUsesPersistedContentSpansInsteadOfElementText() throws Exception {
        PaperLocationRepository locations = mock(PaperLocationRepository.class);
        PaperSourceQuoteRepository quotes = mock(PaperSourceQuoteRepository.class);
        PaperSourceQuoteReadService service = new PaperSourceQuoteReadService(
                quotes, locations, new ObjectMapper());
        PaperLocation section = new PaperLocation();
        section.setPaperId("paper-a");
        section.setModelVersion("rm-1");
        section.setLocationRef("section_ref_a");
        section.setLocationType(PaperLocationType.SECTION);
        section.setPageNumber(1);
        section.setPageEndNumber(2);
        section.setSectionTitle("Methods");
        section.setContentKind("SECTION_TEXT");
        section.setSourceSpanJson("""
                {"spans":[
                  {"parserElementId":"heading","pageNumber":1,"readingOrder":1,
                   "content_char_from":0,"content_char_to":7,"elementSourceSpan":{"bbox":{"x":1}}},
                  {"parserElementId":"paragraph","pageNumber":1,"readingOrder":2,
                   "content_char_from":9,"content_char_to":21,"elementSourceSpan":{"bbox":{"x":1}}},
                  {"parserElementId":"table","pageNumber":2,"readingOrder":3,
                   "content_char_from":23,"content_char_to":38,
                   "elementSourceSpan":{"elementType":"TABLE","bbox":{"x":2}}}
                ]}
                """);
        when(locations.findByLocationRefIn(List.of("section_ref_a"))).thenReturn(List.of(section));
        when(quotes.findFirstByPaperIdAndModelVersionAndLocationRefAndSplitPolicyVersionAndSplitIndexAndContentHash(
                anyString(), anyString(), anyString(), anyString(), anyInt(), anyString())).thenReturn(Optional.empty());
        when(quotes.save(any(PaperSourceQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperSourceQuoteReadService.ReadResult result = service.createQuotes(
                new CanonicalReadingLocationService.ReadBatch(List.of(
                        new CanonicalReadingLocationService.CanonicalLocation(
                                "paper-a", "Paper A", "rm-1", "section_ref_a", "section", 1, 2, "Methods",
                                "Methods\n\nMethod text.\n\nraw table text.", "", "mineru", "1", "",
                                false, false, false, false, List.of())
                ), List.of()));

        List<PaperSourceQuoteReadService.SourceQuote> resultQuotes = result.items().get(0).sourceQuotes();
        assertEquals(2, resultQuotes.size());
        assertEquals(1, resultQuotes.get(0).pageNumber());
        assertEquals("SECTION_TEXT", resultQuotes.get(0).contentKind());
        assertEquals("Methods\n\nMethod text.", resultQuotes.get(0).content());
        assertEquals(2, resultQuotes.get(1).pageNumber());
        assertEquals("TABLE", resultQuotes.get(1).contentKind());
        assertEquals("raw table text.", resultQuotes.get(1).content());
        assertEquals(1, new ObjectMapper().readTree(resultQuotes.get(1).sourceSpanJson()).path("bbox").size());
    }
}
