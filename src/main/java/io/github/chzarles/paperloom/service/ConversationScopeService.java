package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.controller.dto.ConversationScopeRequests.UpdateConversationScopeRequest;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.ConversationScopeMode;
import io.github.chzarles.paperloom.model.ConversationScopeStatus;
import io.github.chzarles.paperloom.model.ConversationSession;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.ConversationSessionRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class ConversationScopeService {

    public static final String DEFAULT_AUTO_LIBRARY_LABEL = "All readable papers";

    private final ConversationSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final PaperRepository paperRepository;
    private final PaperReadingModelRepository readingModelRepository;
    private final PaperCollectionService paperCollectionService;
    private final PaperSearchabilityService paperSearchabilityService;
    private final PaperAccessService paperAccessService;
    private final ObjectMapper objectMapper;

    public ConversationScopeService(ConversationSessionRepository sessionRepository,
                                    UserRepository userRepository,
                                    PaperRepository paperRepository,
                                    PaperReadingModelRepository readingModelRepository,
                                    PaperCollectionService paperCollectionService,
                                    PaperSearchabilityService paperSearchabilityService,
                                    PaperAccessService paperAccessService,
                                    ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.paperRepository = paperRepository;
        this.readingModelRepository = readingModelRepository;
        this.paperCollectionService = paperCollectionService;
        this.paperSearchabilityService = paperSearchabilityService;
        this.paperAccessService = paperAccessService;
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
        ConversationSession session = requireOwnedSessionForUpdate(userId, conversationId);
        if (session.isScopeLocked()) {
            throw new CustomException("Conversation scope is locked", HttpStatus.CONFLICT);
        }

        ConversationScopeMode mode = parseScopeMode(request == null ? null : request.scopeMode());
        if (mode == ConversationScopeMode.AUTO_LIBRARY) {
            applyAutoLibraryScope(session);
            sessionRepository.save(session);
            return scopeResponse(userId, resolveSession(session));
        }

        User user = resolveUser(userId);
        List<String> directPaperIds = normalizedPaperIds(request == null ? null : request.paperIds());
        List<Long> collectionIds = normalizedCollectionIds(request == null ? null : request.collectionIds());
        List<String> titleMatchPaperIds = resolveTitleMatchPaperIds(user, request, true);

        LinkedHashSet<String> snapshotPaperIds = new LinkedHashSet<>();
        snapshotPaperIds.addAll(resolveDirectPaperIds(user, directPaperIds));
        for (Long collectionId : collectionIds) {
            snapshotPaperIds.addAll(resolveCollectionPaperIds(user, collectionId));
        }
        snapshotPaperIds.addAll(titleMatchPaperIds);

        if (snapshotPaperIds.isEmpty()) {
            throw new CustomException("Conversation source scope resolved to no searchable papers", HttpStatus.BAD_REQUEST);
        }

        List<String> paperIds = new ArrayList<>(snapshotPaperIds);
        Map<String, Object> sourceRecipe = sourceRecipe(request, collectionIds, directPaperIds, titleMatchPaperIds);
        Map<String, Object> sourceSnapshot = sourceSnapshot(paperIds);

        session.setScopeMode(ConversationScopeMode.SOURCE_SET_SNAPSHOT);
        session.setScopeStatus(ConversationScopeStatus.READY);
        session.setSourceLabel(sourceLabel(request, collectionIds, directPaperIds, titleMatchPaperIds));
        session.setSourceRecipeJson(writeJson(sourceRecipe));
        session.setSourceSnapshotJson(writeJson(sourceSnapshot));
        session.setSourcePaperCount(paperIds.size());
        sessionRepository.save(session);
        return scopeResponse(userId, resolveSession(session));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewTitleMatchScope(Long userId,
                                                      String conversationId,
                                                      UpdateConversationScopeRequest request) {
        ConversationSession session = requireOwnedSession(userId, conversationId);
        if (session.isScopeLocked()) {
            throw new CustomException("Conversation scope is locked", HttpStatus.CONFLICT);
        }
        User user = resolveUser(userId);
        List<String> paperIds = resolveTitleMatchPaperIds(user, request, false);
        Map<String, List<Paper>> papersByPaperId = productPapersByPaperId(paperIds);
        List<Map<String, Object>> papers = paperIds.stream()
                .map(paperId -> papersByPaperId.getOrDefault(paperId, List.of()).stream()
                        .filter(paper -> canAccessPaper(user, paper))
                        .filter(paperSearchabilityService::isSearchable)
                        .findFirst()
                        .map(this::paperPreview)
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("paperCount", paperIds.size());
        preview.put("paperIds", paperIds);
        preview.put("papers", papers);
        preview.put("sourceLabel", titleMatchLabel(request, paperIds));
        preview.put("sourceRecipe", titleMatchRecipe(request, paperIds));
        return preview;
    }

    @Transactional(readOnly = true)
    public EffectiveConversationScope resolveForChat(Long userId, String conversationId) {
        return resolveSession(requireOwnedSession(userId, conversationId));
    }

    @Transactional(noRollbackFor = CustomException.class)
    public EffectiveConversationScope lockForFirstMessage(Long userId, String conversationId) {
        ConversationSession session = requireOwnedSessionForUpdate(userId, conversationId);
        if (session.isScopeLocked()) {
            return resolveSession(session);
        }

        ConversationScopeMode mode = scopeModeOrDefault(session.getScopeMode());
        if (mode == ConversationScopeMode.SOURCE_SET_SNAPSHOT) {
            List<String> paperIds = snapshotPaperIds(session.getSourceSnapshotJson());
            if (paperIds.isEmpty() || !snapshotPaperIdsStillValid(resolveUser(userId), paperIds)) {
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
                                                ProductReferenceFocus referenceFocus) {
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
        return scopeResponse(null, scope);
    }

    public Map<String, Object> scopeResponse(Long userId, EffectiveConversationScope scope) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scopeMode", scope.mode().name());
        response.put("scopeLocked", scope.locked());
        response.put("scopeStatus", scope.status().name());
        response.put("sourceLabel", scope.label());
        response.put("sourcePaperCount", sourcePaperCount(userId, scope));
        response.put("paperIds", scope.paperIds());
        response.put("sourceRecipe", scope.sourceRecipe() != null && !scope.sourceRecipe().isEmpty()
                ? scope.sourceRecipe()
                : null);
        return response;
    }

    public Integer autoLibraryReadablePaperCount(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = resolveUser(userId);
        LinkedHashSet<String> countedPaperIds = new LinkedHashSet<>();
        int count = 0;
        for (Paper paper : accessiblePapersForTitleMatch(user)) {
            String paperId = trimToNull(paper == null ? null : paper.getPaperId());
            if (paper == null
                    || paperId == null
                    || !canAccessPaper(user, paper)
                    || !paperSearchabilityService.isSearchable(paper)
                    || !hasCurrentReadyReadingModel(paperId)) {
                continue;
            }
            if (countedPaperIds.add(paperId)) {
                count += 1;
            }
        }
        return count;
    }

    private Integer sourcePaperCount(Long userId, EffectiveConversationScope scope) {
        if (scope == null) {
            return null;
        }
        if (scope.mode() == ConversationScopeMode.SOURCE_SET_SNAPSHOT) {
            return scope.paperIds().size();
        }
        return autoLibraryReadablePaperCount(userId);
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

    private ConversationSession requireOwnedSessionForUpdate(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            throw new CustomException("Conversation not found", HttpStatus.NOT_FOUND);
        }
        return sessionRepository.findByConversationIdAndUserIdForUpdate(conversationId, userId)
                .orElseThrow(() -> new CustomException("Conversation not found", HttpStatus.NOT_FOUND));
    }

    private User resolveUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
    }

    public List<String> authorizedPaperIdsForHarness(Long userId, EffectiveConversationScope scope) {
        User user = resolveUser(userId);
        if (scope != null && scope.mode() == ConversationScopeMode.SOURCE_SET_SNAPSHOT) {
            return resolveDirectPaperIds(user, scope.paperIds());
        }
        return accessiblePapersForTitleMatch(user).stream()
                .filter(paper -> canAccessPaper(user, paper))
                .filter(paperSearchabilityService::isSearchable)
                .map(Paper::getPaperId)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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
        List<String> resolved = new ArrayList<>();
        for (String paperId : paperIds) {
            List<Paper> papers = papersByPaperId.getOrDefault(paperId, List.of());
            if (papers.isEmpty()) {
                throw new CustomException("Paper not found: " + paperId, HttpStatus.BAD_REQUEST);
            }
            boolean accessible = papers.stream().anyMatch(paper -> canAccessPaper(user, paper));
            if (!accessible) {
                throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
            }
            boolean searchable = papers.stream()
                    .filter(paper -> canAccessPaper(user, paper))
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
        return paperIds.stream()
                .filter(paperId -> papersByPaperId.getOrDefault(paperId, List.of()).stream()
                        .filter(paper -> canAccessPaper(user, paper))
                        .anyMatch(paperSearchabilityService::isSearchable))
                .toList();
    }

    private List<String> resolveTitleMatchPaperIds(User user,
                                                   UpdateConversationScopeRequest request,
                                                   boolean requireMatches) {
        String titleQuery = trimToNull(request == null ? null : request.titleQuery());
        String titleRegex = trimToNull(request == null ? null : request.titleRegex());
        if (titleQuery == null && titleRegex == null) {
            return List.of();
        }
        if (titleQuery != null && titleRegex != null) {
            throw new CustomException("Use either titleQuery or titleRegex", HttpStatus.BAD_REQUEST);
        }
        Pattern regex = null;
        if (titleRegex != null) {
            try {
                regex = Pattern.compile(titleRegex);
            } catch (PatternSyntaxException exception) {
                throw new CustomException("Invalid title regex", HttpStatus.BAD_REQUEST);
            }
        }
        String query = titleQuery == null ? null : titleQuery.toLowerCase();
        Pattern safeRegex = regex;
        LinkedHashSet<String> paperIds = accessiblePapersForTitleMatch(user).stream()
                .filter(paper -> canAccessPaper(user, paper))
                .filter(paperSearchabilityService::isSearchable)
                .filter(paper -> titleMatches(paper, query, safeRegex))
                .map(Paper::getPaperId)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requireMatches && paperIds.isEmpty()) {
            throw new CustomException("Title match resolved to no searchable papers", HttpStatus.BAD_REQUEST);
        }
        return new ArrayList<>(paperIds);
    }

    private List<Paper> accessiblePapersForTitleMatch(User user) {
        return paperAccessService.accessiblePapers(String.valueOf(user.getId()));
    }

    private boolean titleMatches(Paper paper, String titleQuery, Pattern titleRegex) {
        String title = trimToNull(paper == null ? null : paper.getPaperTitle());
        String filename = trimToNull(paper == null ? null : paper.getOriginalFilename());
        if (titleRegex != null) {
            return (title != null && titleRegex.matcher(title).find())
                    || (filename != null && titleRegex.matcher(filename).find());
        }
        if (titleQuery == null) {
            return false;
        }
        String normalizedTitle = title == null ? "" : title.toLowerCase();
        String normalizedFilename = filename == null ? "" : filename.toLowerCase();
        return normalizedTitle.contains(titleQuery) || normalizedFilename.contains(titleQuery);
    }

    private Map<String, Object> paperPreview(Paper paper) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("paperId", paper.getPaperId());
        item.put("paperTitle", paper.getPaperTitle());
        item.put("originalFilename", paper.getOriginalFilename());
        item.put("authors", paper.getAuthors());
        item.put("venue", paper.getVenue());
        item.put("publicationYear", paper.getPublicationYear());
        return item;
    }

    private boolean snapshotPaperIdsStillValid(User user, List<String> paperIds) {
        Map<String, List<Paper>> papersByPaperId = productPapersByPaperId(paperIds);
        for (String paperId : paperIds) {
            List<Paper> papers = papersByPaperId.getOrDefault(paperId, List.of());
            if (papers.isEmpty()) {
                return false;
            }
            boolean accessible = papers.stream().anyMatch(paper -> canAccessPaper(user, paper));
            if (!accessible) {
                return false;
            }
            boolean searchable = papers.stream()
                    .filter(paper -> canAccessPaper(user, paper))
                    .anyMatch(paperSearchabilityService::isSearchable);
            if (!searchable) {
                return false;
            }
        }
        return true;
    }

    private boolean hasCurrentReadyReadingModel(String paperId) {
        String normalizedPaperId = trimToNull(paperId);
        if (normalizedPaperId == null) {
            return false;
        }
        return readingModelRepository.findFirstByPaperIdAndIsCurrentTrue(normalizedPaperId)
                .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                .isPresent();
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

    private boolean canAccessPaper(User user, Paper paper) {
        if (user == null || paper == null) {
            return false;
        }
        return paperAccessService.canAccess(String.valueOf(user.getId()), paper.getPaperId());
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

    private String sourceLabel(UpdateConversationScopeRequest request,
                               List<Long> collectionIds,
                               List<String> directPaperIds,
                               List<String> titleMatchPaperIds) {
        if (request != null && (!titleMatchPaperIds.isEmpty()
                || trimToNull(request.titleQuery()) != null
                || trimToNull(request.titleRegex()) != null)) {
            return titleMatchLabel(request, titleMatchPaperIds);
        }
        String requestedLabel = request == null ? null : request.sourceLabel();
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
                                             List<String> directPaperIds,
                                             List<String> titleMatchPaperIds) {
        if (request != null && (!titleMatchPaperIds.isEmpty()
                || trimToNull(request.titleQuery()) != null
                || trimToNull(request.titleRegex()) != null)) {
            return titleMatchRecipe(request, titleMatchPaperIds);
        }
        if (request != null && request.sourceRecipe() != null && !request.sourceRecipe().isEmpty()) {
            return new LinkedHashMap<>(request.sourceRecipe());
        }
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("scopeMode", ConversationScopeMode.SOURCE_SET_SNAPSHOT.name());
        recipe.put("collectionIds", collectionIds);
        recipe.put("paperIds", directPaperIds);
        return recipe;
    }

    private String titleMatchLabel(UpdateConversationScopeRequest request, List<String> paperIds) {
        String titleQuery = trimToNull(request == null ? null : request.titleQuery());
        String titleRegex = trimToNull(request == null ? null : request.titleRegex());
        String matcher = titleRegex != null ? "/" + titleRegex + "/" : titleQuery;
        return "Title match: " + matcher + " (" + paperIds.size() + " papers)";
    }

    private Map<String, Object> titleMatchRecipe(UpdateConversationScopeRequest request, List<String> paperIds) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("type", "title_match");
        recipe.put("titleQuery", trimToNull(request == null ? null : request.titleQuery()));
        recipe.put("titleRegex", trimToNull(request == null ? null : request.titleRegex()));
        recipe.put("paperCount", paperIds == null ? 0 : paperIds.size());
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
