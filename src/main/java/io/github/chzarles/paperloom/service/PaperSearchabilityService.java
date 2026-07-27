package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PaperSearchabilityService {

    private final PaperReadingModelRepository modelRepository;
    private final RetrievalIndexContractService contractService;

    public PaperSearchabilityService(PaperReadingModelRepository modelRepository,
                                     RetrievalIndexContractService contractService) {
        this.modelRepository = modelRepository;
        this.contractService = contractService;
    }

    public boolean isSearchable(Paper paper) {
        if (paper == null || paper.getPaperId() == null || paper.getPaperId().isBlank()) {
            return false;
        }
        return searchablePaperIds(List.of(paper)).contains(paper.getPaperId().trim());
    }

    public boolean isSearchable(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return false;
        }
        return modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId.trim())
                .map(this::isSearchable)
                .orElse(false);
    }

    public boolean isSearchable(PaperReadingModel model) {
        return isSearchable(model, contractService.activeContract());
    }

    private boolean isSearchable(PaperReadingModel model, String activeContract) {
        return model != null
                && model.getPaperId() != null
                && !model.getPaperId().isBlank()
                && model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY
                && model.getRetrievalIndexStatus() == PaperRetrievalIndexStatus.READY
                && activeContract != null
                && activeContract.equals(model.getRetrievalIndexContract())
                && model.getRetrievalIndexedLocationCount() != null
                && model.getRetrievalIndexedLocationCount() > 0;
    }

    public Set<String> searchablePaperIds(List<Paper> papers) {
        LinkedHashSet<String> paperIds = new LinkedHashSet<>();
        if (papers != null) {
            for (Paper paper : papers) {
                if (paper != null && paper.getPaperId() != null && !paper.getPaperId().isBlank()) {
                    paperIds.add(paper.getPaperId().trim());
                }
            }
        }
        if (paperIds.isEmpty()) {
            return Set.of();
        }
        return searchablePaperIdsById(paperIds);
    }

    public Set<String> searchablePaperIdsById(Collection<String> paperIds) {
        LinkedHashSet<String> normalizedPaperIds = new LinkedHashSet<>();
        if (paperIds != null) {
            for (String paperId : paperIds) {
                if (paperId != null && !paperId.isBlank()) {
                    normalizedPaperIds.add(paperId.trim());
                }
            }
        }
        if (normalizedPaperIds.isEmpty()) {
            return Set.of();
        }

        String activeContract = contractService.activeContract();
        if (activeContract == null || activeContract.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> searchable = new LinkedHashSet<>(modelRepository.findSearchableCurrentPaperIds(
                List.copyOf(normalizedPaperIds),
                PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY,
                activeContract
        ));
        return Set.copyOf(searchable);
    }
}
