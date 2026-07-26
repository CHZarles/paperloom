package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisResearchHarnessTransportTest {

    @Test
    void enqueuesJobAndCompletesFromResultEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        StreamOperations streamOperations = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(streamOperations.size("paperloom:research:harness:jobs")).thenReturn(0L);
        when(streamOperations.add(eq("paperloom:research:harness:jobs"), any(Map.class))).thenReturn(RecordId.of("1-0"));

        String eventKey = "paperloom:research:harness:events:generation-1";
        Map<String, String> event = Map.of(
                "type", "result",
                "payload_json", objectMapper.writeValueAsString(Map.of(
                        "status", "COMPLETED",
                        "answer", Map.of("markdown", "ok"),
                        "citations", List.of(),
                        "usage", Map.of("total_tokens", 9),
                        "trace", Map.of()
                ))
        );
        Map<String, String> progress = Map.of(
                "type", "calling_tool",
                "payload_json", objectMapper.writeValueAsString(Map.of(
                        "tool", "search_papers",
                        "status", "executing"
                ))
        );
        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(MapRecord.create(eventKey, progress).withId(RecordId.of("2-0"))))
                .thenReturn(List.of(MapRecord.create(eventKey, event).withId(RecordId.of("3-0"))));

        UsageQuotaService quotaService = mock(UsageQuotaService.class);
        UsageQuotaService.TokenReservation reservation = UsageQuotaService.TokenReservation.noop("llm", "7");
        when(quotaService.reserveLlmTokens(eq("7"), anyInt(), eq(3000))).thenReturn(reservation);
        RedisResearchHarnessTransport transport = new RedisResearchHarnessTransport(
                objectMapper,
                redisTemplate,
                quotaService,
                new ResearchHarnessPayloadFactory(),
                new ResearchHarnessResultMapper(objectMapper),
                "paperloom:research:harness:jobs",
                "paperloom:research:harness:status:",
                "paperloom:research:harness:events:",
                "paperloom:research:harness:cancel:",
                200,
                5,
                10,
                1800,
                1800
        );

        List<Map<String, Object>> progressEvents = new ArrayList<>();
        ProductTurnResult result = transport.submit(
                request(),
                progressEvents::add
        ).get(1, TimeUnit.SECONDS);

        assertEquals("ok", result.finalAnswerMarkdown());
        assertEquals("calling_tool", progressEvents.get(0).get("type"));
        assertEquals("search_papers", progressEvents.get(0).get("tool"));
        verify(streamOperations).add(eq("paperloom:research:harness:jobs"), any(Map.class));
        verify(quotaService).settleReservation(reservation, 9);
        verify(valueOperations).set(eq("paperloom:research:harness:status:generation-1"), anyString(), any(Duration.class));
    }

    @Test
    void cancelWritesRedisCancelKeyWithTtl() {
        ObjectMapper objectMapper = new ObjectMapper();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        StreamOperations streamOperations = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisResearchHarnessTransport transport = new RedisResearchHarnessTransport(
                objectMapper,
                redisTemplate,
                mock(UsageQuotaService.class),
                new ResearchHarnessPayloadFactory(),
                new ResearchHarnessResultMapper(objectMapper),
                "paperloom:research:harness:jobs",
                "paperloom:research:harness:status:",
                "paperloom:research:harness:events:",
                "paperloom:research:harness:cancel:",
                200,
                5,
                10,
                1800,
                1800
        );

        transport.cancel("generation-1");

        verify(valueOperations).set(
                eq("paperloom:research:harness:cancel:generation-1"),
                eq("1"),
                eq(Duration.ofSeconds(1800))
        );
    }

    @Test
    void completesExceptionallyFromErrorEventAndAbortsReservation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        StreamOperations streamOperations = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(streamOperations.size("paperloom:research:harness:jobs")).thenReturn(0L);
        when(streamOperations.add(eq("paperloom:research:harness:jobs"), any(Map.class))).thenReturn(RecordId.of("1-0"));
        String eventKey = "paperloom:research:harness:events:generation-1";
        Map<String, String> error = Map.of(
                "type", "error",
                "payload_json", objectMapper.writeValueAsString(Map.of(
                        "error_type", "HarnessError",
                        "message", "boom"
                ))
        );
        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(MapRecord.create(eventKey, error).withId(RecordId.of("2-0"))));
        UsageQuotaService quotaService = mock(UsageQuotaService.class);
        UsageQuotaService.TokenReservation reservation = UsageQuotaService.TokenReservation.noop("llm", "7");
        when(quotaService.reserveLlmTokens(eq("7"), anyInt(), eq(3000))).thenReturn(reservation);
        RedisResearchHarnessTransport transport = transport(objectMapper, redisTemplate, quotaService);

        assertThrows(Exception.class, () -> transport.submit(request(), event -> {}).get(1, TimeUnit.SECONDS));

        verify(quotaService).abortReservation(reservation);
    }

    @Test
    void rejectsWhenQueueDepthExceedsLimit() {
        ObjectMapper objectMapper = new ObjectMapper();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        StreamOperations streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size("paperloom:research:harness:jobs")).thenReturn(201L);
        UsageQuotaService quotaService = mock(UsageQuotaService.class);
        RedisResearchHarnessTransport transport = transport(objectMapper, redisTemplate, quotaService);

        assertThrows(IllegalStateException.class, () -> transport.submit(request(), event -> {}));
    }

    private ProductTurnRequest request() {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "question",
                SourceScope.manual(List.of("paper-1")),
                List.of(),
                Map.of(),
                ProductModelContext.defaults(),
                ignored -> {}
        );
    }

    private RedisResearchHarnessTransport transport(ObjectMapper objectMapper,
                                                    StringRedisTemplate redisTemplate,
                                                    UsageQuotaService quotaService) {
        return new RedisResearchHarnessTransport(
                objectMapper,
                redisTemplate,
                quotaService,
                new ResearchHarnessPayloadFactory(),
                new ResearchHarnessResultMapper(objectMapper),
                "paperloom:research:harness:jobs",
                "paperloom:research:harness:status:",
                "paperloom:research:harness:events:",
                "paperloom:research:harness:cancel:",
                200,
                5,
                10,
                1800,
                1800
        );
    }
}
