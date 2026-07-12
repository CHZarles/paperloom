package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatGenerationStateService {

    private static final Logger logger = LoggerFactory.getLogger(ChatGenerationStateService.class);
    private static final Duration GENERATION_TTL = Duration.ofMinutes(30);
    private static final TypeReference<Map<String, Map<String, Object>>> REFERENCE_MAP_TYPE =
            new TypeReference<Map<String, Map<String, Object>>>() {};
    private static final TypeReference<Map<String, Object>> DIAGNOSTICS_MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};
    private static final TypeReference<Map<String, Object>> READING_MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatGenerationStateService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public GenerationSnapshot createGeneration(String userId, String conversationId, String question) {
        return createGeneration(userId, null, conversationId, question);
    }

    public GenerationSnapshot createGeneration(String userId, String clientId, String conversationId, String question) {
        String generationId = UUID.randomUUID().toString();
        String now = LocalDateTime.now().toString();
        GenerationMeta meta = new GenerationMeta(
                generationId,
                userId,
                conversationId,
                question,
                GenerationStatus.STREAMING,
                now,
                now,
                null,
                trimToNull(clientId),
                null
        );

        // 写入顺序：先把可读子项准备好，最后再发布 active key，避免读取者拿到 active key 后却查不到 meta/content。
        redisTemplate.delete(referenceKey(generationId));
        redisTemplate.delete(diagnosticsKey(generationId));
        redisTemplate.delete(readingArtifactsKey(generationId));
        redisTemplate.delete(readingStatePatchKey(generationId));
        redisTemplate.delete(progressEventsKey(generationId));
        redisTemplate.opsForValue().set(contentKey(generationId), "", GENERATION_TTL);
        writeMeta(meta);
        redisTemplate.opsForValue().set(activeGenerationKey(userId), generationId, GENERATION_TTL);
        if (meta.clientId() != null) {
            redisTemplate.opsForValue().set(activeGenerationKey(userId, meta.clientId()), generationId, GENERATION_TTL);
        }
        return toSnapshot(meta, "", Collections.emptyMap(), Collections.emptyMap());
    }

    public void appendChunk(String generationId, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            touch(generationId);
            return;
        }

        redisTemplate.opsForValue().append(contentKey(generationId), chunk);
        touch(generationId);
    }

    public void updateReferenceMappings(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        try {
            if (referenceMappings == null || referenceMappings.isEmpty()) {
                redisTemplate.delete(referenceKey(generationId));
            } else {
                redisTemplate.opsForValue().set(
                        referenceKey(generationId),
                        objectMapper.writeValueAsString(referenceMappings),
                        GENERATION_TTL
                );
            }
            touch(generationId);
        } catch (Exception e) {
            logger.warn("保存生成态引用映射失败: generationId={}", generationId, e);
        }
    }

    public void updateDiagnostics(String generationId, Map<String, Object> diagnostics) {
        try {
            if (diagnostics == null || diagnostics.isEmpty()) {
                redisTemplate.delete(diagnosticsKey(generationId));
            } else {
                redisTemplate.opsForValue().set(
                        diagnosticsKey(generationId),
                        objectMapper.writeValueAsString(diagnostics),
                        GENERATION_TTL
                );
            }
            touch(generationId);
        } catch (Exception e) {
            logger.warn("保存生成态诊断信息失败: generationId={}", generationId, e);
        }
    }

    public void updateReadingArtifacts(String generationId, Map<String, Object> readingArtifacts) {
        updateReadingMap(generationId, readingArtifactsKey(generationId), readingArtifacts, "reading artifacts");
    }

    public void updateReadingStatePatch(String generationId, Map<String, Object> readingStatePatch) {
        updateReadingMap(generationId, readingStatePatchKey(generationId), readingStatePatch, "reading state patch");
    }

    public void appendProgressEvent(String generationId, Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        try {
            redisTemplate.opsForList().rightPush(progressEventsKey(generationId), objectMapper.writeValueAsString(event));
            redisTemplate.expire(progressEventsKey(generationId), GENERATION_TTL);
            touch(generationId);
        } catch (Exception error) {
            logger.warn("保存研究进度事件失败: generationId={}", generationId, error);
        }
    }

    public void updateConversationRecordId(String generationId, Long conversationRecordId) {
        if (conversationRecordId == null) {
            touch(generationId);
            return;
        }
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return;
        }
        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                meta.createdAt(),
                LocalDateTime.now().toString(),
                meta.errorMessage(),
                meta.clientId(),
                conversationRecordId
        );
        writeMeta(updated);
        expireGenerationKeys(generationId);
    }

    public void markCompleted(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        updateTerminalState(generationId, GenerationStatus.COMPLETED, null, referenceMappings);
    }

    public void markFailed(String generationId, String errorMessage) {
        updateTerminalState(generationId, GenerationStatus.FAILED, errorMessage, null);
    }

    public void markCancelled(String generationId) {
        updateTerminalState(generationId, GenerationStatus.CANCELLED, null, null);
    }

    public Optional<GenerationSnapshot> getGeneration(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return Optional.empty();
        }

        String content = Optional.ofNullable(redisTemplate.opsForValue().get(contentKey(generationId))).orElse("");
        Map<String, Map<String, Object>> references = readReferenceMappings(generationId);
        Map<String, Object> diagnostics = readDiagnostics(generationId);
        Map<String, Object> readingArtifacts = readReadingMap(readingArtifactsKey(generationId), "reading artifacts");
        Map<String, Object> readingStatePatch = readReadingMap(readingStatePatchKey(generationId), "reading state patch");
        List<Map<String, Object>> progressEvents = readProgressEvents(generationId);
        return Optional.of(toSnapshot(meta, content, references, diagnostics, readingArtifacts, readingStatePatch, progressEvents));
    }

    public Optional<GenerationSnapshot> getGenerationForUser(String generationId, String userId) {
        return getGeneration(generationId).filter(snapshot -> snapshot.userId().equals(userId));
    }

    public Optional<GenerationSnapshot> getActiveGenerationForUser(String userId) {
        String generationId = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId == null || generationId.isBlank()) {
            return Optional.empty();
        }
        return getGenerationForUser(generationId, userId);
    }

    public Optional<GenerationSnapshot> getActiveGenerationForUserAndClient(String userId, String clientId) {
        String normalizedClientId = trimToNull(clientId);
        if (normalizedClientId == null) {
            return Optional.empty();
        }
        String generationId = redisTemplate.opsForValue().get(activeGenerationKey(userId, normalizedClientId));
        if (generationId == null || generationId.isBlank()) {
            return Optional.empty();
        }
        return getGenerationForUser(generationId, userId);
    }

    private void updateTerminalState(String generationId,
                                     GenerationStatus status,
                                     String errorMessage,
                                     Map<String, Map<String, Object>> referenceMappings) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return;
        }

        if (referenceMappings != null) {
            updateReferenceMappings(generationId, referenceMappings);
        } else {
            touch(generationId);
        }

        String now = LocalDateTime.now().toString();
        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                status,
                meta.createdAt(),
                now,
                errorMessage,
                meta.clientId(),
                meta.conversationRecordId()
        );
        writeMeta(updated);
        clearActiveGeneration(meta.userId(), meta.clientId(), generationId);
    }

    private void touch(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return;
        }

        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                meta.createdAt(),
                LocalDateTime.now().toString(),
                meta.errorMessage(),
                meta.clientId(),
                meta.conversationRecordId()
        );
        writeMeta(updated);
        expireGenerationKeys(generationId);
        redisTemplate.opsForValue().set(activeGenerationKey(meta.userId()), generationId, GENERATION_TTL);
        if (meta.clientId() != null) {
            redisTemplate.opsForValue().set(activeGenerationKey(meta.userId(), meta.clientId()), generationId, GENERATION_TTL);
        }
    }

    private void clearActiveGeneration(String userId, String clientId, String generationId) {
        String current = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId.equals(current)) {
            redisTemplate.delete(activeGenerationKey(userId));
        }
        String normalizedClientId = trimToNull(clientId);
        if (normalizedClientId == null) {
            return;
        }
        String currentForClient = redisTemplate.opsForValue().get(activeGenerationKey(userId, normalizedClientId));
        if (generationId.equals(currentForClient)) {
            redisTemplate.delete(activeGenerationKey(userId, normalizedClientId));
        }
    }

    private void updateReadingMap(String generationId,
                                  String key,
                                  Map<String, Object> value,
                                  String label) {
        try {
            if (value == null || value.isEmpty()) {
                redisTemplate.delete(key);
            } else {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), GENERATION_TTL);
            }
            touch(generationId);
        } catch (Exception e) {
            logger.warn("保存生成态{}失败: generationId={}", label, generationId, e);
        }
    }

    private void expireGenerationKeys(String generationId) {
        redisTemplate.expire(contentKey(generationId), GENERATION_TTL);
        redisTemplate.expire(referenceKey(generationId), GENERATION_TTL);
        redisTemplate.expire(diagnosticsKey(generationId), GENERATION_TTL);
        redisTemplate.expire(readingArtifactsKey(generationId), GENERATION_TTL);
        redisTemplate.expire(readingStatePatchKey(generationId), GENERATION_TTL);
        redisTemplate.expire(progressEventsKey(generationId), GENERATION_TTL);
    }

    private void writeMeta(GenerationMeta meta) {
        try {
            redisTemplate.opsForValue().set(metaKey(meta.generationId()), objectMapper.writeValueAsString(meta), GENERATION_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("保存生成态元数据失败", e);
        }
    }

    private GenerationMeta readMeta(String generationId) {
        String raw = redisTemplate.opsForValue().get(metaKey(generationId));
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(raw, GenerationMeta.class);
        } catch (Exception e) {
            logger.warn("解析生成态元数据失败: generationId={}", generationId, e);
            return null;
        }
    }

    private Map<String, Map<String, Object>> readReferenceMappings(String generationId) {
        String raw = redisTemplate.opsForValue().get(referenceKey(generationId));
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(raw, REFERENCE_MAP_TYPE);
        } catch (Exception e) {
            logger.warn("解析生成态引用映射失败: generationId={}", generationId, e);
            return Collections.emptyMap();
        }
    }

    private Map<String, Object> readDiagnostics(String generationId) {
        String raw = redisTemplate.opsForValue().get(diagnosticsKey(generationId));
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(raw, DIAGNOSTICS_MAP_TYPE);
        } catch (Exception e) {
            logger.warn("解析生成态诊断信息失败: generationId={}", generationId, e);
            return Collections.emptyMap();
        }
    }

    private Map<String, Object> readReadingMap(String key, String label) {
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(raw, READING_MAP_TYPE);
        } catch (Exception e) {
            logger.warn("解析生成态{}失败: key={}", label, key, e);
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> readProgressEvents(String generationId) {
        var operations = redisTemplate.opsForList();
        if (operations == null) {
            return List.of();
        }
        List<String> rawEvents = operations.range(progressEventsKey(generationId), 0, -1);
        if (rawEvents == null || rawEvents.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (String raw : rawEvents) {
            try {
                events.add(objectMapper.readValue(raw, DIAGNOSTICS_MAP_TYPE));
            } catch (Exception error) {
                logger.warn("解析研究进度事件失败: generationId={}", generationId, error);
            }
        }
        return List.copyOf(events);
    }

    private GenerationSnapshot toSnapshot(GenerationMeta meta,
                                          String content,
                                          Map<String, Map<String, Object>> referenceMappings,
                                          Map<String, Object> diagnostics) {
        return toSnapshot(meta, content, referenceMappings, diagnostics, Collections.emptyMap(), Collections.emptyMap(), List.of());
    }

    private GenerationSnapshot toSnapshot(GenerationMeta meta,
                                          String content,
                                          Map<String, Map<String, Object>> referenceMappings,
                                          Map<String, Object> diagnostics,
                                          Map<String, Object> readingArtifacts,
                                          Map<String, Object> readingStatePatch,
                                          List<Map<String, Object>> progressEvents) {
        return new GenerationSnapshot(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                content,
                meta.createdAt(),
                meta.updatedAt(),
                meta.errorMessage(),
                referenceMappings == null ? Collections.emptyMap() : referenceMappings,
                diagnostics == null ? Collections.emptyMap() : diagnostics,
                readingArtifacts == null ? Collections.emptyMap() : readingArtifacts,
                readingStatePatch == null ? Collections.emptyMap() : readingStatePatch,
                progressEvents == null ? List.of() : progressEvents,
                meta.conversationRecordId()
        );
    }

    private String metaKey(String generationId) {
        return "chat:generation:" + generationId + ":meta";
    }

    private String contentKey(String generationId) {
        return "chat:generation:" + generationId + ":content";
    }

    private String referenceKey(String generationId) {
        return "chat:generation:" + generationId + ":refs";
    }

    private String diagnosticsKey(String generationId) {
        return "chat:generation:" + generationId + ":diagnostics";
    }

    private String readingArtifactsKey(String generationId) {
        return "chat:generation:" + generationId + ":reading_artifacts";
    }

    private String readingStatePatchKey(String generationId) {
        return "chat:generation:" + generationId + ":reading_state_patch";
    }

    private String progressEventsKey(String generationId) {
        return "chat:generation:" + generationId + ":progress_events";
    }

    private String activeGenerationKey(String userId) {
        return "chat:user:" + userId + ":active_generation";
    }

    private String activeGenerationKey(String userId, String clientId) {
        return "chat:user:" + userId + ":client:" + clientId + ":active_generation";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum GenerationStatus {
        STREAMING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public record GenerationMeta(
            String generationId,
            String userId,
            String conversationId,
            String question,
            GenerationStatus status,
            String createdAt,
            String updatedAt,
            String errorMessage,
            String clientId,
            Long conversationRecordId
    ) {
    }

    public record GenerationSnapshot(
            String generationId,
            String userId,
            String conversationId,
            String question,
            GenerationStatus status,
            String content,
            String createdAt,
            String updatedAt,
            String errorMessage,
            Map<String, Map<String, Object>> referenceMappings,
            Map<String, Object> diagnostics,
            Map<String, Object> readingArtifacts,
            Map<String, Object> readingStatePatch,
            List<Map<String, Object>> progressEvents,
            Long conversationRecordId
    ) {
        public GenerationSnapshot(String generationId,
                                  String userId,
                                  String conversationId,
                                  String question,
                                  GenerationStatus status,
                                  String content,
                                  String createdAt,
                                  String updatedAt,
                                  String errorMessage,
                                  Map<String, Map<String, Object>> referenceMappings,
                                  Map<String, Object> diagnostics,
                                  Map<String, Object> readingArtifacts,
                                  Map<String, Object> readingStatePatch,
                                  Long conversationRecordId) {
            this(generationId, userId, conversationId, question, status, content, createdAt, updatedAt,
                    errorMessage, referenceMappings, diagnostics, readingArtifacts, readingStatePatch,
                    List.of(), conversationRecordId);
        }
    }
}
