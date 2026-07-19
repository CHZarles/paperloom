package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.ConversationScopeMode;
import io.github.chzarles.paperloom.model.ConversationScopeStatus;
import io.github.chzarles.paperloom.model.ConversationSession;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.ConversationSessionRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@ActiveProfiles("test")
@Import({
        ConversationScopeService.class,
        ConversationScopeServiceIntegrationTest.TestConfig.class
})
class ConversationScopeServiceIntegrationTest {

    @Autowired
    private ConversationScopeService conversationScopeService;

    @Autowired
    private ConversationSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaperCollectionService paperCollectionService;

    @MockBean
    private PaperSearchabilityService paperSearchabilityService;

    @MockBean
    private PaperAccessService paperAccessService;

    @MockBean
    private PaperRepository paperRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void invalidSnapshotLockPersistsInvalidStatusAfterConflict() throws Exception {
        User user = new User();
        user.setUsername("scope-owner");
        user.setPassword("password");
        user.setRole(User.Role.USER);
        user.setPrimaryOrg("default");
        user.setOrgTags("default");
        User savedUser = userRepository.saveAndFlush(user);

        ConversationSession session = new ConversationSession();
        session.setUser(savedUser);
        session.setConversationId("invalid-snapshot-conversation");
        session.setTitle("Invalid snapshot");
        session.setScopeMode(ConversationScopeMode.SOURCE_SET_SNAPSHOT);
        session.setScopeStatus(ConversationScopeStatus.READY);
        session.setSourceLabel("Selected papers");
        session.setSourcePaperCount(1);
        session.setSourceSnapshotJson(objectMapper.writeValueAsString(Map.of(
                "paperIds", List.of("missing-paper"),
                "paperCount", 1
        )));
        sessionRepository.saveAndFlush(session);

        CustomException exception = assertThrows(CustomException.class,
                () -> conversationScopeService.lockForFirstMessage(savedUser.getId(), "invalid-snapshot-conversation"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());

        ConversationSession reloaded = sessionRepository
                .findByConversationIdAndUserId("invalid-snapshot-conversation", savedUser.getId())
                .orElseThrow();
        assertEquals(ConversationScopeStatus.INVALID, reloaded.getScopeStatus());
        assertEquals(false, reloaded.isScopeLocked());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
