package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.controller.dto.CollectionRequests.AddCollectionPapersRequest;
import io.github.chzarles.paperloom.controller.dto.CollectionRequests.CreateCollectionRequest;
import io.github.chzarles.paperloom.controller.dto.CollectionRequests.UpdateCollectionRequest;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperCollection;
import io.github.chzarles.paperloom.model.PaperCollectionPaper;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperCollectionPaperRepository;
import io.github.chzarles.paperloom.repository.PaperCollectionRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
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
    private PaperAccessService paperAccessService;

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
        owner = user(1L, "owner", User.Role.USER);
        otherUser = user(2L, "other", User.Role.USER);
        labUser = user(3L, "lab-user", User.Role.USER);
        admin = user(4L, "admin", User.Role.ADMIN);

        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(userRepository.findById(2L)).thenReturn(Optional.of(otherUser));
        when(userRepository.findById(3L)).thenReturn(Optional.of(labUser));
        when(userRepository.findById(4L)).thenReturn(Optional.of(admin));

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
        when(paperAccessService.canAccess(anyString(), anyString())).thenAnswer(invocation -> {
            String requesterId = invocation.getArgument(0);
            String paperId = invocation.getArgument(1);
            User requester = userRepository.findById(Long.parseLong(requesterId)).orElse(owner);
            return papers.stream().filter(paper -> paperId.equals(paper.getPaperId())).anyMatch(paper ->
                    requester.getRole() == User.Role.ADMIN
                            || requesterId.equals(paper.getUserId()));
        });

        service = new PaperCollectionService(
                collectionRepository,
                collectionPaperRepository,
                userRepository,
                paperRepository,
                paperSearchabilityService,
                paperAccessService
        );
    }

    @Test
    void ownerCanCreatePrivateCollection() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("  Agent papers  ", "Agent system reading set")
        );

        assertEquals("Agent papers", created.get("name"));

        List<Map<String, Object>> ownerVisible = service.listCollections(owner.getId());
        List<Map<String, Object>> otherVisible = service.listCollections(otherUser.getId());

        assertEquals(1, ownerVisible.size());
        assertEquals("Agent papers", ownerVisible.get(0).get("name"));
        assertTrue(otherVisible.isEmpty());
    }




    @Test
    void adminCanSeeAllCollectionsInList() {
        service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Owner private", "Private set")
        );
        service.createCollection(
                labUser.getId(),
                new CreateCollectionRequest("Lab org", "Lab shared set")
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
                new CreateCollectionRequest("Owner private", "Private set")
        );

        Map<String, Object> updated = service.updateCollection(
                admin.getId(),
                (Long) created.get("id"),
                new UpdateCollectionRequest("Admin edited private", "Documented admin policy")
        );

        assertEquals("Admin edited private", updated.get("name"));
        assertEquals("Documented admin policy", updated.get("description"));
    }



    @Test
    void addPapersStoresStaticPaperIds() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Agent papers", "Static set")
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
                new CreateCollectionRequest("Agent papers", "Static set")
        );
        papers.add(paper("private-other", true, "2"));

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
                new CreateCollectionRequest("Agent papers", "Static set")
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
                new CreateCollectionRequest("Agent papers", "Static set")
        );
        Long collectionId = (Long) created.get("id");
        Paper searchable = paper("searchable-paper", true, "1");
        Paper unsearchable = paper("unsearchable-paper", false, "1");
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
    void membershipEditsSaveParentCollectionForUpdatedAt() {
        Map<String, Object> created = service.createCollection(
                owner.getId(),
                new CreateCollectionRequest("Agent papers", "Static set")
        );
        Long collectionId = (Long) created.get("id");
        Paper paper = paper("paper-1", true, "1");
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

    private User user(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }

    private Paper paper(String paperId, boolean searchable) {
        return paper(paperId, searchable, "1");
    }

    private Paper paper(String paperId, boolean searchable, String userId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setPaperTitle(paperId);
        paper.setOriginalFilename(paperId + ".pdf");
        paper.setUserId(userId);
        paper.setStatus(searchable ? Paper.STATUS_COMPLETED : Paper.STATUS_UPLOADING);
        paper.setVectorizationStatus(searchable
                ? Paper.VECTORIZATION_STATUS_COMPLETED
                : Paper.VECTORIZATION_STATUS_PROCESSING);
        return paper;
    }
}
