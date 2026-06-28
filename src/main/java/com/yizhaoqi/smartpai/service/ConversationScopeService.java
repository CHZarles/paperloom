package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.controller.dto.ConversationScopeRequests.UpdateConversationScopeRequest;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ConversationScopeMode;
import com.yizhaoqi.smartpai.model.ConversationScopeStatus;
import com.yizhaoqi.smartpai.model.ConversationSession;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationSessionRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ConversationScopeService {

    public static final String DEFAULT_AUTO_LIBRARY_LABEL = "All searchable papers";

    private final ConversationSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final PaperRepository paperRepository;
    private final PaperCollectionService paperCollectionService;
    private final PaperSearchabilityService paperSearchabilityService;
    private final OrgTagCacheService orgTagCacheService;
    private final ObjectMapper objectMapper;

    public ConversationScopeService(ConversationSessionRepository sessionRepository,
                                    UserRepository userRepository,
                                    PaperRepository paperRepository,
                                    PaperCollectionService paperCollectionService,
                                    PaperSearchabilityService paperSearchabilityService,
                                    OrgTagCacheService orgTagCacheService,
                                    ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.paperRepository = paperRepository;
        this.paperCollectionService = paperCollectionService;
        this.paperSearchabilityService = paperSearchabilityService;
        this.orgTagCacheService = orgTagCacheService;
        this.objectMapper = objectMapper;
    }

    public record EffectiveConversationScope(
            ConversationScopeMode mode,
            ConversationScopeStatus status,
            boolean locked,
            String label,
            List<String> paperIds,
            Map<String, Object> sourceRecipe
    ) {
        public EffectiveConversationScope {
            mode = mode == null ? ConversationScopeMode.AUTO_LIBRARY : mode;
            status = status == null ? ConversationScopeStatus.READY : status;
            label = label == null || label.isBlank()
                    ? (mode == ConversationScopeMode.AUTO_LIBRARY ? DEFAULT_AUTO_LIBRARY_LABEL : "Selected papers")
                    : label;
            paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
            sourceRecipe = sourceRecipe == null ? Map.of() : new LinkedHashMap<>(sourceRecipe);
        }
    }

    public Map<String, Object> defaultScope() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("scopeMode", ConversationScopeMode.AUTO_LIBRARY.name());
        scope.put("scopeLocked", false);
        scope.put("scopeStatus", ConversationScopeStatus.READY.name());
        scope.put("sourceLabel", DEFAULT_AUTO_LIBRARY_LABEL);
        scope.put("sourcePaperCount", null);
        scope.put("paperIds", List.of());
        scope.put("sourceRecipe", null);
        return scope;
    }

    @Transactional
    public Map<String, Object> updateUnlockedScope(Long userId,
                                                   String conversationId,
                                                   UpdateConversationScopeRequest request) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        if (session.isScopeLocked()) {
            throw new CustomException("Conversation scope is locked", HttpStatus.CONFLICT);
        }

        ConversationScopeMode mode = parseScopeMode(request == null ? null : request.scopeMode());
        if (mode == ConversationScopeMode.AUTO_LIBRARY) {
            applyAutoLibraryScope(session);
            sessionRepository.save(session);
            return scopeResponse(resolveSession(session));
        }

        User user = resolveUser(userId);
        List<String> directPaperIds = normalizedPaperIds(request == null ? null : request.paperIds());
        List<Long> collectionIds = normalizedCollectionIds(request == null ? null : request.collectionIds());

        LinkedHashSet<String> snapshotPaperIds = new LinkedHashSet<>();
        snapshotPaperIds.addAll(resolveDirectPaperIds(user, directPaperIds));
        for (Long collectionId : collectionIds) {
            snapshotPaperIds.addAll(resolveCollectionPaperIds(user, collectionId));
        }

        if (snapshotPaperIds.isEmpty()) {
            throw new CustomException("Conversation source scope resolved to no searchable papers", HttpStatus.BAD_REQUEST);
        }

        List<String> paperIds = new ArrayList<>(snapshotPaperIds);
        Map<String, Object> sourceRecipe = sourceRecipe(request, collectionIds, directPaperIds);
        Map<String, Object> sourceSnapshot = sourceSnapshot(paperIds);

        session.setScopeMode(ConversationScopeMode.SOURCE_SET_SNAPSHOT);
        session.setScopeStatus(ConversationScopeStatus.READY);
        session.setSourceLabel(sourceLabel(request == null ? null : request.sourceLabel(), collectionIds, directPaperIds));
        session.setSourceRecipeJson(writeJson(sourceRecipe));
        session.setSourceSnapshotJson(writeJson(sourceSnapshot));
        session.setSourcePaperCount(paperIds.size());
        sessionRepository.save(session);
        return scopeResponse(resolveSession(session));
    }

    @Transactional(readOnly = true)
    public EffectiveConversationScope resolveForChat(Long userId, String conversationId) {
        return resolveSession(requireOwnedSession(userId, conversationId));
    }

    @Transactional
    public EffectiveConversationScope lockForFirstMessage(Long userId, String conversationId) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        if (session.isScopeLocked()) {
            return resolveSession(session);
        }

        ConversationScopeMode mode = scopeModeOrDefault(session.getScopeMode());
        if (mode == ConversationScopeMode.SOURCE_SET_SNAPSHOT) {
            List<String> paperIds = snapshotPaperIds(session.getSourceSnapshotJson());
            if (paperIds.isEmpty()) {
                session.setScopeStatus(ConversationScopeStatus.INVALID);
                sessionRepository.save(session);
                throw new CustomException("Conversation source scope is invalid", HttpStatus.CONFLICT);
            }
        }

        session.setScopeMode(mode);
        session.setScopeLocked(true);
        session.setScopeStatus(scopeStatusOrDefault(session.getScopeStatus()));
        sessionRepository.save(session);
        return resolveSession(session);
    }

    public void assertReferenceFocusWithinScope(EffectiveConversationScope scope,
                                                PaperAnswerService.AnswerScope referenceFocus) {
        if (scope == null || scope.mode() == ConversationScopeMode.AUTO_LIBRARY || referenceFocus == null) {
            return;
        }

        LinkedHashSet<String> focusedPaperIds = new LinkedHashSet<>();
        normalizedPaperIds(referenceFocus.paperIds()).forEach(focusedPaperIds::add);
        String singlePaperId = trimToNull(referenceFocus.paperId());
        if (singlePaperId != null) {
            focusedPaperIds.add(singlePaperId);
        }
        if (focusedPaperIds.isEmpty()) {
            return;
        }

        Set<String> allowedPaperIds = new LinkedHashSet<>(normalizedPaperIds(scope.paperIds()));
        if (!allowedPaperIds.containsAll(focusedPaperIds)) {
            throw new CustomException("Reference focus is outside the conversation source scope", HttpStatus.FORBIDDEN);
        }
    }

    public Map<String, Object> scopeResponse(EffectiveConversationScope scope) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scopeMode", scope.mode().name());
        response.put("scopeLocked", scope.locked());
        response.put("scopeStatus", scope.status().name());
        response.put("sourceLabel", scope.label());
        response.put("sourcePaperCount", scope.mode() == ConversationScopeMode.SOURCE_SET_SNAPSHOT
                ? scope.paperIds().size()
                : null);
        response.put("paperIds", scope.paperIds());
        response.put("sourceRecipe", scope.sourceRecipe() != null && !scope.sourceRecipe().isEmpty()
                ? scope.sourceRecipe()
                : null);
        return response;
    }

    private void applyAutoLibraryScope(ConversationSession session) {
        session.setScopeMode(ConversationScopeMode.AUTO_LIBRARY);
        session.setScopeStatus(ConversationScopeStatus.READY);
        session.setSourceLabel(DEFAULT_AUTO_LIBRARY_LABEL);
        session.setSourceRecipeJson(null);
        session.setSourceSnapshotJson(null);
        session.setSourcePaperCount(null);
    }

    private EffectiveConversationScope resolveSession(ConversationSession session) {
        ConversationScopeMode mode = scopeModeOrDefault(session.getScopeMode());
        Map<String, Object> sourceRecipe = parseMap(session.getSourceRecipeJson());
        List<String> paperIds = mode == ConversationScopeMode.SOURCE_SET_SNAPSHOT
                ? snapshotPaperIds(session.getSourceSnapshotJson())
                : List.of();
        ConversationScopeStatus status = scopeStatusOrDefault(session.getScopeStatus());
        if (mode == ConversationScopeMode.SOURCE_SET_SNAPSHOT && paperIds.isEmpty()) {
            status = ConversationScopeStatus.INVALID;
        }
        return new EffectiveConversationScope(
                mode,
                status,
                session.isScopeLocked(),
                labelOrDefault(session.getSourceLabel(), mode),
                paperIds,
                sourceRecipe
        );
    }

    private ConversationSession requireOwnedSession(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            throw new CustomException("Conversation not found", HttpStatus.NOT_FOUND);
        }
        return sessionRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new CustomException("Conversation not found", HttpStatus.NOT_FOUND));
    }

    private User resolveUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
    }

    private ConversationScopeMode parseScopeMode(String rawMode) {
        String mode = trimToNull(rawMode);
        if (mode == null) {
            return ConversationScopeMode.AUTO_LIBRARY;
        }
        try {
            return ConversationScopeMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new CustomException("Invalid conversation scope mode: " + rawMode, HttpStatus.BAD_REQUEST);
        }
    }

    private ConversationScopeMode scopeModeOrDefault(ConversationScopeMode mode) {
        return mode == null ? ConversationScopeMode.AUTO_LIBRARY : mode;
    }

    private ConversationScopeStatus scopeStatusOrDefault(ConversationScopeStatus status) {
        return status == null ? ConversationScopeStatus.READY : status;
    }

    private List<String> resolveDirectPaperIds(User user, List<String> paperIds) {
        if (paperIds.isEmpty()) {
            return List.of();
        }
        Map<String, List<Paper>> papersByPaperId = productPapersByPaperId(paperIds);
        List<String> effectiveOrgTags = effectiveOrgTags(user);
        List<String> resolved = new ArrayList<>();
        for (String paperId : paperIds) {
            List<Paper> papers = papersByPaperId.getOrDefault(paperId, List.of());
            if (papers.isEmpty()) {
                throw new CustomException("Paper not found: " + paperId, HttpStatus.BAD_REQUEST);
            }
            boolean accessible = papers.stream().anyMatch(paper -> canAccessPaper(user, paper, effectiveOrgTags));
            if (!accessible) {
                throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
            }
            boolean searchable = papers.stream()
                    .filter(paper -> canAccessPaper(user, paper, effectiveOrgTags))
                    .anyMatch(paperSearchabilityService::isSearchable);
            if (!searchable) {
                throw new CustomException("Paper is not searchable: " + paperId, HttpStatus.BAD_REQUEST);
            }
            resolved.add(paperId);
        }
        return resolved;
    }

    private List<String> resolveCollectionPaperIds(User user, Long collectionId) {
        if (collectionId == null) {
            return List.of();
        }
        Map<String, Object> collection = paperCollectionService.getCollection(user.getId(), collectionId);
        List<String> paperIds = normalizedPaperIds(collection.get("paperIds"));
        if (paperIds.isEmpty()) {
            return List.of();
        }
        Map<String, List<Paper>> papersByPaperId = productPapersByPaperId(paperIds);
        List<String> effectiveOrgTags = effectiveOrgTags(user);
        return paperIds.stream()
                .filter(paperId -> papersByPaperId.getOrDefault(paperId, List.of()).stream()
                        .filter(paper -> canAccessPaper(user, paper, effectiveOrgTags))
                        .anyMatch(paperSearchabilityService::isSearchable))
                .toList();
    }

    private Map<String, List<Paper>> productPapersByPaperId(List<String> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return Map.of();
        }
        return paperRepository.findByPaperIdIn(paperIds)
                .stream()
                .filter(paper -> trimToNull(paper.getPaperId()) != null)
                .collect(Collectors.groupingBy(
                        Paper::getPaperId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    private boolean canAccessPaper(User user, Paper paper, List<String> effectiveOrgTags) {
        if (user == null || paper == null) {
            return false;
        }
        if (user.getRole() == User.Role.ADMIN) {
            return true;
        }
        if (String.valueOf(user.getId()).equals(trimToNull(paper.getUserId()))) {
            return true;
        }
        if (paper.isPublic()) {
            return true;
        }
        String paperOrgTag = trimToNull(paper.getOrgTag());
        return paperOrgTag != null && effectiveOrgTags.contains(paperOrgTag);
    }

    private List<String> effectiveOrgTags(User user) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        List<String> cachedTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        if (cachedTags != null) {
            cachedTags.stream()
                    .map(this::trimToNull)
                    .filter(Objects::nonNull)
                    .forEach(tags::add);
        }
        LinkedHashSet<String> rawTags = rawOrgTags(user);
        if (tags.isEmpty() || onlyDefaultOrgTag(tags)) {
            tags.addAll(rawTags);
        }
        return new ArrayList<>(tags);
    }

    private LinkedHashSet<String> rawOrgTags(User user) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addCsvTags(tags, user.getOrgTags());
        String primaryOrg = trimToNull(user.getPrimaryOrg());
        if (primaryOrg != null) {
            tags.add(primaryOrg);
        }
        return tags;
    }

    private boolean onlyDefaultOrgTag(Set<String> tags) {
        return tags.size() == 1 && tags.stream().anyMatch(tag -> "DEFAULT".equalsIgnoreCase(tag));
    }

    private void addCsvTags(Set<String> tags, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String item : csv.split(",")) {
            String tag = trimToNull(item);
            if (tag != null) {
                tags.add(tag);
            }
        }
    }

    private List<String> normalizedPaperIds(Object rawPaperIds) {
        if (!(rawPaperIds instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(value -> value == null ? null : String.valueOf(value))
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private List<Long> normalizedCollectionIds(List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            return List.of();
        }
        return collectionIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private String sourceLabel(String requestedLabel, List<Long> collectionIds, List<String> directPaperIds) {
        String label = trimToNull(requestedLabel);
        if (label != null) {
            return label;
        }
        if (!collectionIds.isEmpty() && !directPaperIds.isEmpty()) {
            return "Selected sources";
        }
        if (!collectionIds.isEmpty()) {
            return "Selected collections";
        }
        return "Selected papers";
    }

    private String labelOrDefault(String storedLabel, ConversationScopeMode mode) {
        String label = trimToNull(storedLabel);
        if (label != null) {
            return label;
        }
        return mode == ConversationScopeMode.AUTO_LIBRARY ? DEFAULT_AUTO_LIBRARY_LABEL : "Selected papers";
    }

    private Map<String, Object> sourceRecipe(UpdateConversationScopeRequest request,
                                             List<Long> collectionIds,
                                             List<String> directPaperIds) {
        if (request != null && request.sourceRecipe() != null && !request.sourceRecipe().isEmpty()) {
            return new LinkedHashMap<>(request.sourceRecipe());
        }
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("scopeMode", ConversationScopeMode.SOURCE_SET_SNAPSHOT.name());
        recipe.put("collectionIds", collectionIds);
        recipe.put("paperIds", directPaperIds);
        return recipe;
    }

    private Map<String, Object> sourceSnapshot(List<String> paperIds) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("paperIds", paperIds);
        snapshot.put("paperCount", paperIds.size());
        return snapshot;
    }

    private List<String> snapshotPaperIds(String sourceSnapshotJson) {
        return normalizedPaperIds(parseMap(sourceSnapshotJson).get("paperIds"));
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            return parsed == null ? Map.of() : parsed;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CustomException("Invalid conversation scope metadata", HttpStatus.BAD_REQUEST);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
