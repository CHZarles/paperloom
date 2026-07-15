package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.repository.PaperParserArtifactRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PaperLibraryStatusService {

    private final PaperService paperService;
    private final PaperSearchabilityService searchabilityService;
    private final PaperRepository paperRepository;
    private final PaperTextChunkRepository paperTextChunkRepository;
    private final PaperVisualAssetRepository paperVisualAssetRepository;
    private final PaperParserArtifactRepository paperParserArtifactRepository;
    private final ReadingModelQdrantIndexService qdrantIndexService;

    public PaperLibraryStatusService(PaperService paperService,
                                     PaperSearchabilityService searchabilityService) {
        this(paperService, searchabilityService, null, null, null, null, null);
    }

    @Autowired
    public PaperLibraryStatusService(PaperService paperService,
                                     PaperSearchabilityService searchabilityService,
                                     PaperRepository paperRepository,
                                     PaperTextChunkRepository paperTextChunkRepository,
                                     PaperVisualAssetRepository paperVisualAssetRepository,
                                     PaperParserArtifactRepository paperParserArtifactRepository,
                                     ReadingModelQdrantIndexService qdrantIndexService) {
        this.paperService = paperService;
        this.searchabilityService = searchabilityService;
        this.paperRepository = paperRepository;
        this.paperTextChunkRepository = paperTextChunkRepository;
        this.paperVisualAssetRepository = paperVisualAssetRepository;
        this.paperParserArtifactRepository = paperParserArtifactRepository;
        this.qdrantIndexService = qdrantIndexService;
    }

    public PaperLibraryStatus statusFor(String userId, SourceScope scope) {
        List<Paper> accessible = paperService.getAccessiblePapers(userId, null);
        List<Paper> safeAccessible = accessible == null ? List.of() : accessible;
        Set<String> indexedPaperIds = searchabilityService.searchablePaperIds(safeAccessible);
        Set<String> requested = requestedPaperIds(scope);
        LinkedHashSet<String> accessibleSearchablePaperIds = new LinkedHashSet<>();
        int searchableCount = 0;
        int parsingCount = 0;
        int indexingCount = 0;
        int failedCount = 0;
        int selectedScopeCount = 0;
        List<PaperSource> selectedSearchablePapers = new ArrayList<>();

        for (Paper paper : safeAccessible) {
            if (paper == null || paper.getPaperId() == null || paper.getPaperId().isBlank()) {
                continue;
            }
            boolean searchable = indexedPaperIds.contains(paper.getPaperId());
            if (searchable) {
                searchableCount++;
                accessibleSearchablePaperIds.add(paper.getPaperId());
                if (requested.isEmpty() || requested.contains(paper.getPaperId())) {
                    selectedScopeCount++;
                    selectedSearchablePapers.add(new PaperSource(
                            paper.getPaperId(),
                            displayTitle(paper),
                            paper.getOriginalFilename()
                    ));
                }
                continue;
            }
            String status = normalizedVectorizationStatus(paper);
            if (Paper.VECTORIZATION_STATUS_FAILED.equals(status)) {
                failedCount++;
            } else if (isParsingStatus(status)) {
                parsingCount++;
            } else {
                indexingCount++;
            }
        }

        if (requested.isEmpty()) {
            selectedScopeCount = searchableCount;
        }
        return new PaperLibraryStatus(
                safeAccessible.size(),
                searchableCount,
                parsingCount,
                indexingCount,
                failedCount,
                selectedScopeCount,
                consistencyWarnings(accessibleSearchablePaperIds),
                selectedSearchablePapers
        );
    }

    private String displayTitle(Paper paper) {
        if (paper == null) {
            return "";
        }
        if (paper.getPaperTitle() != null && !paper.getPaperTitle().isBlank()) {
            return paper.getPaperTitle();
        }
        return paper.getOriginalFilename() == null ? paper.getPaperId() : paper.getOriginalFilename();
    }

    private Set<String> requestedPaperIds(SourceScope scope) {
        if (scope == null || scope.paperIds().isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(scope.paperIds());
    }

    private String normalizedVectorizationStatus(Paper paper) {
        String status = paper == null ? null : paper.getVectorizationStatus();
        if (status == null || status.isBlank()) {
            return paper != null && paper.getStatus() == Paper.STATUS_COMPLETED
                    ? Paper.VECTORIZATION_STATUS_PENDING
                    : Paper.VECTORIZATION_STATUS_PROCESSING;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isParsingStatus(String status) {
        return Paper.VECTORIZATION_STATUS_MINERU_RUNNING.equals(status)
                || Paper.VECTORIZATION_STATUS_MINERU_ARTIFACT_SAVED.equals(status)
                || Paper.VECTORIZATION_STATUS_MAPPING_STRUCTURED_CONTENT.equals(status)
                || Paper.VECTORIZATION_STATUS_RENDERING_VISUAL_ASSETS.equals(status);
    }

    private List<String> consistencyWarnings(Set<String> accessibleSearchablePaperIds) {
        List<String> warnings = new ArrayList<>();
        Set<String> productPaperIds = distinctPaperIds(() -> paperRepository == null
                ? List.of()
                : paperRepository.findDistinctPaperIds());

        addOrphanWarning(warnings, "paper_text_chunks", distinctPaperIds(() -> paperTextChunkRepository == null
                ? List.of()
                : paperTextChunkRepository.findDistinctPaperIds()), productPaperIds);
        addOrphanWarning(warnings, "paper_visual_assets", distinctPaperIds(() -> paperVisualAssetRepository == null
                ? List.of()
                : paperVisualAssetRepository.findDistinctPaperIds()), productPaperIds);
        addOrphanWarning(warnings, "paper_parser_artifacts", distinctPaperIds(() -> paperParserArtifactRepository == null
                ? List.of()
                : paperParserArtifactRepository.findDistinctPaperIds()), productPaperIds);

        if (qdrantIndexService != null) {
            qdrantConsistencyWarnings(warnings, accessibleSearchablePaperIds, productPaperIds);
        }
        return warnings;
    }

    private void addOrphanWarning(List<String> warnings,
                                  String sourceName,
                                  Set<String> derivedPaperIds,
                                  Set<String> productPaperIds) {
        if (derivedPaperIds.isEmpty()) {
            return;
        }
        LinkedHashSet<String> orphanIds = new LinkedHashSet<>(derivedPaperIds);
        orphanIds.removeAll(productPaperIds);
        if (!orphanIds.isEmpty()) {
            warnings.add(sourceName + " 存在 " + orphanIds.size() + " 个没有 product paper row 的 paperId");
        }
    }

    private void qdrantConsistencyWarnings(List<String> warnings,
                                           Set<String> accessibleSearchablePaperIds,
                                           Set<String> productPaperIds) {
        try {
            Set<String> indexedPaperIds = qdrantIndexService.indexedPaperIds(10_000);
            addOrphanWarning(warnings, "qdrant_reading_models", indexedPaperIds, productPaperIds);
            LinkedHashSet<String> missing = new LinkedHashSet<>(accessibleSearchablePaperIds);
            missing.removeAll(indexedPaperIds);
            if (!missing.isEmpty()) {
                warnings.add("Qdrant 缺少 " + missing.size() + " 篇当前可检索产品论文的 Reading Model 索引");
            }
        } catch (Exception exception) {
            warnings.add("Qdrant 一致性检查不可用：" + exception.getClass().getSimpleName());
        }
    }

    private Set<String> distinctPaperIds(PaperIdSupplier supplier) {
        try {
            List<String> values = supplier.get();
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            return new LinkedHashSet<>(values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList());
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    @FunctionalInterface
    private interface PaperIdSupplier {
        List<String> get();
    }
}
