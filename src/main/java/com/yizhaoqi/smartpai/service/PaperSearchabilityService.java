package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
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
        if (!Paper.VECTORIZATION_STATUS_COMPLETED.equalsIgnoreCase(String.valueOf(paper.getVectorizationStatus()))
                && paper.getStatus() != Paper.STATUS_COMPLETED) {
            return false;
        }
        return chunkRepository.countByPaperId(paper.getPaperId()) > 0;
    }
}
