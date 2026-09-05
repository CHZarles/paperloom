package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivImportControllerTest {

    @Test
    void confirmedVersionIsQueuedAndDoesNotDownloadInTheRequest() {
        UserRepository users = mock(UserRepository.class);
        PaperRepository papers = mock(PaperRepository.class);
        PaperSearchabilityService searchability = mock(PaperSearchabilityService.class);
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        KafkaConfig kafkaConfig = mock(KafkaConfig.class);
        Authentication authentication = mock(Authentication.class);
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");

        when(authentication.getName()).thenReturn("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(papers.findFirstByArxivIdAndUserIdOrderByCreatedAtDesc("2401.01234v2", "7"))
                .thenReturn(Optional.empty());
        when(kafkaConfig.getPaperProcessingTopic()).thenReturn("paper-processing");
        when(kafka.executeInTransaction(any())).thenReturn(true);

        ArxivImportController controller = new ArxivImportController(
                users, papers, searchability, kafka, kafkaConfig);

        ResponseEntity<Map<String, Object>> response = controller.importPaper(
                authentication,
                new ArxivImportController.ArxivImportRequest("2401.01234v2", Map.of()));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("queued", response.getBody().get("status"));
        verify(kafka).executeInTransaction(any());
    }
}
