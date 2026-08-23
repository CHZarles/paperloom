package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperSourceQuoteRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.CanonicalReadingLocationService;
import io.github.chzarles.paperloom.service.PaperAccessService;
import io.github.chzarles.paperloom.service.PaperReadingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@Import({ReadingQueryController.class, ReadingQueryPersistenceTest.TestConfig.class})
class ReadingQueryPersistenceTest {

    @Autowired
    private ReadingQueryController controller;

    @Autowired
    private PaperSourceQuoteRepository quoteRepository;

    @MockBean
    private PaperReadingService readingService;

    @MockBean
    private PaperAccessService paperAccessService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void publicExactReadCreatesNoSourceQuotes() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setRole(User.Role.USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(paperAccessService.canAccess("7", "paper-a")).thenReturn(true);
        when(readingService.readPaperContent(any())).thenReturn(new PaperReadingService.ContentReadResult(
                List.of(new CanonicalReadingLocationService.CanonicalLocation(
                        "paper-a", "Paper A", "rm-1", "location-a", "passage", 1, 1, "Methods",
                        "canonical content", "", "mineru", "1", "passage-a",
                        false, false, false, false, List.of())),
                List.of()));

        controller.readContent(
                new UsernamePasswordAuthenticationToken("alice", ""),
                "mcp-request-1",
                new ReadingQueryController.ContentReadRequest(
                        "paper-a", List.of("location-a"), Map.of()));

        assertEquals(0, quoteRepository.count());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
