package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProductPaperCorpus {

    private final PaperService paperService;
    private final PaperSearchabilityService searchabilityService;

    public ProductPaperCorpus(PaperService paperService,
                              PaperSearchabilityService searchabilityService) {
        this.paperService = paperService;
        this.searchabilityService = searchabilityService;
    }

    public ProductPaperSet resolveAccessibleSearchablePaperIds(String userId, SourceScope scope) {
        SourceScope safeScope = scope == null ? SourceScope.auto() : scope;
        List<Paper> accessible = paperService.getAccessiblePapers(userId, null);
        List<Paper> safeAccessible = accessible == null ? List.of() : accessible;
        LinkedHashSet<String> searchable = new LinkedHashSet<>();
        for (Paper paper : safeAccessible) {
            if (paper == null || paper.getPaperId() == null || paper.getPaperId().isBlank()) {
                continue;
            }
            if (searchabilityService.isSearchable(paper)) {
                searchable.add(paper.getPaperId());
            }
        }

        if (safeScope.paperIds().isEmpty()) {
            return new ProductPaperSet(List.copyOf(searchable), safeAccessible.size(), searchable.size(), List.of());
        }

        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        LinkedHashSet<String> rejected = new LinkedHashSet<>();
        Set<String> searchableSet = Set.copyOf(searchable);
        for (String requestedPaperId : safeScope.paperIds()) {
            if (searchableSet.contains(requestedPaperId)) {
                allowed.add(requestedPaperId);
            } else {
                rejected.add(requestedPaperId);
            }
        }
        return new ProductPaperSet(
                List.copyOf(allowed),
                safeAccessible.size(),
                searchable.size(),
                List.copyOf(rejected)
        );
    }

    public record ProductPaperSet(
            List<String> paperIds,
            int accessibleCount,
            int searchableCount,
            List<String> rejectedRequestedPaperIds
    ) {
        public ProductPaperSet {
            paperIds = paperIds == null ? List.of() : paperIds.stream()
                    .filter(paperId -> paperId != null && !paperId.isBlank())
                    .distinct()
                    .toList();
            accessibleCount = Math.max(0, accessibleCount);
            searchableCount = Math.max(0, searchableCount);
            rejectedRequestedPaperIds = rejectedRequestedPaperIds == null ? List.of() : rejectedRequestedPaperIds.stream()
                    .filter(paperId -> paperId != null && !paperId.isBlank())
                    .distinct()
                    .toList();
        }
    }
}
