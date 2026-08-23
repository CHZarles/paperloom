package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperSourceQuoteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@Import({
        HarnessReadingService.class,
        PaperSourceQuoteReadService.class,
        HarnessReadingServiceTest.TestConfig.class
})
class HarnessReadingServiceTest {

    @Autowired
    private HarnessReadingService service;

    @Autowired
    private PaperLocationRepository locationRepository;

    @Autowired
    private PaperSourceQuoteRepository quoteRepository;

    @MockBean
    private PaperReadingService readingService;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void quoteMaterializationFailureRollsBackTheWholeExactRead() {
        String validSpan = """
                {"spans":[{"pageNumber":1,"content_char_from":0,"content_char_to":5,"readingOrder":1}]}
                """.trim();
        locationRepository.saveAllAndFlush(List.of(
                location("valid-ref", validSpan),
                location("invalid-ref", "{")
        ));
        when(readingService.readPaperContent(any())).thenReturn(new PaperReadingService.ContentReadResult(
                List.of(canonical("valid-ref", "valid"), canonical("invalid-ref", "invalid")),
                List.of()
        ));

        assertThrows(IllegalArgumentException.class, () -> service.readAndCreateQuotes(
                new PaperReadingService.ContentReadQuery(
                        7L, List.of("paper-a"), List.of("valid-ref", "invalid-ref"))));

        assertEquals(0, quoteRepository.count());
    }

    private PaperLocation location(String ref, String sourceSpan) {
        PaperLocation location = new PaperLocation();
        location.setLocationRef(ref);
        location.setPaperId("paper-a");
        location.setModelVersion("rm-1");
        location.setLocationType(PaperLocationType.PASSAGE);
        location.setPageNumber(1);
        location.setSourceObjectId(ref);
        location.setSourceSpanJson(sourceSpan);
        location.setContentKind("PASSAGE");
        location.setUserId("7");
        return location;
    }

    private CanonicalReadingLocationService.CanonicalLocation canonical(String ref, String content) {
        return new CanonicalReadingLocationService.CanonicalLocation(
                "paper-a", "Paper A", "rm-1", ref, "passage", 1, 1, "Methods", content,
                "", "mineru", "1", ref, false, false, false, false, List.of());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
