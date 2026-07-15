package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.entity.SearchResult;
import io.github.chzarles.paperloom.model.Paper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Compatibility surface for the library-search controller.
 *
 * The implementation now uses the canonical Qdrant Reading Model index. The class name is kept
 * temporarily because the query planner and public controller already depend on this contract.
 */
@Service
public class HybridSearchService {

    private final CorpusRetrievalService corpusRetrievalService;
    private final PaperService paperService;

    public HybridSearchService(CorpusRetrievalService corpusRetrievalService, PaperService paperService) {
        this.corpusRetrievalService = corpusRetrievalService;
        this.paperService = paperService;
    }

    public AdaptiveSearchResult adaptiveSearchWithPermission(String query,
                                                               String userId,
                                                               RetrievalBudget budget) {
        return adaptiveSearchWithPermission(query, userId, budget, List.of());
    }

    public AdaptiveSearchResult adaptiveSearchWithPermission(String query,
                                                               String userId,
                                                               RetrievalBudget budget,
                                                               List<String> scopePaperIds) {
        List<String> scope = effectiveScope(userId, scopePaperIds);
        if (scope.isEmpty()) {
            return empty();
        }
        int topK = Math.min(20, Math.max(1, budget == null ? 8 : budget.pageBatchSize()));
        CorpusRetrievalService.LocationSearchResult result = corpusRetrievalService.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        numericUserId(userId),
                        scope,
                        scope,
                        query,
                        "",
                        List.of(),
                        null,
                        null,
                        topK
                )
        );
        List<SearchResult> results = result.locations().stream().map(this::toSearchResult).toList();
        return new AdaptiveSearchResult(
                results,
                result.matchedCount(),
                results.size(),
                (int) results.stream().map(SearchResult::getPaperId).distinct().count(),
                results.isEmpty()
                        ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
                        : PaperRetrievalService.StopReason.EXHAUSTED
        );
    }

    public AdaptiveSearchResult searchPaperCandidatesWithPermission(String query,
                                                                     String userId,
                                                                     RetrievalBudget budget,
                                                                     List<String> scopePaperIds) {
        List<String> scope = effectiveScope(userId, scopePaperIds);
        if (scope.isEmpty()) {
            return empty();
        }
        int limit = Math.min(100, Math.max(1, budget == null ? 20 : budget.pageBatchSize()));
        CorpusRetrievalService.PaperSearchResult result = corpusRetrievalService.searchPapers(
                new CorpusRetrievalService.PaperSearchQuery(
                        numericUserId(userId),
                        scope,
                        query,
                        scope,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        0,
                        limit
                )
        );
        List<SearchResult> results = result.candidates().stream().map(this::toPaperResult).toList();
        return new AdaptiveSearchResult(
                results,
                result.matchedCount(),
                results.size(),
                results.size(),
                results.isEmpty()
                        ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
                        : PaperRetrievalService.StopReason.EXHAUSTED
        );
    }

    private List<String> effectiveScope(String userId, List<String> requestedScope) {
        if (requestedScope != null && !requestedScope.isEmpty()) {
            return requestedScope.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        }
        return paperService.getAccessiblePapers(userId, null).stream()
                .map(Paper::getPaperId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private SearchResult toSearchResult(CorpusRetrievalService.LocationCandidate candidate) {
        SearchResult result = new SearchResult(
                candidate.paperId(),
                Math.abs(candidate.locationRef().hashCode()),
                candidate.preview(),
                candidate.fusedScore(),
                null,
                null,
                false,
                candidate.title(),
                candidate.page(),
                candidate.locationRef(),
                "QDRANT_HYBRID",
                candidate.preview(),
                candidate.elementType(),
                candidate.section(),
                null,
                null,
                null,
                null
        );
        result.setSourceKind("reading_model_location");
        result.setEvidenceRole("NAVIGATION_PREVIEW");
        return result;
    }

    private SearchResult toPaperResult(CorpusRetrievalService.PaperCard card) {
        SearchResult result = new SearchResult(card.paperId(), 0, card.preview(), 1.0, card.title());
        result.setOriginalFilename(card.filename());
        result.setElementType("PAPER");
        result.setRetrievalMode("PAPER_METADATA");
        result.setSourceKind("paper_metadata");
        result.setEvidenceRole("PAPER_METADATA");
        return result;
    }

    private Long numericUserId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Library search requires a numeric user id", exception);
        }
    }

    private AdaptiveSearchResult empty() {
        return new AdaptiveSearchResult(
                List.of(), 0, 0, 0, PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE);
    }

    static Duration queryEmbeddingTimeout(RetrievalBudget budget) {
        RetrievalBudget effectiveBudget = budget == null ? RetrievalBudget.forQa() : budget;
        Duration byBudget = effectiveBudget.latencyBudget().dividedBy(3);
        Duration minimum = Duration.ofMillis(500);
        Duration maximum = Duration.ofSeconds(2);
        if (byBudget.compareTo(minimum) < 0) {
            return minimum;
        }
        return byBudget.compareTo(maximum) > 0 ? maximum : byBudget;
    }

    public record AdaptiveSearchResult(
            List<SearchResult> results,
            int scannedCount,
            int acceptedEvidenceCount,
            int sourceCount,
            PaperRetrievalService.StopReason stopReason
    ) {
        public AdaptiveSearchResult {
            results = results == null ? List.of() : List.copyOf(results);
            stopReason = stopReason == null ? PaperRetrievalService.StopReason.EXHAUSTED : stopReason;
        }
    }

    public record SearchBranchPlan(
            boolean semanticEnabled,
            boolean semanticRequiresTextMatch,
            boolean keywordEnabled,
            boolean keywordRequiresTextMatch,
            String keywordMatchField
    ) {
        public static SearchBranchPlan forQuery(String query) {
            boolean hasQuery = query != null && !query.isBlank();
            return new SearchBranchPlan(hasQuery, false, hasQuery, true, "searchable_text");
        }
    }
}
