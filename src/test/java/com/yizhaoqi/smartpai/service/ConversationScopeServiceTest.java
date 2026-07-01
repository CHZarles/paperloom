package com.yizhaoqi.smartpai.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationScopeServiceTest {

    @Mock
    private ConversationSessionRepository sessionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperCollectionService paperCollectionService;

    @Mock
    private PaperSearchabilityService paperSearchabilityService;

    @Mock
    private OrgTagCacheService orgTagCacheService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ConversationScopeService service;
    private User user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        user = user(1L, "owner", User.Role.USER, "default", "default");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("owner")).thenReturn(List.of("default"));
        when(sessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paperRepository.findByPaperIdIn(anyList())).thenReturn(List.of());

        service = new ConversationScopeService(
                sessionRepository,
                userRepository,
                paperRepository,
                paperCollectionService,
                paperSearchabilityService,
                orgTagCacheService,
                objectMapper
        );
    }

    @Test
    void newSessionDefaultsToUnlockedAutoLibrary() {
        ConversationSession session = new ConversationSession();

        assertEquals(ConversationScopeMode.AUTO_LIBRARY, session.getScopeMode());
        assertEquals(false, session.isScopeLocked());
        assertEquals(ConversationScopeStatus.READY, session.getScopeStatus());

        Map<String, Object> defaults = service.defaultScope();

        assertEquals("AUTO_LIBRARY", defaults.get("scopeMode"));
        assertEquals(false, defaults.get("scopeLocked"));
        assertEquals("READY", defaults.get("scopeStatus"));
        assertEquals("All searchable papers", defaults.get("sourceLabel"));
        assertNull(defaults.get("sourcePaperCount"));
        assertIterableEquals(List.of(), (List<?>) defaults.get("paperIds"));
    }

    @Test
    void unlockedSessionCanUpdateToSnapshot() throws Exception {
        ConversationSession session = ownedSession("conversation-1");
        Paper first = paper("paper-1", "1", false, "default");
        Paper second = paper("paper-2", "1", false, "default");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findByPaperIdIn(List.of("paper-1", "paper-2"))).thenReturn(List.of(first, second));
        when(paperSearchabilityService.isSearchable(first)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(second)).thenReturn(true);

        Map<String, Object> result = service.updateUnlockedScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        "Custom reading set",
                        null,
                        List.of(" paper-1 ", "paper-2", "paper-1"),
                        Map.of("kind", "manual")
                )
        );

        assertEquals(ConversationScopeMode.SOURCE_SET_SNAPSHOT, session.getScopeMode());
        assertEquals(ConversationScopeStatus.READY, session.getScopeStatus());
        assertEquals(2, session.getSourcePaperCount());
        assertEquals("Custom reading set", session.getSourceLabel());
        Map<?, ?> snapshot = objectMapper.readValue(session.getSourceSnapshotJson(), Map.class);
        assertIterableEquals(List.of("paper-1", "paper-2"), (List<?>) snapshot.get("paperIds"));
        assertEquals(2, snapshot.get("paperCount"));
        assertIterableEquals(List.of("paper-1", "paper-2"), (List<?>) result.get("paperIds"));
        assertEquals(2, result.get("sourcePaperCount"));
        assertEquals(Map.of("kind", "manual"), result.get("sourceRecipe"));
        verify(sessionRepository).save(session);
    }

    @Test
    void lockedSessionRejectsScopeUpdate() {
        ConversationSession session = ownedSession("conversation-1");
        session.setScopeLocked(true);
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));

        CustomException exception = assertThrows(CustomException.class, () -> service.updateUnlockedScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest("AUTO_LIBRARY", null, null, null, null)
        ));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(sessionRepository, never()).save(session);
    }

    @Test
    void updateAndLockMutatingPathsUsePessimisticLockedLookup() {
        ConversationSession updateSession = ownedSession("update-conversation");
        ConversationSession lockSession = ownedSession("lock-conversation");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("update-conversation", 1L))
                .thenReturn(Optional.of(updateSession));
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("lock-conversation", 1L))
                .thenReturn(Optional.of(lockSession));

        service.updateUnlockedScope(
                1L,
                "update-conversation",
                new UpdateConversationScopeRequest("AUTO_LIBRARY", null, null, null, null)
        );
        service.lockForFirstMessage(1L, "lock-conversation");

        verify(sessionRepository).findByConversationIdAndUserIdForUpdate("update-conversation", 1L);
        verify(sessionRepository).findByConversationIdAndUserIdForUpdate("lock-conversation", 1L);
        verify(sessionRepository, never()).findByConversationIdAndUserId("update-conversation", 1L);
        verify(sessionRepository, never()).findByConversationIdAndUserId("lock-conversation", 1L);
    }

    @Test
    void lockAutoLibraryScopeForFirstMessage() {
        ConversationSession session = ownedSession("conversation-1");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));

        ConversationScopeService.EffectiveConversationScope scope = service.lockForFirstMessage(1L, "conversation-1");

        assertEquals(ConversationScopeMode.AUTO_LIBRARY, scope.mode());
        assertEquals(ConversationScopeStatus.READY, scope.status());
        assertEquals(true, scope.locked());
        assertEquals(true, session.isScopeLocked());
        verify(sessionRepository).save(session);
    }

    @Test
    void lockValidSnapshotScopeForFirstMessage() throws Exception {
        ConversationSession session = snapshotSession("conversation-1", List.of("paper-1", "paper-2"));
        Paper first = paper("paper-1", "1", false, "default");
        Paper second = paper("paper-2", "1", false, "default");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findByPaperIdIn(List.of("paper-1", "paper-2"))).thenReturn(List.of(first, second));
        when(paperSearchabilityService.isSearchable(first)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(second)).thenReturn(true);

        ConversationScopeService.EffectiveConversationScope scope = service.lockForFirstMessage(1L, "conversation-1");

        assertEquals(ConversationScopeMode.SOURCE_SET_SNAPSHOT, scope.mode());
        assertEquals(ConversationScopeStatus.READY, scope.status());
        assertEquals(true, scope.locked());
        assertIterableEquals(List.of("paper-1", "paper-2"), scope.paperIds());
        assertEquals(true, session.isScopeLocked());
        verify(sessionRepository).save(session);
    }

    @Test
    void alreadyLockedScopeIsIdempotent() throws Exception {
        ConversationSession session = snapshotSession("conversation-1", List.of("paper-1"));
        session.setScopeLocked(true);
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));

        ConversationScopeService.EffectiveConversationScope scope = service.lockForFirstMessage(1L, "conversation-1");

        assertEquals(ConversationScopeMode.SOURCE_SET_SNAPSHOT, scope.mode());
        assertEquals(true, scope.locked());
        assertIterableEquals(List.of("paper-1"), scope.paperIds());
        verify(sessionRepository, never()).save(session);
        verify(paperRepository, never()).findByPaperIdIn(anyList());
    }

    @Test
    void lockRejectsEmptySnapshotScope() {
        ConversationSession session = ownedSession("conversation-1");
        session.setScopeMode(ConversationScopeMode.SOURCE_SET_SNAPSHOT);
        session.setScopeStatus(ConversationScopeStatus.READY);
        session.setSourceSnapshotJson("{\"paperIds\":[],\"paperCount\":0}");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));

        CustomException exception = assertThrows(CustomException.class,
                () -> service.lockForFirstMessage(1L, "conversation-1"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(ConversationScopeStatus.INVALID, session.getScopeStatus());
        assertEquals(false, session.isScopeLocked());
        verify(sessionRepository).save(session);
    }

    @Test
    void lockRejectsSnapshotWithMissingPaper() throws Exception {
        ConversationSession session = snapshotSession("conversation-1", List.of("missing-paper"));
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findByPaperIdIn(List.of("missing-paper"))).thenReturn(List.of());

        CustomException exception = assertThrows(CustomException.class,
                () -> service.lockForFirstMessage(1L, "conversation-1"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(ConversationScopeStatus.INVALID, session.getScopeStatus());
        assertEquals(false, session.isScopeLocked());
        verify(sessionRepository).save(session);
    }

    @Test
    void lockRejectsSnapshotWithInaccessiblePaper() throws Exception {
        ConversationSession session = snapshotSession("conversation-1", List.of("inaccessible-paper"));
        Paper inaccessible = paper("inaccessible-paper", "2", false, "other");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findByPaperIdIn(List.of("inaccessible-paper"))).thenReturn(List.of(inaccessible));

        CustomException exception = assertThrows(CustomException.class,
                () -> service.lockForFirstMessage(1L, "conversation-1"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(ConversationScopeStatus.INVALID, session.getScopeStatus());
        assertEquals(false, session.isScopeLocked());
        verify(sessionRepository).save(session);
    }

    @Test
    void lockRejectsSnapshotWithUnsearchablePaper() throws Exception {
        ConversationSession session = snapshotSession("conversation-1", List.of("unsearchable-paper"));
        Paper unsearchable = paper("unsearchable-paper", "1", false, "default");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findByPaperIdIn(List.of("unsearchable-paper"))).thenReturn(List.of(unsearchable));
        when(paperSearchabilityService.isSearchable(unsearchable)).thenReturn(false);

        CustomException exception = assertThrows(CustomException.class,
                () -> service.lockForFirstMessage(1L, "conversation-1"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(ConversationScopeStatus.INVALID, session.getScopeStatus());
        assertEquals(false, session.isScopeLocked());
        verify(sessionRepository).save(session);
    }

    @Test
    void collectionScopeResolvesSearchableAccessiblePapersOnly() throws Exception {
        ConversationSession session = ownedSession("conversation-1");
        Paper searchable = paper("searchable-paper", "1", false, "default");
        Paper unsearchable = paper("unsearchable-paper", "1", false, "default");
        Paper inaccessible = paper("inaccessible-paper", "2", false, "other");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperCollectionService.getCollection(1L, 11L)).thenReturn(Map.of(
                "id", 11L,
                "name", "Collection",
                "paperIds", List.of("searchable-paper", "unsearchable-paper", "inaccessible-paper")
        ));
        when(paperRepository.findByPaperIdIn(List.of("searchable-paper", "unsearchable-paper", "inaccessible-paper")))
                .thenReturn(List.of(searchable, unsearchable, inaccessible));
        when(paperSearchabilityService.isSearchable(searchable)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(unsearchable)).thenReturn(false);
        when(paperSearchabilityService.isSearchable(inaccessible)).thenReturn(true);

        Map<String, Object> result = service.updateUnlockedScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        List.of(11L),
                        null,
                        null
                )
        );

        assertEquals("Selected collections", session.getSourceLabel());
        assertEquals(1, session.getSourcePaperCount());
        Map<?, ?> snapshot = objectMapper.readValue(session.getSourceSnapshotJson(), Map.class);
        assertIterableEquals(List.of("searchable-paper"), (List<?>) snapshot.get("paperIds"));
        assertIterableEquals(List.of("searchable-paper"), (List<?>) result.get("paperIds"));
    }

    @Test
    void emptyResolvedCollectionScopeIsRejected() {
        ConversationSession session = ownedSession("conversation-1");
        Paper unsearchable = paper("unsearchable-paper", "1", false, "default");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperCollectionService.getCollection(1L, 11L)).thenReturn(Map.of(
                "id", 11L,
                "name", "Collection",
                "paperIds", List.of("unsearchable-paper")
        ));
        when(paperRepository.findByPaperIdIn(List.of("unsearchable-paper"))).thenReturn(List.of(unsearchable));
        when(paperSearchabilityService.isSearchable(unsearchable)).thenReturn(false);

        CustomException exception = assertThrows(CustomException.class, () -> service.updateUnlockedScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        List.of(11L),
                        null,
                        null
                )
        ));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(sessionRepository, never()).save(session);
    }

    @Test
    void titleQueryPreviewReturnsOnlySearchableAccessibleMatches() {
        ConversationSession session = ownedSession("conversation-1");
        Paper lora = paper("paper-lora", "1", false, "default");
        lora.setPaperTitle("LoRA: Low-Rank Adaptation of Large Language Models");
        lora.setOriginalFilename("lora.pdf");
        Paper unsearchable = paper("paper-unsearchable", "1", false, "default");
        unsearchable.setPaperTitle("LoRA implementation note");
        Paper unrelated = paper("paper-transformer", "1", false, "default");
        unrelated.setPaperTitle("Attention Is All You Need");
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findAccessiblePapersWithTags("1", List.of("default")))
                .thenReturn(List.of(lora, unsearchable, unrelated));
        when(paperSearchabilityService.isSearchable(lora)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(unsearchable)).thenReturn(false);
        when(paperSearchabilityService.isSearchable(unrelated)).thenReturn(true);
        when(paperRepository.findByPaperIdIn(List.of("paper-lora"))).thenReturn(List.of(lora));

        Map<String, Object> preview = service.previewTitleMatchScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        null,
                        null,
                        null,
                        "lora",
                        null
                )
        );

        assertEquals(1, preview.get("paperCount"));
        assertIterableEquals(List.of("paper-lora"), (List<?>) preview.get("paperIds"));
        assertEquals("Title match: lora (1 papers)", preview.get("sourceLabel"));
        Map<?, ?> recipe = (Map<?, ?>) preview.get("sourceRecipe");
        assertEquals("title_match", recipe.get("type"));
        assertEquals("lora", recipe.get("titleQuery"));
        assertNull(recipe.get("titleRegex"));
        assertEquals(1, recipe.get("paperCount"));
        List<?> papers = (List<?>) preview.get("papers");
        assertEquals(1, papers.size());
        assertEquals("LoRA: Low-Rank Adaptation of Large Language Models", ((Map<?, ?>) papers.get(0)).get("paperTitle"));
    }

    @Test
    void titleRegexPreviewMatchesTitleOrFilename() {
        ConversationSession session = ownedSession("conversation-1");
        Paper filenameMatch = paper("paper-lora", "1", false, "default");
        filenameMatch.setPaperTitle("Low rank adapters");
        filenameMatch.setOriginalFilename("lora-notes.pdf");
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findAccessiblePapersWithTags("1", List.of("default"))).thenReturn(List.of(filenameMatch));
        when(paperSearchabilityService.isSearchable(filenameMatch)).thenReturn(true);
        when(paperRepository.findByPaperIdIn(List.of("paper-lora"))).thenReturn(List.of(filenameMatch));

        Map<String, Object> preview = service.previewTitleMatchScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "(?i)^lora"
                )
        );

        assertIterableEquals(List.of("paper-lora"), (List<?>) preview.get("paperIds"));
        Map<?, ?> recipe = (Map<?, ?>) preview.get("sourceRecipe");
        assertNull(recipe.get("titleQuery"));
        assertEquals("(?i)^lora", recipe.get("titleRegex"));
    }

    @Test
    void titleMatchSnapshotStoresResolvedPaperIdsAndRecipe() throws Exception {
        ConversationSession session = ownedSession("conversation-1");
        Paper lora = paper("paper-lora", "1", false, "default");
        lora.setPaperTitle("LoRA: Low-Rank Adaptation of Large Language Models");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findAccessiblePapersWithTags("1", List.of("default"))).thenReturn(List.of(lora));
        when(paperSearchabilityService.isSearchable(lora)).thenReturn(true);

        Map<String, Object> result = service.updateUnlockedScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        null,
                        null,
                        null,
                        "lora",
                        null
                )
        );

        assertEquals(ConversationScopeMode.SOURCE_SET_SNAPSHOT, session.getScopeMode());
        assertEquals("Title match: lora (1 papers)", session.getSourceLabel());
        assertEquals(1, session.getSourcePaperCount());
        Map<?, ?> snapshot = objectMapper.readValue(session.getSourceSnapshotJson(), Map.class);
        assertIterableEquals(List.of("paper-lora"), (List<?>) snapshot.get("paperIds"));
        Map<?, ?> recipe = objectMapper.readValue(session.getSourceRecipeJson(), Map.class);
        assertEquals("title_match", recipe.get("type"));
        assertEquals("lora", recipe.get("titleQuery"));
        assertEquals(1, recipe.get("paperCount"));
        assertIterableEquals(List.of("paper-lora"), (List<?>) result.get("paperIds"));
        verify(sessionRepository).save(session);
    }

    @Test
    void invalidTitleRegexIsRejected() {
        ConversationSession session = ownedSession("conversation-1");
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L)).thenReturn(Optional.of(session));

        CustomException exception = assertThrows(CustomException.class, () -> service.previewTitleMatchScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "["
                )
        ));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void titleMatchPreviewRejectsLockedSession() {
        ConversationSession session = ownedSession("conversation-1");
        session.setScopeLocked(true);
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L)).thenReturn(Optional.of(session));

        CustomException exception = assertThrows(CustomException.class, () -> service.previewTitleMatchScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        null,
                        null,
                        null,
                        "lora",
                        null
                )
        ));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void titleMatchSnapshotRejectsNoMatches() {
        ConversationSession session = ownedSession("conversation-1");
        when(sessionRepository.findByConversationIdAndUserIdForUpdate("conversation-1", 1L)).thenReturn(Optional.of(session));
        when(paperRepository.findAccessiblePapersWithTags("1", List.of("default"))).thenReturn(List.of());

        CustomException exception = assertThrows(CustomException.class, () -> service.updateUnlockedScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        null,
                        null,
                        null,
                        "lora",
                        null
                )
        ));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(sessionRepository, never()).save(session);
    }

    @Test
    void titleMatchRejectsQueryAndRegexTogether() {
        ConversationSession session = ownedSession("conversation-1");
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L)).thenReturn(Optional.of(session));

        CustomException exception = assertThrows(CustomException.class, () -> service.previewTitleMatchScope(
                1L,
                "conversation-1",
                new UpdateConversationScopeRequest(
                        "SOURCE_SET_SNAPSHOT",
                        null,
                        null,
                        null,
                        null,
                        "lora",
                        "lora"
                )
        ));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void resolveForChatSafelyParsesStoredRecipeWithNullValues() {
        ConversationSession session = ownedSession("conversation-1");
        session.setSourceRecipeJson("{\"kind\":null}");
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L)).thenReturn(Optional.of(session));

        ConversationScopeService.EffectiveConversationScope scope = service.resolveForChat(1L, "conversation-1");

        assertEquals(null, scope.sourceRecipe().get("kind"));
    }

    @Test
    void referenceFocusInsideSnapshotIsAllowed() {
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.SOURCE_SET_SNAPSHOT,
                        ConversationScopeStatus.READY,
                        true,
                        "Selected papers",
                        List.of("paper-1", "paper-2"),
                        Map.of()
                );

        ProductReferenceFocus referenceFocus = new ProductReferenceFocus(
                List.of("paper-1"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertDoesNotThrow(() -> service.assertReferenceFocusWithinScope(scope, referenceFocus));
    }

    @Test
    void referenceFocusOutsideSnapshotIsRejected() {
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.SOURCE_SET_SNAPSHOT,
                        ConversationScopeStatus.READY,
                        true,
                        "Selected papers",
                        List.of("paper-1"),
                        Map.of()
                );

        ProductReferenceFocus referenceFocus = new ProductReferenceFocus(
                List.of("paper-2"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        CustomException exception = assertThrows(CustomException.class,
                () -> service.assertReferenceFocusWithinScope(scope, referenceFocus));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    private ConversationSession ownedSession(String conversationId) {
        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(conversationId);
        session.setTitle("New conversation");
        return session;
    }

    private ConversationSession snapshotSession(String conversationId, List<String> paperIds) throws Exception {
        ConversationSession session = ownedSession(conversationId);
        session.setScopeMode(ConversationScopeMode.SOURCE_SET_SNAPSHOT);
        session.setScopeStatus(ConversationScopeStatus.READY);
        session.setSourceLabel("Selected papers");
        session.setSourcePaperCount(paperIds.size());
        session.setSourceSnapshotJson(objectMapper.writeValueAsString(Map.of(
                "paperIds", paperIds,
                "paperCount", paperIds.size()
        )));
        return session;
    }

    private User user(Long id, String username, User.Role role, String primaryOrg, String orgTags) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setPrimaryOrg(primaryOrg);
        user.setOrgTags(orgTags);
        return user;
    }

    private Paper paper(String paperId, String userId, boolean isPublic, String orgTag) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setPaperTitle(paperId);
        paper.setOriginalFilename(paperId + ".pdf");
        paper.setUserId(userId);
        paper.setPublic(isPublic);
        paper.setOrgTag(orgTag);
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        return paper;
    }
}
