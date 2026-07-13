package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import org.springframework.stereotype.Service;

@Service
public class PaperSearchabilityService {

    private final PaperTextChunkRepository chunkRepository;

    public PaperSearchabilityService(PaperTextChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public boolean isSearchable(Paper paper) {
        if (paper == null || paper.getPaperId() == null || paper.getPaperId().isBlank()) {
            return false;
        }

        String vectorizationStatus = paper.getVectorizationStatus();
        if (vectorizationStatus != null && !vectorizationStatus.isBlank()) {
            if (!Paper.VECTORIZATION_STATUS_COMPLETED.equalsIgnoreCase(vectorizationStatus.trim())) {
                return false;
            }
        } else if (paper.getStatus() != Paper.STATUS_COMPLETED) {
            return false;
        }

        return chunkRepository.countByPaperId(paper.getPaperId()) > 0;
    }
}
