package io.github.chzarles.paperloom.service.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.DiscoveryChatMessage;
import io.github.chzarles.paperloom.repository.DiscoveryChatMessageRepository;
import io.github.chzarles.paperloom.repository.DiscoveryMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 候选移除的两条通道（候选栏 UI 的 removeCandidateCard 与 Runner 工具走的 removeCandidateCards）共用
 * {@code stripCardsFromRows}，必须把所有消息行里的同一 id 都摘干净，且 Runner 通道要把池外的 id 挡回。
 */
class DiscoveryChatCandidateRemovalTest {

    private static final String USER = "u1";
    private static final String TURN = "t1";
    private static final String KEEP = "2401.00002";
    private static final String DROP = "2401.00001";
    private static final String OUTSIDE = "9999.99999";

    private DiscoveryChatMessageRepository chatRepository;
    private DiscoveryChatService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatRepository = mock(DiscoveryChatMessageRepository.class);
        service = new DiscoveryChatService(
                chatRepository,
                mock(DiscoveryMemoryRepository.class),
                mock(ArxivDiscoveryService.class),
                mock(DiscoveryRunnerClient.class),
                mock(RedisTemplate.class),
                objectMapper);
    }

    @Test
    void runnerRemovalStripsTheIdFromEveryRowAndRejectsIdsOutsideThePool() throws Exception {
        DiscoveryChatMessage running = assistantRow(3L, TURN, DiscoveryChatMessage.TURN_STATUS_RUNNING);
        running.setCards(cardsJson(DROP, KEEP));
        DiscoveryChatMessage earlier = assistantRow(2L, "t0", DiscoveryChatMessage.TURN_STATUS_COMPLETED);
        earlier.setCards(cardsJson(DROP));
        List<DiscoveryChatMessage> rows = List.of(running, earlier);

        when(chatRepository.findByTurnId(TURN)).thenReturn(Optional.of(running));
        when(chatRepository.findTop500ByUserIdOrderByIdDesc(USER)).thenReturn(rows);
        when(chatRepository.save(any(DiscoveryChatMessage.class))).thenAnswer(call -> call.getArgument(0));

        Map<String, Object> result = service.removeCandidateCards(TURN, USER, List.of(DROP, OUTSIDE));

        assertEquals(List.of(DROP), result.get("removed"));
        assertEquals(List.of(OUTSIDE), result.get("rejected"));
        assertEquals(2, result.get("removedRows"), "两行都含有该 id，应当都被改写");
        assertFalse(running.getCards().contains(DROP), "运行中的行不应再持有被移除的候选");
        assertFalse(earlier.getCards().contains(DROP), "历史行不应再持有被移除的候选");
        assertTrue(running.getCards().contains(KEEP), "同一行的其他候选必须保留");
    }

    @Test
    void runnerRemovalRejectsEverythingWhenTheTurnIsNotRunning() throws Exception {
        DiscoveryChatMessage done = assistantRow(3L, TURN, DiscoveryChatMessage.TURN_STATUS_COMPLETED);
        done.setCards(cardsJson(DROP));
        when(chatRepository.findByTurnId(TURN)).thenReturn(Optional.of(done));

        CustomExceptionFailure failure = catchFailure(() -> service.removeCandidateCards(TURN, USER, List.of(DROP)));

        assertTrue(failure.thrown(), "终态 turn 上的移除应被拒绝");
        assertTrue(done.getCards().contains(DROP), "被拒绝时不得改动候选");
    }

    // ------------------------------------------------------------ helpers

    private DiscoveryChatMessage assistantRow(Long id, String turnId, String status) {
        DiscoveryChatMessage row = new DiscoveryChatMessage();
        row.setId(id);
        row.setUserId(USER);
        row.setRole(DiscoveryChatMessage.ROLE_ASSISTANT);
        row.setTurnId(turnId);
        row.setTurnStatus(status);
        row.setContent("");
        return row;
    }

    private String cardsJson(String... arxivIds) throws Exception {
        List<Map<String, String>> cards = java.util.Arrays.stream(arxivIds)
                .map(id -> Map.of("arxivId", id, "title", "t-" + id))
                .toList();
        return objectMapper.writeValueAsString(cards);
    }

    private CustomExceptionFailure catchFailure(Runnable action) {
        try {
            action.run();
            return new CustomExceptionFailure(null);
        } catch (RuntimeException e) {
            return new CustomExceptionFailure(e);
        }
    }

    private record CustomExceptionFailure(RuntimeException error) {
        boolean thrown() {
            return error instanceof io.github.chzarles.paperloom.exception.CustomException;
        }
    }
}
