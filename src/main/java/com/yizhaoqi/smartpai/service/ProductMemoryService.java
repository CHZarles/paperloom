package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ConversationSession;
import com.yizhaoqi.smartpai.repository.ConversationSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductMemoryService {

    private final ConversationSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final LlmProviderRouter llmProviderRouter;

    public ProductMemoryService(ConversationSessionRepository sessionRepository,
                                ObjectMapper objectMapper) {
        this(sessionRepository, objectMapper, null);
    }

    @Autowired
    public ProductMemoryService(ConversationSessionRepository sessionRepository,
                                ObjectMapper objectMapper,
                                LlmProviderRouter llmProviderRouter) {
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.llmProviderRouter = llmProviderRouter;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadMemory(Long userId, String conversationId) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        String json = session.getConversationMemoryJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> memory = objectMapper.readValue(
                    json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            return sanitizeMemory(memory);
        } catch (Exception exception) {
            throw new CustomException("Conversation memory is invalid", HttpStatus.CONFLICT);
        }
    }

    @Transactional
    public void saveMemory(Long userId, String conversationId, Map<String, Object> memory) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        try {
            session.setConversationMemoryJson(objectMapper.writeValueAsString(sanitizeMemory(memory)));
        } catch (Exception exception) {
            throw new CustomException("Conversation memory serialization failed", HttpStatus.BAD_REQUEST);
        }
        sessionRepository.save(session);
    }

    @Transactional
    public MemoryUpdateResult updateMemory(Long userId,
                                           String conversationId,
                                           Map<String, Object> previousMemory,
                                           String userMessage,
                                           ProductTurnResult turnResult,
                                           SourceScope lockedScope) {
        if (llmProviderRouter == null) {
            return new MemoryUpdateResult(false, sanitizeMemory(previousMemory),
                    Map.of(), "memory_llm_unavailable");
        }
        List<Map<String, Object>> messages = memoryMessages(previousMemory, userMessage, turnResult, lockedScope);
        LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                String.valueOf(userId),
                messages,
                List.of(),
                900
        );
        Map<String, Object> memory = parseMemory(turn.content());
        saveMemory(userId, conversationId, memory);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("purpose", "MEMORY_COMPRESSION");
        trace.put("promptMessagesJson", messages);
        trace.put("rawResponseJson", turn.assistantMessage());
        trace.put("finishReason", turn.finishReason());
        trace.put("promptTokens", turn.promptTokens());
        trace.put("completionTokens", turn.completionTokens());
        return new MemoryUpdateResult(true, memory, trace, "");
    }

    private List<Map<String, Object>> memoryMessages(Map<String, Object> previousMemory,
                                                     String userMessage,
                                                     ProductTurnResult turnResult,
                                                     SourceScope lockedScope) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", """
                Update PaperLoom conversation memory.
                Return only valid JSON matching this schema:
                {
                  "userGoals": [],
                  "confirmedConstraints": [],
                  "openQuestions": [],
                  "papersDiscussed": [],
                  "referencesDiscussed": [],
                  "decisions": [],
                  "failedAttempts": [],
                  "sessionScope": {"scopeSnapshotId": "", "immutable": true}
                }
                Memory is non-authoritative and must not add paper facts that were not in the answer envelope or tool summary.
                Memory must not invent or preserve raw paper identifiers. Do not output fields named id or paperId for papers.
                Only output a paperRef when it was returned by PaperLoom references/tool results and starts with paper_.
                Never convert filenames, arXiv-like ids, DOI values, titles, or raw internal ids into paperRef.
                """));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousMemory", sanitizeMemory(previousMemory));
        payload.put("currentUserMessage", userMessage == null ? "" : userMessage);
        payload.put("answerEnvelope", turnResult == null ? Map.of() : turnResult.envelope());
        payload.put("toolProgress", turnResult == null ? List.of() : turnResult.progressEvents());
        payload.put("references", turnResult == null ? List.of() : turnResult.references());
        payload.put("scopeSnapshotId", scopeSnapshotId(lockedScope));
        payload.put("scopeImmutable", true);
        messages.add(message("user", writeJson(payload)));
        return messages;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private Map<String, Object> parseMemory(String rawContent) {
        try {
            Map<String, Object> memory = objectMapper.readValue(
                    extractJson(rawContent),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            memory = sanitizeMemory(memory);
            validateMemory(memory);
            return memory;
        } catch (Exception exception) {
            throw new CustomException("Conversation memory update failed", HttpStatus.BAD_GATEWAY);
        }
    }

    private void validateMemory(Map<String, Object> memory) {
        if (memory == null || memory.isEmpty()) {
            throw new IllegalArgumentException("empty memory");
        }
        for (String key : List.of(
                "userGoals",
                "confirmedConstraints",
                "openQuestions",
                "papersDiscussed",
                "referencesDiscussed",
                "decisions",
                "failedAttempts",
                "sessionScope"
        )) {
            if (!memory.containsKey(key)) {
                throw new IllegalArgumentException("missing memory key: " + key);
            }
        }
    }

    private Map<String, Object> sanitizeMemory(Map<String, Object> memory) {
        Object sanitized = sanitizeMemoryValue(memory == null ? Map.of() : memory);
        if (sanitized instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private Object sanitizeMemoryValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object nestedValue = entry.getValue();
                if (shouldDropMemoryIdentityField(key, nestedValue)) {
                    continue;
                }
                sanitized.put(key, sanitizeMemoryValue(nestedValue));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::sanitizeMemoryValue)
                    .toList();
        }
        return value;
    }

    private boolean shouldDropMemoryIdentityField(String key, Object value) {
        if ("id".equals(key) || "paperId".equals(key)) {
            return true;
        }
        return "paperRef".equals(key) && !isOpaquePaperRef(value);
    }

    private boolean isOpaquePaperRef(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.startsWith("paper_");
    }

    private String scopeSnapshotId(SourceScope scope) {
        SourceScope safeScope = scope == null ? SourceScope.auto() : scope;
        return safeScope.mode().name() + ":" + safeScope.paperIds().hashCode();
    }

    private String extractJson(String rawContent) {
        String text = rawContent == null ? "" : rawContent.trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private ConversationSession requireOwnedSession(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            throw new CustomException("Conversation not found", HttpStatus.NOT_FOUND);
        }
        return sessionRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new CustomException("Conversation not found", HttpStatus.NOT_FOUND));
    }

    public record MemoryUpdateResult(
            boolean success,
            Map<String, Object> memory,
            Map<String, Object> trace,
            String error
    ) {
        public MemoryUpdateResult {
            memory = memory == null ? Map.of() : Map.copyOf(memory);
            trace = trace == null ? Map.of() : Map.copyOf(trace);
            error = error == null ? "" : error.trim();
        }
    }
}
