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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PaperCollectionService {

    private final PaperCollectionRepository collectionRepository;
    private final PaperCollectionPaperRepository collectionPaperRepository;
    private final UserRepository userRepository;
    private final PaperRepository paperRepository;
    private final PaperSearchabilityService paperSearchabilityService;
    private final PaperAccessService paperAccessService;

    public PaperCollectionService(PaperCollectionRepository collectionRepository,
                                  PaperCollectionPaperRepository collectionPaperRepository,
                                  UserRepository userRepository,
                                  PaperRepository paperRepository,
                                  PaperSearchabilityService paperSearchabilityService,
                                  PaperAccessService paperAccessService) {
        this.collectionRepository = collectionRepository;
        this.collectionPaperRepository = collectionPaperRepository;
        this.userRepository = userRepository;
        this.paperRepository = paperRepository;
        this.paperSearchabilityService = paperSearchabilityService;
        this.paperAccessService = paperAccessService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCollections(Long userId) {
        User user = resolveUser(userId);
        if (isAdmin(user)) {
            return collectionRepository.findAllByOrderByUpdatedAtDesc().stream()
                    .map(collection -> toSummaryDto(collection, user))
                    .toList();
        }

        Map<Long, PaperCollection> visibleById = new LinkedHashMap<>();

        collectionRepository.findByOwnerIdOrderByUpdatedAtDesc(user.getId())
                .forEach(collection -> visibleById.put(collection.getId(), collection));

        return visibleById.values().stream()
                .filter(collection -> canView(user, collection))
                .sorted(Comparator.comparing(PaperCollection::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(collection -> toSummaryDto(collection, user))
                .toList();
    }

    @Transactional
    public Map<String, Object> createCollection(Long userId, CreateCollectionRequest request) {
        User user = resolveUser(userId);
        PaperCollection collection = new PaperCollection();
        collection.setOwner(user);
        applyEditableFields(
                collection,
                requiredName(request == null ? null : request.name()),
                request == null ? null : request.description()
        );
        return toSummaryDto(collectionRepository.save(collection), user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCollection(Long userId, Long collectionId) {
        User user = resolveUser(userId);
        PaperCollection collection = visibleCollection(user, collectionId);
        return toDetailDto(collection, user);
    }

    @Transactional
    public Map<String, Object> updateCollection(Long userId, Long collectionId, UpdateCollectionRequest request) {
        User user = resolveUser(userId);
        PaperCollection collection = visibleCollection(user, collectionId);
        requireEditable(user, collection);
        applyEditableFields(
                collection,
                requiredName(request == null ? null : request.name()),
                request == null ? null : request.description()
        );
        return toSummaryDto(collectionRepository.save(collection), user);
    }

    @Transactional
    public void deleteCollection(Long userId, Long collectionId) {
        User user = resolveUser(userId);
        PaperCollection collection = visibleCollection(user, collectionId);
        requireEditable(user, collection);
        collectionPaperRepository.deleteByCollectionId(collection.getId());
        collectionRepository.delete(collection);
    }

    @Transactional
    public Map<String, Object> addPapers(Long userId, Long collectionId, AddCollectionPapersRequest request) {
        User user = resolveUser(userId);
        PaperCollection collection = visibleCollection(user, collectionId);
        requireEditable(user, collection);

        Set<String> existingPaperIds = collectionPaperRepository.findByCollectionIdOrderByCreatedAtAsc(collection.getId())
                .stream()
                .map(PaperCollectionPaper::getPaperId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> requestedPaperIds = normalizedPaperIds(request == null ? null : request.paperIds());
        validateRequestedPaperAccess(user, requestedPaperIds);
        boolean changed = false;
        for (String paperId : requestedPaperIds) {
            if (existingPaperIds.contains(paperId)
                    || collectionPaperRepository.existsByCollectionIdAndPaperId(collection.getId(), paperId)) {
                continue;
            }
            PaperCollectionPaper membership = new PaperCollectionPaper();
            membership.setCollection(collection);
            membership.setPaperId(paperId);
            collectionPaperRepository.save(membership);
            existingPaperIds.add(paperId);
            changed = true;
        }

        if (changed) {
            touchCollection(collection);
        }
        return toDetailDto(collection, user);
    }

    @Transactional
    public void removePaper(Long userId, Long collectionId, String paperId) {
        User user = resolveUser(userId);
        PaperCollection collection = visibleCollection(user, collectionId);
        requireEditable(user, collection);
        String normalizedPaperId = trimToNull(paperId);
        if (normalizedPaperId != null) {
            collectionPaperRepository.deleteByCollectionIdAndPaperId(collection.getId(), normalizedPaperId);
            touchCollection(collection);
        }
    }

    private User resolveUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
    }

    private PaperCollection visibleCollection(User user, Long collectionId) {
        PaperCollection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new CustomException("Collection not found", HttpStatus.NOT_FOUND));
        if (!canView(user, collection)) {
            throw new CustomException("Collection not found", HttpStatus.NOT_FOUND);
        }
        return collection;
    }

    private void requireEditable(User user, PaperCollection collection) {
        if (!canEdit(user, collection)) {
            throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        }
    }

    private boolean canView(User user, PaperCollection collection) {
        return isOwner(user, collection) || isAdmin(user);
    }

    private boolean canEdit(User user, PaperCollection collection) {
        return isOwner(user, collection) || isAdmin(user);
    }

    private boolean isOwner(User user, PaperCollection collection) {
        return collection.getOwner() != null && Objects.equals(collection.getOwner().getId(), user.getId());
    }

    private boolean isAdmin(User user) {
        return user.getRole() == User.Role.ADMIN;
    }

    private void applyEditableFields(PaperCollection collection,
                                     String name,
                                     String description) {
        collection.setName(name);
        collection.setDescription(trimDescription(description));
    }

    private String requiredName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            throw new CustomException("Collection name is required", HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private String trimDescription(String description) {
        return description == null ? null : description.trim();
    }

    private List<String> normalizedPaperIds(List<String> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return List.of();
        }
        return paperIds.stream()
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private Map<String, Object> toSummaryDto(PaperCollection collection, User viewer) {
        List<PaperCollectionPaper> members = collectionPaperRepository
                .findByCollectionIdOrderByCreatedAtAsc(collection.getId());
        MemberPaperAccess access = resolveMemberPaperAccess(viewer, members);
        Map<String, Object> dto = baseDto(collection);
        dto.put("paperCount", members.size());
        dto.put("searchablePaperCount", access.searchablePaperCount());
        return dto;
    }

    private Map<String, Object> toDetailDto(PaperCollection collection, User viewer) {
        List<PaperCollectionPaper> members = collectionPaperRepository
                .findByCollectionIdOrderByCreatedAtAsc(collection.getId());
        MemberPaperAccess access = resolveMemberPaperAccess(viewer, members);
        Map<String, Object> dto = baseDto(collection);
        dto.put("paperCount", members.size());
        dto.put("searchablePaperCount", access.searchablePaperCount());
        dto.put("paperIds", access.accessiblePaperIds());
        return dto;
    }

    private Map<String, Object> baseDto(PaperCollection collection) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", collection.getId());
        dto.put("name", collection.getName());
        dto.put("description", collection.getDescription());
        dto.put("ownerUserId", collection.getOwner() == null ? null : collection.getOwner().getId());
        dto.put("createdAt", collection.getCreatedAt());
        dto.put("updatedAt", collection.getUpdatedAt());
        return dto;
    }

    private MemberPaperAccess resolveMemberPaperAccess(User viewer, List<PaperCollectionPaper> members) {
        List<String> memberPaperIds = memberPaperIds(members);
        if (memberPaperIds.isEmpty()) {
            return new MemberPaperAccess(List.of(), 0);
        }

        Map<String, List<Paper>> papersByPaperId = productPapersByPaperId(memberPaperIds);
        List<String> accessiblePaperIds = memberPaperIds.stream()
                .filter(paperId -> papersByPaperId.getOrDefault(paperId, List.of()).stream()
                        .anyMatch(paper -> canAccessPaper(viewer, paper)))
                .toList();
        long searchablePaperCount = accessiblePaperIds.stream()
                .filter(paperId -> papersByPaperId.getOrDefault(paperId, List.of()).stream()
                        .filter(paper -> canAccessPaper(viewer, paper))
                        .anyMatch(paperSearchabilityService::isSearchable))
                .count();
        return new MemberPaperAccess(accessiblePaperIds, searchablePaperCount);
    }

    private List<String> memberPaperIds(List<PaperCollectionPaper> members) {
        return members.stream()
                .map(PaperCollectionPaper::getPaperId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private Map<String, List<Paper>> productPapersByPaperId(List<String> paperIds) {
        if (paperIds.isEmpty()) {
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

    private void validateRequestedPaperAccess(User user, List<String> requestedPaperIds) {
        if (requestedPaperIds.isEmpty()) {
            return;
        }
        Map<String, List<Paper>> papersByPaperId = productPapersByPaperId(requestedPaperIds);
        for (String paperId : requestedPaperIds) {
            List<Paper> papers = papersByPaperId.getOrDefault(paperId, List.of());
            if (papers.isEmpty()) {
                throw new CustomException("Paper not found: " + paperId, HttpStatus.BAD_REQUEST);
            }
            boolean accessible = papers.stream().anyMatch(paper -> canAccessPaper(user, paper));
            if (!accessible) {
                throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
            }
        }
    }

    private boolean canAccessPaper(User user, Paper paper) {
        return paperAccessService.canAccess(String.valueOf(user.getId()), paper.getPaperId());
    }

    private void touchCollection(PaperCollection collection) {
        collection.setUpdatedAt(LocalDateTime.now());
        collectionRepository.save(collection);
    }

    private record MemberPaperAccess(List<String> accessiblePaperIds, long searchablePaperCount) {
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
