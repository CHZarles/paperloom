package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PaperSearchabilityService {

    private final PaperReadingModelRepository modelRepository;

    public PaperSearchabilityService(PaperReadingModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    public boolean isSearchable(Paper paper) {
        if (paper == null || paper.getPaperId() == null || paper.getPaperId().isBlank()) {
            return false;
        }
        return searchablePaperIds(List.of(paper)).contains(paper.getPaperId().trim());
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

        LinkedHashSet<String> searchable = new LinkedHashSet<>();
        for (PaperReadingModel model : modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.copyOf(paperIds), PaperReadingModelStatus.READING_MODEL_READY)) {
            if (hasActiveRetrievalIndex(model)) {
                searchable.add(model.getPaperId());
            }
        }
        return Set.copyOf(searchable);
    }

    private boolean hasActiveRetrievalIndex(PaperReadingModel model) {
        return model != null
                && model.getPaperId() != null
                && !model.getPaperId().isBlank()
                && model.getRetrievalIndexStatus() == PaperRetrievalIndexStatus.READY
                && model.getRetrievalIndexGeneration() != null
                && !model.getRetrievalIndexGeneration().isBlank()
                && model.getRetrievalEmbeddingContract() != null
                && !model.getRetrievalEmbeddingContract().isBlank()
                && model.getRetrievalIndexedLocationCount() != null
                && model.getRetrievalIndexedLocationCount() > 0;
    }
}
