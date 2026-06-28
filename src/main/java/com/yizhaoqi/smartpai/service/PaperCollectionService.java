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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public PaperCollectionService(PaperCollectionRepository collectionRepository,
                                  PaperCollectionPaperRepository collectionPaperRepository,
                                  UserRepository userRepository,
                                  PaperRepository paperRepository,
                                  PaperSearchabilityService paperSearchabilityService) {
        this.collectionRepository = collectionRepository;
        this.collectionPaperRepository = collectionPaperRepository;
        this.userRepository = userRepository;
        this.paperRepository = paperRepository;
        this.paperSearchabilityService = paperSearchabilityService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCollections(Long userId) {
        User user = resolveUser(userId);
        if (isAdmin(user)) {
            return collectionRepository.findAllByOrderByUpdatedAtDesc().stream()
                    .map(this::toSummaryDto)
                    .toList();
        }

        Map<Long, PaperCollection> visibleById = new LinkedHashMap<>();

        collectionRepository.findByOwnerIdOrderByUpdatedAtDesc(user.getId())
                .forEach(collection -> visibleById.put(collection.getId(), collection));

        List<String> orgTags = orgTags(user);
        if (!orgTags.isEmpty()) {
            collectionRepository.findOrgVisibleCollections(orgTags)
                    .forEach(collection -> visibleById.putIfAbsent(collection.getId(), collection));
        }

        return visibleById.values().stream()
                .filter(collection -> canView(user, collection))
                .sorted(Comparator.comparing(PaperCollection::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(this::toSummaryDto)
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
                request == null ? null : request.description(),
                parseVisibility(request == null ? null : request.visibility()),
                request == null ? null : request.orgTag(),
                user
        );
        return toSummaryDto(collectionRepository.save(collection));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCollection(Long userId, Long collectionId) {
        User user = resolveUser(userId);
        PaperCollection collection = visibleCollection(user, collectionId);
        return toDetailDto(collection);
    }

    @Transactional
    public Map<String, Object> updateCollection(Long userId, Long collectionId, UpdateCollectionRequest request) {
        User user = resolveUser(userId);
        PaperCollection collection = visibleCollection(user, collectionId);
        requireEditable(user, collection);
        applyEditableFields(
                collection,
                requiredName(request == null ? null : request.name()),
                request == null ? null : request.description(),
                parseVisibility(request == null ? null : request.visibility()),
                request == null ? null : request.orgTag(),
                user
        );
        return toSummaryDto(collectionRepository.save(collection));
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

        for (String paperId : normalizedPaperIds(request == null ? null : request.paperIds())) {
            if (existingPaperIds.contains(paperId)
                    || collectionPaperRepository.existsByCollectionIdAndPaperId(collection.getId(), paperId)) {
                continue;
            }
            PaperCollectionPaper membership = new PaperCollectionPaper();
            membership.setCollection(collection);
            membership.setPaperId(paperId);
            collectionPaperRepository.save(membership);
            existingPaperIds.add(paperId);
        }

        return toDetailDto(collection);
    }

    @Transactional
    public void removePaper(Long userId, Long collectionId, String paperId) {
        User user = resolveUser(userId);
        PaperCollection collection = visibleCollection(user, collectionId);
        requireEditable(user, collection);
        String normalizedPaperId = trimToNull(paperId);
        if (normalizedPaperId != null) {
            collectionPaperRepository.deleteByCollectionIdAndPaperId(collection.getId(), normalizedPaperId);
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
        if (isOwner(user, collection) || isAdmin(user)) {
            return true;
        }
        return collection.getVisibility() == PaperCollection.Visibility.ORG
                && collection.getOrgTag() != null
                && orgTags(user).contains(collection.getOrgTag());
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
                                     String description,
                                     PaperCollection.Visibility visibility,
                                     String orgTag,
                                     User user) {
        collection.setName(name);
        collection.setDescription(trimDescription(description));
        collection.setVisibility(visibility);
        if (visibility == PaperCollection.Visibility.ORG) {
            collection.setOrgTag(resolveOrgTag(orgTag, user));
        } else {
            collection.setOrgTag(null);
        }
    }

    private String requiredName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            throw new CustomException("Collection name is required", HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private PaperCollection.Visibility parseVisibility(String visibility) {
        String trimmed = trimToNull(visibility);
        if (trimmed == null) {
            return PaperCollection.Visibility.PRIVATE;
        }
        try {
            return PaperCollection.Visibility.valueOf(trimmed.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException("Invalid collection visibility: " + visibility, HttpStatus.BAD_REQUEST);
        }
    }

    private String resolveOrgTag(String requestedOrgTag, User user) {
        String orgTag = trimToNull(requestedOrgTag);
        if (orgTag == null) {
            orgTag = trimToNull(user.getPrimaryOrg());
        }
        if (orgTag == null) {
            throw new CustomException("Organization tag is required for ORG collections", HttpStatus.BAD_REQUEST);
        }
        return orgTag;
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

    private List<String> orgTags(User user) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addCsvTags(tags, user.getOrgTags());
        String primaryOrg = trimToNull(user.getPrimaryOrg());
        if (primaryOrg != null) {
            tags.add(primaryOrg);
        }
        return new ArrayList<>(tags);
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

    private Map<String, Object> toSummaryDto(PaperCollection collection) {
        List<PaperCollectionPaper> members = collectionPaperRepository
                .findByCollectionIdOrderByCreatedAtAsc(collection.getId());
        Map<String, Object> dto = baseDto(collection);
        dto.put("paperCount", members.size());
        dto.put("searchablePaperCount", searchablePaperCount(members));
        return dto;
    }

    private Map<String, Object> toDetailDto(PaperCollection collection) {
        List<PaperCollectionPaper> members = collectionPaperRepository
                .findByCollectionIdOrderByCreatedAtAsc(collection.getId());
        Map<String, Object> dto = baseDto(collection);
        dto.put("paperCount", members.size());
        dto.put("searchablePaperCount", searchablePaperCount(members));
        dto.put("paperIds", members.stream().map(PaperCollectionPaper::getPaperId).toList());
        return dto;
    }

    private Map<String, Object> baseDto(PaperCollection collection) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", collection.getId());
        dto.put("name", collection.getName());
        dto.put("description", collection.getDescription());
        dto.put("visibility", collection.getVisibility().name());
        dto.put("orgTag", collection.getOrgTag());
        dto.put("ownerUserId", collection.getOwner() == null ? null : collection.getOwner().getId());
        dto.put("createdAt", collection.getCreatedAt());
        dto.put("updatedAt", collection.getUpdatedAt());
        return dto;
    }

    private long searchablePaperCount(List<PaperCollectionPaper> members) {
        List<String> memberPaperIds = members.stream()
                .map(PaperCollectionPaper::getPaperId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (memberPaperIds.isEmpty()) {
            return 0;
        }

        Set<String> searchablePaperIds = paperRepository.findByPaperIdIn(memberPaperIds)
                .stream()
                .filter(paperSearchabilityService::isSearchable)
                .map(Paper::getPaperId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return memberPaperIds.stream().filter(searchablePaperIds::contains).count();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
