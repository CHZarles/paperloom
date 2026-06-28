package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.controller.dto.CollectionRequests.AddCollectionPapersRequest;
import com.yizhaoqi.smartpai.controller.dto.CollectionRequests.CreateCollectionRequest;
import com.yizhaoqi.smartpai.controller.dto.CollectionRequests.UpdateCollectionRequest;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperCollection;
import com.yizhaoqi.smartpai.model.PaperCollectionPaper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.PaperCollectionPaperRepository;
import com.yizhaoqi.smartpai.repository.PaperCollectionRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperCollectionServiceTest {

    @Mock
    private PaperCollectionRepository collectionRepository;

    @Mock
    private PaperCollectionPaperRepository collectionPaperRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperSearchabilityService paperSearchabilityService;

    @Mock
    private OrgTagCacheService orgTagCacheService;

    private final List<PaperCollection> collections = new ArrayList<>();
    private final List<PaperCollectionPaper> memberships = new ArrayList<>();
    private final List<Paper> papers = new ArrayList<>();
    private final AtomicLong collectionIds = new AtomicLong(10);
    private final AtomicLong membershipIds = new AtomicLong(100);

    private User owner;
    private User otherUser;
    private User labUser;
    private User admin;
    private PaperCollectionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        owner = user(1L, "owner", User.Role.USER, "default", "default");
        otherUser = user(2L, "other", User.Role.USER, "other", "other");
        labUser = user(3L, "lab-user", User.Role.USER, "lab", "lab,ml");
        admin = user(4L, "admin", User.Role.ADMIN, "admin", "admin");

        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(userRepository.findById(2L)).thenReturn(Optional.of(otherUser));
        when(userRepository.findById(3L)).thenReturn(Optional.of(labUser));
        when(userRepository.findById(4L)).thenReturn(Optional.of(admin));
        when(orgTagCacheService.getUserEffectiveOrgTags("owner")).thenReturn(List.of("default"));
        when(orgTagCacheService.getUserEffectiveOrgTags("other")).thenReturn(List.of("other"));
        when(orgTagCacheService.getUserEffectiveOrgTags("lab-user")).thenReturn(List.of("lab", "ml"));
        when(orgTagCacheService.getUserEffectiveOrgTags("admin")).thenReturn(List.of("admin"));

        when(collectionRepository.save(any(PaperCollection.class))).thenAnswer(invocation -> {
            PaperCollection collection = invocation.getArgument(0);
            if (collection.getId() == null) {
                ReflectionTestUtils.setField(collection, "id", collectionIds.incrementAndGet());
                ReflectionTestUtils.setField(collection, "createdAt", LocalDateTime.now());
            }
            ReflectionTestUtils.setField(collection, "updatedAt", LocalDateTime.now());
            collections.removeIf(existing -> existing.getId().equals(collection.getId()));
            collections.add(collection);
            return collection;
        });
        when(collectionRepository.findById(any(Long.class))).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return collections.stream().filter(collection -> collection.getId().equals(id)).findFirst();
        });
        when(collectionRepository.findByOwnerIdOrderByUpdatedAtDesc(any(Long.class))).thenAnswer(invocation -> {
            Long ownerId = invocation.getArgument(0);
            return collections.stream()
                    .filter(collection -> collection.getOwner().getId().equals(ownerId))
                    .sorted(Comparator.comparing(PaperCollection::getUpdatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .toList();
        });
        when(collectionRepository.findAllByOrderByUpdatedAtDesc()).thenAnswer(invocation -> collections.stream()
                .sorted(Comparator.comparing(PaperCollection::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList());
        when(collectionRepository.findOrgVisibleCollections(anyList())).thenAnswer(invocation -> {
            List<String> orgTags = invocation.getArgument(0);
            return collections.stream()
                    .filter(collection -> collection.getVisibility() == PaperCollection.Visibility.ORG)
                    .filter(collection -> orgTags.contains(collection.getOrgTag()))
                    .sorted(Comparator.comparing(PaperCollection::getUpdatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .toList();
        });

        when(collectionPaperRepository.save(any(PaperCollectionPaper.class))).thenAnswer(invocation -> {
            PaperCollectionPaper membership = invocation.getArgument(0);
            if (membership.getId() == null) {
                ReflectionTestUtils.setField(membership, "id", membershipIds.incrementAndGet());
                ReflectionTestUtils.setField(membership, "createdAt", LocalDateTime.now());
            }
            memberships.removeIf(existing -> existing.getCollection().getId().equals(membership.getCollection().getId())
                    && existing.getPaperId().equals(membership.getPaperId()));
            memberships.add(membership);
            return membership;
        });
        when(collectionPaperRepository.findByCollectionIdOrderByCreatedAtAsc(any(Long.class))).thenAnswer(invocation -> {
            Long collectionId = invocation.getArgument(0);
            return memberships.stream()
                    .filter(membership -> membership.getCollection().getId().equals(collectionId))
                    .sorted(Comparator.comparing(PaperCollectionPaper::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        });
        when(collectionPaperRepository.existsByCollectionIdAndPaperId(any(Long.class), any(String.class))).thenAnswer(invocation -> {
            Long collectionId = invocation.getArgument(0);
            String paperId = invocation.getArgument(1);
            return memberships.stream().anyMatch(membership -> membership.getCollection().getId().equals(collectionId)
                    && membership.getPaperId().equals(paperId));
        });

        when(paperRepository.findByPaperIdIn(anyList())).thenAnswer(invocation -> {
            List<String> paperIds = invocation.getArgument(0);
            Set<String> requested = new LinkedHashSet<>(paperIds);
            return papers.stream().filter(paper -> requested.contains(paper.getPaperId())).toList();
        });

        service = new PaperCollectionService(
                collectionRepository,
                collectionPaperRepository,
                userRepository,
                paperRepository,
                paperSearchabilityService,
                orgTagCacheService
        );
    }

    @Test
    void ownerCanCreatePrivateCollection() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("  Agent papers  ", "Agent system reading set", null, null)
        );

        assertEquals("Agent papers", created.get("name"));
        assertEquals("PRIVATE", created.get("visibility"));

        List<Map<String, Object>> ownerVisible = service.listCollections(owner.getId());
        List<Map<String, Object>> otherVisible = service.listCollections(otherUser.getId());

        assertEquals(1, ownerVisible.size());
        assertEquals("Agent papers", ownerVisible.get(0).get("name"));
        assertTrue(otherVisible.isEmpty());
    }

    @Test
    void orgCollectionIsVisibleToSameOrgUser() {
        service.createCollection(
                admin.getId(),
                new CreateCollectionRequest("Lab RAG", "Shared lab reading set", "ORG", "lab")
        );

        List<Map<String, Object>> labVisible = service.listCollections(labUser.getId());

        assertEquals(1, labVisible.size());
        assertEquals("Lab RAG", labVisible.get(0).get("name"));
        assertEquals("ORG", labVisible.get(0).get("visibility"));
        assertEquals("lab", labVisible.get(0).get("orgTag"));
    }

    @Test
    void ordinaryOrgUserCannotEditOrgCollection() {
        Map<String, Object> created = service.createCollection(
                admin.getId(),
                new CreateCollectionRequest("Lab RAG", "Shared lab reading set", "ORG", "lab")
        );

        CustomException exception = assertThrows(CustomException.class, () -> service.updateCollection(
                labUser.getId(),
                (Long) created.get("id"),
                new UpdateCollectionRequest("Edited", "Nope", "ORG", "lab")
        ));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void adminCanEditOrgCollection() {
        Map<String, Object> created = service.createCollection(
                labUser.getId(),
                new CreateCollectionRequest("Lab RAG", "Shared lab reading set", "ORG", "lab")
        );

        Map<String, Object> updated = service.updateCollection(
                admin.getId(),
                (Long) created.get("id"),
                new UpdateCollectionRequest("Updated Lab RAG", "Admin update", "ORG", "lab")
        );

        assertEquals("Updated Lab RAG", updated.get("name"));
        assertEquals("Admin update", updated.get("description"));
    }

    @Test
    void adminCanSeeAllCollectionsInList() {
        service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Owner private", "Private set", "PRIVATE", null)
        );
        service.createCollection(
                labUser.getId(),
                new CreateCollectionRequest("Lab org", "Lab shared set", "ORG", "lab")
        );

        List<Map<String, Object>> adminVisible = service.listCollections(admin.getId());

        assertEquals(2, adminVisible.size());
        assertEquals(List.of("Lab org", "Owner private"),
                adminVisible.stream().map(item -> item.get("name")).toList());
    }

    @Test
    void adminCanEditPrivateCollectionById() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Owner private", "Private set", "PRIVATE", null)
        );

        Map<String, Object> updated = service.updateCollection(
                admin.getId(),
                (Long) created.get("id"),
                new UpdateCollectionRequest("Admin edited private", "Documented admin policy", "PRIVATE", null)
        );

        assertEquals("Admin edited private", updated.get("name"));
        assertEquals("Documented admin policy", updated.get("description"));
        assertEquals("PRIVATE", updated.get("visibility"));
    }

    @Test
    void nonAdminCannotCreateOrgCollectionOutsideOwnOrg() {
        CustomException exception = assertThrows(CustomException.class, () -> service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Other org", "Outside tenant", "ORG", "other")
        ));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void nonAdminCannotMoveOrgCollectionOutsideOwnOrg() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Default org", "Inside tenant", "ORG", "default")
        );

        CustomException exception = assertThrows(CustomException.class, () -> service.updateCollection(
                owner.getId(),
                (Long) created.get("id"),
                new UpdateCollectionRequest("Moved org", "Outside tenant", "ORG", "other")
        ));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void addPapersStoresStaticPaperIds() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Agent papers", "Static set", "PRIVATE", null)
        );
        Long collectionId = (Long) created.get("id");
        Paper p1 = paper("paper-1", true);
        Paper p2 = paper("paper-2", true);
        Paper p3 = paper("paper-3", false);
        papers.addAll(List.of(p1, p2, p3));
        when(paperSearchabilityService.isSearchable(p1)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(p2)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(p3)).thenReturn(false);

        Map<String, Object> afterAdd = service.addPapers(
                owner.getId(),
                collectionId,
                new AddCollectionPapersRequest(Arrays.asList("paper-1", "paper-2", " ", null, "paper-1", "paper-3"))
        );

        p1.setPaperTitle("Renamed after collection membership");
        p2.setAuthors("Updated Authors");
        Map<String, Object> detail = service.getCollection(owner.getId(), collectionId);

        assertEquals(3, afterAdd.get("paperCount"));
        assertEquals(2L, afterAdd.get("searchablePaperCount"));
        assertEquals(3, detail.get("paperCount"));
        assertEquals(2L, detail.get("searchablePaperCount"));
        assertIterableEquals(List.of("paper-1", "paper-2", "paper-3"), (List<?>) detail.get("paperIds"));
    }

    @Test
    void nonAdminCannotAddInaccessiblePrivatePaper() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Agent papers", "Static set", "PRIVATE", null)
        );
        papers.add(paper("private-other", true, "2", false, "other"));

        CustomException exception = assertThrows(CustomException.class, () -> service.addPapers(
                owner.getId(),
                (Long) created.get("id"),
                new AddCollectionPapersRequest(List.of("private-other"))
        ));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void addRejectsMissingPaperId() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Agent papers", "Static set", "PRIVATE", null)
        );

        CustomException exception = assertThrows(CustomException.class, () -> service.addPapers(
                owner.getId(),
                (Long) created.get("id"),
                new AddCollectionPapersRequest(List.of("missing-paper"))
        ));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void canAddAccessibleUnsearchablePaperAndSearchableCountOnlyCountsSearchable() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Agent papers", "Static set", "PRIVATE", null)
        );
        Long collectionId = (Long) created.get("id");
        Paper searchable = paper("searchable-paper", true, "1", false, "default");
        Paper unsearchable = paper("unsearchable-paper", false, "1", false, "default");
        papers.addAll(List.of(searchable, unsearchable));
        when(paperSearchabilityService.isSearchable(searchable)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(unsearchable)).thenReturn(false);

        Map<String, Object> afterAdd = service.addPapers(
                owner.getId(),
                collectionId,
                new AddCollectionPapersRequest(List.of("searchable-paper", "unsearchable-paper"))
        );

        assertEquals(2, afterAdd.get("paperCount"));
        assertEquals(1L, afterAdd.get("searchablePaperCount"));
        assertIterableEquals(List.of("searchable-paper", "unsearchable-paper"), (List<?>) afterAdd.get("paperIds"));
    }

    @Test
    void orgViewerDetailDoesNotLeakOrCountInaccessibleMemberPaperIds() {
        Map<String, Object> created = service.createCollection(
                admin.getId(),
                new CreateCollectionRequest("Lab org", "Mixed membership", "ORG", "lab")
        );
        Long collectionId = (Long) created.get("id");
        Paper labPaper = paper("lab-paper", true, "9", false, "lab");
        Paper ownerPrivatePaper = paper("owner-private", true, "1", false, "default");
        papers.addAll(List.of(labPaper, ownerPrivatePaper));
        when(paperSearchabilityService.isSearchable(labPaper)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(ownerPrivatePaper)).thenReturn(true);

        service.addPapers(
                admin.getId(),
                collectionId,
                new AddCollectionPapersRequest(List.of("lab-paper", "owner-private"))
        );

        Map<String, Object> detail = service.getCollection(labUser.getId(), collectionId);

        assertEquals(2, detail.get("paperCount"));
        assertEquals(1L, detail.get("searchablePaperCount"));
        assertIterableEquals(List.of("lab-paper"), (List<?>) detail.get("paperIds"));
    }

    @Test
    void membershipEditsSaveParentCollectionForUpdatedAt() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Agent papers", "Static set", "PRIVATE", null)
        );
        Long collectionId = (Long) created.get("id");
        Paper paper = paper("paper-1", true, "1", false, "default");
        papers.add(paper);
        when(paperSearchabilityService.isSearchable(paper)).thenReturn(true);
        PaperCollection collection = collections.stream()
                .filter(item -> item.getId().equals(collectionId))
                .findFirst()
                .orElseThrow();

        clearInvocations(collectionRepository);
        service.addPapers(owner.getId(), collectionId, new AddCollectionPapersRequest(List.of("paper-1")));
        verify(collectionRepository, times(1)).save(collection);

        clearInvocations(collectionRepository);
        service.removePaper(owner.getId(), collectionId, "paper-1");
        verify(collectionRepository, times(1)).save(collection);
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

    private Paper paper(String paperId, boolean searchable) {
        return paper(paperId, searchable, "1", false, "default");
    }

    private Paper paper(String paperId, boolean searchable, String userId, boolean isPublic, String orgTag) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setPaperTitle(paperId);
        paper.setOriginalFilename(paperId + ".pdf");
        paper.setUserId(userId);
        paper.setPublic(isPublic);
        paper.setOrgTag(orgTag);
        paper.setStatus(searchable ? Paper.STATUS_COMPLETED : Paper.STATUS_UPLOADING);
        paper.setVectorizationStatus(searchable
                ? Paper.VECTORIZATION_STATUS_COMPLETED
                : Paper.VECTORIZATION_STATUS_PROCESSING);
        return paper;
    }
}
