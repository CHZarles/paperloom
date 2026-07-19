package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperPublication;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PaperAccessService {

    private final PaperRepository paperRepository;
    private final PaperPublicationRepository publicationRepository;

    public PaperAccessService(PaperRepository paperRepository,
                              PaperPublicationRepository publicationRepository) {
        this.paperRepository = paperRepository;
        this.publicationRepository = publicationRepository;
    }

    @Transactional(readOnly = true)
    public boolean canAccess(String userId, String paperId) {
        if (blank(userId) || blank(paperId)) {
            return false;
        }
        return paperRepository.countByPaperIdAndUserId(paperId.trim(), userId.trim()) > 0
                || publicationRepository.existsByPaperId(paperId.trim());
    }

    @Transactional(readOnly = true)
    public Set<String> accessiblePaperIds(String userId, Collection<String> requestedPaperIds) {
        Set<String> requested = normalizedIds(requestedPaperIds);
        if (requested.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> accessible = new LinkedHashSet<>();
        for (Paper paper : paperRepository.findAllByUserIdAndPaperIdIn(userId, List.copyOf(requested))) {
            accessible.add(paper.getPaperId());
        }
        for (String paperId : publicationRepository.findAllById(requested).stream()
                .map(publication -> publication.getPaperId())
                .toList()) {
            accessible.add(paperId);
        }
        return Set.copyOf(accessible);
    }

    @Transactional(readOnly = true)
    public List<Paper> accessiblePapers(String userId) {
        List<Paper> own = paperRepository.findByUserId(userId);
        List<Paper> published = publishedPapers(publicationRepository.findAll());
        return mergePreferred(own, published);
    }

    @Transactional(readOnly = true)
    public List<Paper> accessiblePapers(String userId, Collection<String> requestedPaperIds) {
        Set<String> requested = normalizedIds(requestedPaperIds);
        if (requested.isEmpty()) {
            return List.of();
        }
        List<Paper> own = paperRepository.findAllByUserIdAndPaperIdIn(userId, List.copyOf(requested));
        List<Paper> published = publishedPapers(publicationRepository.findAllById(requested));
        return mergePreferred(own, published);
    }

    @Transactional(readOnly = true)
    public Optional<Paper> findPublishedPaper(String paperId) {
        if (blank(paperId)) {
            return Optional.empty();
        }
        return publicationRepository.findById(paperId.trim()).flatMap(this::publishedPaper);
    }

    private List<Paper> publishedPapers(List<PaperPublication> publications) {
        return safePublications(publications).stream()
                .map(this::publishedPaper)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<Paper> publishedPaper(PaperPublication publication) {
        if (publication == null || blank(publication.getPaperId())) {
            return Optional.empty();
        }
        String paperId = publication.getPaperId().trim();
        if (!blank(publication.getPublishedBy())) {
            Optional<Paper> publisherCopy = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(
                    paperId, publication.getPublishedBy().trim());
            if (publisherCopy.isPresent()) {
                return publisherCopy;
            }
        }
        return paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId);
    }

    private List<Paper> mergePreferred(List<Paper> own, List<Paper> published) {
        Comparator<Paper> newestFirst = Comparator.comparing(
                Paper::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())
        );
        Map<String, Paper> byPaperId = new LinkedHashMap<>();
        safe(own).stream().sorted(newestFirst).forEach(paper -> putFirst(byPaperId, paper));
        safe(published).stream().sorted(newestFirst).forEach(paper -> putFirst(byPaperId, paper));
        return new ArrayList<>(byPaperId.values());
    }

    private void putFirst(Map<String, Paper> byPaperId, Paper paper) {
        if (paper != null && !blank(paper.getPaperId())) {
            byPaperId.putIfAbsent(paper.getPaperId().trim(), paper);
        }
    }

    private Set<String> normalizedIds(Collection<String> paperIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (paperIds != null) {
            paperIds.stream().filter(id -> !blank(id)).map(String::trim).forEach(normalized::add);
        }
        return normalized;
    }

    private List<Paper> safe(List<Paper> papers) {
        return papers == null ? List.of() : papers;
    }

    private List<PaperPublication> safePublications(List<PaperPublication> publications) {
        return publications == null ? List.of() : publications;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
