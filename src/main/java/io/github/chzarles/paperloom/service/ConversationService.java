package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.Conversation;
import io.github.chzarles.paperloom.model.ConversationSession;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperSourceQuote;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.ConversationRepository;
import io.github.chzarles.paperloom.repository.ConversationSessionRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductReadingSourceQuoteResolver sourceQuoteResolver;

    @Autowired(required = false)
    private ConversationScopeService conversationScopeService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public Long recordConversation(String username, String question, String answer) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        return saveConversation(user, question, answer, null, null, null, null, null, null);
    }

    @Transactional
    public Long recordConversation(Long userId, String question, String answer, String conversationId,
                                   Map<String, Map<String, Object>> referenceMappings) {
        return recordConversation(userId, question, answer, conversationId, referenceMappings, null);
    }

    @Transactional
    public Long recordConversation(Long userId, String question, String answer, String conversationId,
                                   Map<String, Map<String, Object>> referenceMappings,
                                   Map<String, Object> effectiveScope) {
        return recordConversation(userId, question, answer, conversationId, referenceMappings, effectiveScope, null, null, null);
    }

    @Transactional
    public Long recordConversation(Long userId, String question, String answer, String conversationId,
                                   Map<String, Map<String, Object>> referenceMappings,
                                   Map<String, Object> effectiveScope,
                                   ReadingTurnArtifacts readingArtifacts,
                                   ReadingStatePatch readingStatePatch) {
        return recordConversation(
                userId, question, answer, conversationId, referenceMappings, effectiveScope,
                readingArtifacts, readingStatePatch, null
        );
    }

    @Transactional
    public Long recordConversation(Long userId, String question, String answer, String conversationId,
                                   Map<String, Map<String, Object>> referenceMappings,
                                   Map<String, Object> effectiveScope,
                                   ReadingTurnArtifacts readingArtifacts,
                                   ReadingStatePatch readingStatePatch,
                                   List<Map<String, Object>> researchEvents) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        Long conversationRecordId = saveConversation(user, question, answer, conversationId, referenceMappings, effectiveScope,
                readingArtifacts, readingStatePatch, researchEvents);
        updateSessionReadingMemory(userId, conversationId, readingStatePatch);
        updateSessionTitleIfDefault(userId, conversationId, question);
        touchSessionUpdatedAt(userId, conversationId);
        return conversationRecordId;
    }

    private Long saveConversation(User user, String question, String answer, String conversationId,
                                  Map<String, Map<String, Object>> referenceMappings,
                                  Map<String, Object> effectiveScope,
                                  ReadingTurnArtifacts readingArtifacts,
                                  ReadingStatePatch readingStatePatch,
                                  List<Map<String, Object>> researchEvents) {
        Conversation conversation = new Conversation();
        conversation.setUser(user);
        conversation.setQuestion(question);
        conversation.setAnswer(answer);
        conversation.setConversationId(conversationId);
        conversation.setReferenceMappingsJson(writeReferenceMappings(referenceMappings));
        conversation.setEffectiveScopeJson(writeEffectiveScope(effectiveScope));
        conversation.setReadingArtifactsJson(writeReadingArtifacts(readingArtifacts));
        conversation.setReadingStatePatchJson(writeReadingStatePatch(readingStatePatch));
        conversation.setResearchEventsJson(writeResearchEvents(researchEvents));

        Conversation saved = conversationRepository.save(conversation);
        if (saved != null && saved.getId() != null) {
            return saved.getId();
        }
        return conversation.getId();
    }

    // ---- ConversationSession management ----

    public List<Map<String, Object>> getConversationSessions(Long userId) {
        List<ConversationSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ConversationSession session : sessions) {
            if (isEmptySession(userId, session)) {
                continue;
            }
            result.add(toSessionResponse(userId, session));
        }

        return result;
    }

    private boolean isEmptySession(Long userId, ConversationSession session) {
        if (userId == null || session == null || session.getConversationId() == null || session.getConversationId().isBlank()) {
            return true;
        }
        return !conversationRepository.existsByUserIdAndConversationId(userId, session.getConversationId());
    }

    public Optional<Map<String, Object>> getCurrentConversationSession(Long userId) {
        String redisKey = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(redisKey);

        if (conversationId != null && !conversationId.isBlank()) {
            Optional<ConversationSession> current = findOwnedSession(userId, conversationId)
                    .filter(session -> session.getStatus() == ConversationSession.SessionStatus.ACTIVE);
            if (current.isPresent() && !isEmptySession(userId, current.get())) {
                return current.map(session -> toSessionResponse(userId, session));
            }
            redisTemplate.delete(redisKey);
        }

        List<ConversationSession> activeSessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(
                userId,
                ConversationSession.SessionStatus.ACTIVE
        );
        for (ConversationSession session : activeSessions) {
            if (isEmptySession(userId, session)) {
                continue;
            }
            redisTemplate.opsForValue().set(redisKey, session.getConversationId(), Duration.ofDays(7));
            return Optional.of(toSessionResponse(userId, session));
        }
        return Optional.empty();
    }

    private Map<String, Object> toSessionResponse(Long userId, ConversationSession session) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", session.getId());
        item.put("conversationId", session.getConversationId());
        item.put("title", session.getTitle() != null ? session.getTitle() : "新对话");
        item.put("status", session.getStatus().name());
        item.put("createdAt", formatTimestamp(session.getCreatedAt()));
        item.put("updatedAt", formatTimestamp(session.getUpdatedAt()));
        appendScopeSummary(item, userId, session);
        return item;
    }

    public Map<String, Object> createConversationSession(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        String conversationId = UUID.randomUUID().toString();

        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(conversationId);
        session.setTitle("新对话");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);

        // Update Redis so the backend uses this new conversation for subsequent messages
        String redisKey = "user:" + userId + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("title", "新对话");
        result.put("status", "ACTIVE");
        result.put("createdAt", formatTimestamp(session.getCreatedAt()));
        result.put("updatedAt", formatTimestamp(session.getUpdatedAt()));
        appendScopeSummary(result, userId, session);
        return result;
    }

    public void ensureConversationSession(Long userId, String conversationId, String title) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        if (sessionRepository.findByConversationIdAndUserId(conversationId, userId).isPresent()) {
            return;
        }

        if (sessionRepository.existsByConversationId(conversationId)) {
            throw new CustomException("对话不存在", HttpStatus.NOT_FOUND);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(conversationId);
        session.setTitle(title != null && !title.isBlank() ? title : "新对话");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);
    }

    public void switchCurrentConversation(Long userId, String conversationId) {
        requireOwnedSession(userId, conversationId);
        String redisKey = "user:" + userId + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));
    }

    public ConversationSession requireActiveOwnedConversationSession(Long userId, String conversationId) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE) {
            throw new CustomException("对话不存在", HttpStatus.NOT_FOUND);
        }
        return session;
    }

    public void updateSessionTitle(Long userId, String conversationId, String title) {
        findOwnedSession(userId, conversationId).ifPresent(session -> {
            if (session.getTitle() == null || "新对话".equals(session.getTitle())) {
                session.setTitle(title);
                sessionRepository.save(session);
            }
        });
    }

    public void archiveConversationSession(Long userId, String conversationId) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        session.setStatus(ConversationSession.SessionStatus.ARCHIVED);
        sessionRepository.save(session);
    }

    public void unarchiveConversationSession(Long userId, String conversationId) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);
    }

    @Transactional
    public void deleteConversationSession(Long userId, String conversationId) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        String redisKey = "user:" + userId + ":current_conversation";
        String currentConversationId = redisTemplate.opsForValue().get(redisKey);

        conversationRepository.deleteByUserIdAndConversationId(userId, conversationId);
        sessionRepository.delete(session);

        if (conversationId.equals(currentConversationId)) {
            redisTemplate.delete(redisKey);
        }
    }

    private void touchSessionUpdatedAt(Long userId, String conversationId) {
        findOwnedSession(userId, conversationId).ifPresent(session -> {
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    private void updateSessionReadingMemory(Long userId,
                                            String conversationId,
                                            ReadingStatePatch readingStatePatch) {
        if (readingStatePatch == null || readingStatePatch.isEmpty()) {
            return;
        }
        findOwnedSession(userId, conversationId).ifPresent(session -> {
            Map<String, Object> memory = parseJsonObject(
                    session.getConversationMemoryJson(),
                    "conversation memory"
            );
            Map<String, Object> updatedMemory = memory == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(memory);
            updatedMemory.put("readingStatePatch", objectMapper.convertValue(
                    readingStatePatch,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            ));
            session.setConversationMemoryJson(writeJsonObject(updatedMemory, "conversation memory"));
            sessionRepository.save(session);
        });
    }

    public void updateSessionTitleIfDefault(Long userId, String conversationId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        String trimmed = title.length() > 50 ? title.substring(0, 50) : title;
        findOwnedSession(userId, conversationId).ifPresent(session -> {
            if ("新对话".equals(session.getTitle())) {
                session.setTitle(trimmed);
                sessionRepository.save(session);
            }
        });
    }

    // ---- Message queries ----

    public List<Map<String, Object>> getMessagesByConversationId(Long userId, String conversationId) {
        List<Conversation> conversations = conversationRepository.findByUserIdAndConversationIdOrderByTimestampAsc(userId, conversationId);
        return toMessageHistory(conversations, false);
    }

    private ConversationSession requireOwnedSession(Long userId, String conversationId) {
        return findOwnedSession(userId, conversationId)
                .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
    }

    private Optional<ConversationSession> findOwnedSession(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findByConversationIdAndUserId(conversationId, userId);
    }

    private void appendScopeSummary(Map<String, Object> item, Long userId, ConversationSession session) {
        item.put("scopeMode", session.getScopeMode() == null
                ? "AUTO_LIBRARY"
                : session.getScopeMode().name());
        item.put("scopeLocked", session.isScopeLocked());
        item.put("scopeStatus", session.getScopeStatus() == null
                ? "READY"
                : session.getScopeStatus().name());
        item.put("sourceLabel", sourceLabel(session));
        item.put("sourcePaperCount", sourcePaperCount(userId, session));
    }

    private Integer sourcePaperCount(Long userId, ConversationSession session) {
        if (session == null || session.getScopeMode() == null || !"AUTO_LIBRARY".equals(session.getScopeMode().name())) {
            return session == null ? null : session.getSourcePaperCount();
        }
        if (conversationScopeService == null || userId == null) {
            return session.getSourcePaperCount();
        }
        return conversationScopeService.autoLibraryReadablePaperCount(userId);
    }

    private String sourceLabel(ConversationSession session) {
        if (session.getSourceLabel() != null && !session.getSourceLabel().isBlank()) {
            return session.getSourceLabel();
        }
        if (session.getScopeMode() != null && "SOURCE_SET_SNAPSHOT".equals(session.getScopeMode().name())) {
            return "Selected papers";
        }
        return ConversationScopeService.DEFAULT_AUTO_LIBRARY_LABEL;
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        return timestamp != null ? timestamp.format(TIMESTAMP_FORMATTER) : null;
    }

    public List<Conversation> getConversations(String username, LocalDateTime startDate, LocalDateTime endDate) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        if (user.getRole() == User.Role.ADMIN && "all".equals(username)) {
            if (startDate != null && endDate != null) {
                return conversationRepository.findByTimestampBetweenOrderByTimestampAsc(startDate, endDate);
            } else {
                return conversationRepository.findAllByOrderByTimestampAsc();
            }
        } else {
            if (startDate != null && endDate != null) {
                return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc(
                        user.getId(), startDate, endDate);
            } else {
                return conversationRepository.findByUserIdOrderByTimestampAsc(user.getId());
            }
        }
    }

    public List<Conversation> getAllConversations(String adminUsername, String targetUsername,
                                                 LocalDateTime startDate, LocalDateTime endDate) {
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));

        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Unauthorized access", HttpStatus.FORBIDDEN);
        }

        if (targetUsername != null && !targetUsername.isEmpty()) {
            User targetUser = userRepository.findByUsername(targetUsername)
                    .orElseThrow(() -> new CustomException("Target user not found", HttpStatus.NOT_FOUND));

            if (startDate != null && endDate != null) {
                return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc(
                        targetUser.getId(), startDate, endDate);
            } else {
                return conversationRepository.findByUserIdOrderByTimestampAsc(targetUser.getId());
            }
        } else {
            if (startDate != null && endDate != null) {
                return conversationRepository.findByTimestampBetweenOrderByTimestampAsc(startDate, endDate);
            } else {
                return conversationRepository.findAllByOrderByTimestampAsc();
            }
        }
    }

    public List<Map<String, Object>> toMessageHistory(List<Conversation> conversations, boolean includeUsername) {
        List<Map<String, Object>> messages = new ArrayList<>();

        conversations.stream()
                .sorted(Comparator
                        .comparing(Conversation::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Conversation::getId))
                .forEach(conversation -> {
                    String timestamp = conversation.getTimestamp() != null
                            ? conversation.getTimestamp().format(TIMESTAMP_FORMATTER)
                            : null;
                    String messageConversationId = conversation.getConversationId() != null
                            ? conversation.getConversationId()
                            : String.valueOf(conversation.getId());

                    messages.add(buildMessage(
                            "user",
                            conversation.getQuestion(),
                            timestamp,
                            messageConversationId,
                            conversation.getId(),
                            null,
                            includeUsername ? conversation.getUser().getUsername() : null,
                            parseEffectiveScope(conversation.getEffectiveScopeJson()),
                            null,
                            null,
                            null
                    ));
                    messages.add(buildMessage(
                            "assistant",
                            conversation.getAnswer(),
                            timestamp,
                            messageConversationId,
                            conversation.getId(),
                            parseReferenceMappings(conversation.getReferenceMappingsJson()),
                            includeUsername ? conversation.getUser().getUsername() : null,
                            null,
                            parseJsonObject(conversation.getReadingArtifactsJson(), "reading artifacts"),
                            parseJsonObject(conversation.getReadingStatePatchJson(), "reading state patch"),
                            parseResearchEvents(conversation.getResearchEventsJson())
                    ));
                });

        return messages;
    }

    private Map<String, Object> buildMessage(String role, String content, String timestamp, String conversationId,
                                             Long conversationRecordId,
                                             Map<String, Map<String, Object>> referenceMappings,
                                             String username,
                                             Map<String, Object> effectiveScope,
                                             Map<String, Object> readingArtifacts,
                                             Map<String, Object> readingStatePatch,
                                             List<Map<String, Object>> researchEvents) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);
        if (timestamp != null) {
            message.put("timestamp", timestamp);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            message.put("conversationId", conversationId);
        }
        if (conversationRecordId != null) {
            message.put("conversationRecordId", conversationRecordId);
        }
        if (referenceMappings != null && !referenceMappings.isEmpty()) {
            message.put("referenceMappings", referenceMappings);
        }
        if (username != null && !username.isBlank()) {
            message.put("username", username);
        }
        if (effectiveScope != null && !effectiveScope.isEmpty()) {
            message.put("effectiveScope", effectiveScope);
        }
        if (readingArtifacts != null && !readingArtifacts.isEmpty()) {
            message.put("readingArtifacts", readingArtifacts);
        }
        if (readingStatePatch != null && !readingStatePatch.isEmpty()) {
            message.put("readingStatePatch", readingStatePatch);
        }
        if (researchEvents != null && !researchEvents.isEmpty()) {
            message.put("researchEvents", researchEvents);
        }
        return message;
    }

    public Optional<Map<String, Object>> findReferenceDetail(Long userId, Long conversationRecordId, Integer referenceNumber) {
        if (userId == null || conversationRecordId == null || referenceNumber == null) {
            return Optional.empty();
        }

        Optional<Conversation> conversation = conversationRepository.findByIdAndUserId(conversationRecordId, userId);
        if (conversation.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Map<String, Object>> mappings = parseReferenceMappings(conversation.get().getReferenceMappingsJson());
        if (mappings == null || mappings.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> detail = mappings.get(String.valueOf(referenceNumber));
        if (detail == null || detail.isEmpty()) {
            return Optional.empty();
        }
        return resolveReferenceDetail(conversation.get(), detail, referenceNumber);
    }

    public Optional<Map<String, Object>> findLatestReferenceDetail(Long userId, String conversationId, Integer referenceNumber) {
        if (userId == null || conversationId == null || conversationId.isBlank() || referenceNumber == null) {
            return Optional.empty();
        }

        List<Conversation> conversations = conversationRepository.findByUserIdAndConversationIdOrderByTimestampAsc(userId, conversationId);
        for (int i = conversations.size() - 1; i >= 0; i -= 1) {
            Map<String, Map<String, Object>> mappings = parseReferenceMappings(conversations.get(i).getReferenceMappingsJson());
            if (mappings == null || mappings.isEmpty()) {
                continue;
            }
            Map<String, Object> detail = mappings.get(String.valueOf(referenceNumber));
            if (detail != null && !detail.isEmpty()) {
                return resolveReferenceDetail(conversations.get(i), detail, referenceNumber);
            }
        }
        return Optional.empty();
    }

    public Optional<Map<String, Object>> findLatestReferenceFocus(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }

        List<Conversation> conversations = conversationRepository.findByUserIdAndConversationIdOrderByTimestampAsc(userId, conversationId);
        for (int i = conversations.size() - 1; i >= 0; i -= 1) {
            Map<String, Map<String, Object>> mappings = parseReferenceMappings(conversations.get(i).getReferenceMappingsJson());
            if (mappings == null || mappings.isEmpty()) {
                continue;
            }
            return Optional.of(new LinkedHashMap<>(mappings));
        }
        return Optional.empty();
    }

    public Optional<Map<String, Object>> findLatestReadingStatePatch(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }

        Optional<Map<String, Object>> sessionPatch = findOwnedSession(userId, conversationId)
                .map(ConversationSession::getConversationMemoryJson)
                .map(json -> parseJsonObject(json, "conversation memory"))
                .map(memory -> objectMap(memory.get("readingStatePatch")))
                .filter(patch -> !patch.isEmpty());
        if (sessionPatch.isPresent()) {
            return sessionPatch;
        }

        List<Conversation> conversations = conversationRepository.findByUserIdAndConversationIdOrderByTimestampAsc(userId, conversationId);
        for (int i = conversations.size() - 1; i >= 0; i -= 1) {
            Map<String, Object> patch = parseJsonObject(
                    conversations.get(i).getReadingStatePatchJson(),
                    "reading state patch"
            );
            if (patch != null && !patch.isEmpty()) {
                return Optional.of(new LinkedHashMap<>(patch));
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> normalizeReferenceDetail(Map<String, Object> detail, Integer referenceNumber) {
        Map<String, Object> normalized = new LinkedHashMap<>(detail);
        normalized.put("referenceNumber", referenceNumber);

        Object paperId = normalized.get("paperId");
        if (paperId == null) {
            paperId = normalized.get("fileMd5");
            if (paperId != null) {
                normalized.put("paperId", paperId);
            }
        }

        Object paperTitle = normalized.get("paperTitle");
        if (paperTitle == null) {
            paperTitle = normalized.get("fileName");
            if (paperTitle != null) {
                normalized.put("paperTitle", paperTitle);
            }
        }

        normalized.putIfAbsent("originalFilename", normalized.get("paperTitle"));
        normalized.remove("fileMd5");
        normalized.remove("fileName");
        normalized.put("sourceType", "PDF");
        boolean pdfEvidenceAvailable = booleanValue(normalized.get("pdfEvidenceAvailable"));
        normalized.put("pdfEvidenceAvailable", pdfEvidenceAvailable);
        normalized.put("evidenceAssetLevel", "PDF_VISUAL".equals(normalized.get("evidenceAssetLevel")) || pdfEvidenceAvailable
                ? "PDF_VISUAL"
                : "PDF_PENDING_ASSETS");
        normalized.put("pageScreenshotAvailable", booleanValue(normalized.get("pageScreenshotAvailable")));
        normalized.put("figureScreenshotAvailable", booleanValue(normalized.get("figureScreenshotAvailable")));
        normalized.put("assetWarnings", normalizedAssetWarnings(
                normalized.get("assetWarnings"),
                pdfEvidenceAvailable,
                booleanValue(normalized.get("pageScreenshotAvailable"))
        ));
        normalized.remove(legacyField("structured", "Import"));
        normalized.remove(legacyField("eval", "Import"));
        return normalized;
    }

    private Optional<Map<String, Object>> resolveReferenceDetail(Conversation conversation,
                                                                 Map<String, Object> detail,
                                                                 Integer referenceNumber) {
        String sourceQuoteRef = stringValue(detail.get("sourceQuoteRef"));
        if (sourceQuoteRef.isBlank()) {
            return Optional.of(normalizeReferenceDetail(detail, referenceNumber));
        }
        return sourceQuoteReferenceDetail(conversation, detail, referenceNumber, sourceQuoteRef);
    }

    private Optional<Map<String, Object>> sourceQuoteReferenceDetail(Conversation conversation,
                                                                    Map<String, Object> persistedDetail,
                                                                    Integer referenceNumber,
                                                                    String sourceQuoteRef) {
        if (conversation == null
                || conversation.getConversationId() == null
                || conversation.getConversationId().isBlank()) {
            return Optional.empty();
        }
        ProductReadingSourceQuoteResolver.Resolution resolution = sourceQuoteResolver.resolveRegisteredCurrentQuote(
                conversation.getConversationId(),
                sourceQuoteRef
        );
        if (!resolution.ok()) {
            return Optional.empty();
        }
        Map<String, Object> normalized = normalizeReferenceDetail(persistedDetail, referenceNumber);
        Map<String, Object> authoritative = sourceQuoteDetail(
                resolution.sourceQuote().orElseThrow(),
                resolution.paper(),
                referenceNumber
        );
        authoritative.forEach((key, value) -> {
            if (value != null) {
                normalized.put(key, value);
            }
        });
        normalized.put("sourceQuoteResolutionStatus", "OK");
        return Optional.of(normalized);
    }

    private Map<String, Object> sourceQuoteDetail(PaperSourceQuote quote,
                                                  Optional<Paper> paper,
                                                  Integer referenceNumber) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("referenceNumber", referenceNumber);
        detail.put("sourceQuoteRef", quote.getSourceQuoteRef());
        detail.put("paperId", quote.getPaperId());
        detail.put("paperVersion", quote.getModelVersion());
        detail.put("locationRef", quote.getLocationRef());
        detail.put("locationType", quote.getLocationType());
        detail.put("pageNumber", quote.getPageNumber());
        detail.put("pageEndNumber", quote.getPageEndNumber());
        detail.put("sectionTitle", quote.getSectionTitle());
        detail.put("contentKind", quote.getContentKind());
        detail.put("sourceKind", sourceKindFromContentKind(quote.getContentKind()));
        detail.put("sourceType", "PDF");
        detail.put("retrievalMode", "PRODUCT_READING_REACT");
        detail.put("retrievalLabel", "Product Reading evidence");
        detail.put("retrievalRoute", "PRODUCT_READING_REACT");
        detail.put("anchorText", quote.getContent());
        detail.put("matchedChunkText", quote.getContent());
        detail.put("evidenceSnippet", quote.getContent());
        paper.ifPresent(resolvedPaper -> {
            if (resolvedPaper.getPaperTitle() != null && !resolvedPaper.getPaperTitle().isBlank()) {
                detail.put("paperTitle", resolvedPaper.getPaperTitle());
            }
            if (resolvedPaper.getOriginalFilename() != null && !resolvedPaper.getOriginalFilename().isBlank()) {
                detail.put("originalFilename", resolvedPaper.getOriginalFilename());
            }
        });
        return detail;
    }

    private String sourceKindFromContentKind(String contentKind) {
        String normalized = stringValue(contentKind).toUpperCase();
        if ("TABLE".equals(normalized)) {
            return "TABLE";
        }
        if ("FIGURE".equals(normalized) || "CHART".equals(normalized)) {
            return "FIGURE";
        }
        if ("FORMULA".equals(normalized)) {
            return "FORMULA";
        }
        return "TEXT";
    }

    private String legacyField(String prefix, String suffix) {
        return prefix + suffix;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private List<String> normalizedAssetWarnings(Object value,
                                                 boolean pdfEvidenceAvailable,
                                                 boolean pageScreenshotAvailable) {
        List<String> warnings = new ArrayList<>();
        if (value instanceof List<?> list) {
            warnings.addAll(list.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(String::valueOf)
                    .filter(item -> !isLegacyImportWarning(item))
                    .toList());
        }
        if (!pdfEvidenceAvailable && !pageScreenshotAvailable
                && !warnings.contains("pdf_page_visual_evidence_unavailable")) {
            warnings.add("pdf_page_visual_evidence_unavailable");
        }
        return List.copyOf(warnings);
    }

    private boolean isLegacyImportWarning(String value) {
        return value.startsWith("structured_") && value.endsWith("_text_only");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String writeReferenceMappings(Map<String, Map<String, Object>> referenceMappings) {
        if (referenceMappings == null || referenceMappings.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(referenceMappings);
        } catch (Exception e) {
            logger.warn("序列化引用映射失败，将跳过持久化引用详情", e);
            return null;
        }
    }

    private String writeEffectiveScope(Map<String, Object> effectiveScope) {
        if (effectiveScope == null || effectiveScope.isEmpty()) {
            return null;
        }

        return writeJsonObject(effectiveScope, "effective retrieval scope");
    }

    private String writeJsonObject(Map<String, Object> value, String label) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            logger.warn("序列化{}失败，将跳过对应持久化字段", label, e);
            return null;
        }
    }

    private String writeReadingArtifacts(ReadingTurnArtifacts readingArtifacts) {
        if (readingArtifacts == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(readingArtifacts);
        } catch (Exception e) {
            logger.warn("序列化 reading artifacts 失败，将跳过持久化结构化阅读状态", e);
            return null;
        }
    }

    private String writeReadingStatePatch(ReadingStatePatch readingStatePatch) {
        if (readingStatePatch == null || readingStatePatch.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(readingStatePatch);
        } catch (Exception e) {
            logger.warn("序列化 reading state patch 失败，将跳过持久化当前阅读目标", e);
            return null;
        }
    }

    private String writeResearchEvents(List<Map<String, Object>> researchEvents) {
        if (researchEvents == null || researchEvents.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(researchEvents);
        } catch (Exception e) {
            logger.warn("序列化 research events 失败，将跳过检索过程持久化", e);
            return null;
        }
    }

    private Map<String, Object> parseEffectiveScope(String effectiveScopeJson) {
        if (effectiveScopeJson == null || effectiveScopeJson.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    effectiveScopeJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (Exception e) {
            logger.warn("解析有效检索范围失败，将返回无范围审计的历史记录", e);
            return null;
        }
    }

    private Map<String, Object> parseJsonObject(String json, String label) {
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (Exception e) {
            logger.warn("解析{}失败，将返回无结构化阅读状态的历史记录", label, e);
            return null;
        }
    }

    private List<Map<String, Object>> parseResearchEvents(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(
                    json,
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            );
            return parsed == null || parsed.isEmpty() ? null : List.copyOf(parsed);
        } catch (Exception e) {
            logger.warn("解析 research events 失败，将返回无检索过程的历史记录", e);
            return null;
        }
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> parseReferenceMappings(String referenceMappingsJson) {
        if (referenceMappingsJson == null || referenceMappingsJson.isBlank()) {
            return null;
        }

        try {
            Map<String, Map<String, Object>> rawMappings = objectMapper.readValue(
                    referenceMappingsJson,
                    new TypeReference<Map<String, Map<String, Object>>>() {}
            );
            Map<String, Map<String, Object>> normalizedMappings = new LinkedHashMap<>();
            rawMappings.forEach((key, value) -> normalizedMappings.put(key, normalizeReferenceDetail(value, integerValue(key))));
            return normalizedMappings;
        } catch (Exception e) {
            logger.warn("解析引用映射失败，将返回无引用详情的历史记录", e);
            return null;
        }
    }

    private Integer integerValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
