package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperTextChunk;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads page-window evidence after the caller has already established paper access.
 */
@Service
public class PaperPageWindowService {

    private static final int MAX_WINDOW_RADIUS = 10;

    private final PaperTextChunkRepository paperTextChunkRepository;
    private final PaperRepository paperRepository;

    public PaperPageWindowService(PaperTextChunkRepository paperTextChunkRepository,
                                  PaperRepository paperRepository) {
        this.paperTextChunkRepository = paperTextChunkRepository;
        this.paperRepository = paperRepository;
    }

    public List<SearchResult> inspectPageWindow(String paperId, Integer pageNumber, int radius) {
        if (paperId == null || paperId.isBlank() || pageNumber == null || pageNumber < 1) {
            return List.of();
        }
        int safeRadius = Math.min(Math.max(radius, 0), MAX_WINDOW_RADIUS);
        int startPage = Math.max(1, pageNumber - safeRadius);
        int endPage = pageNumber + safeRadius;
        Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId);
        return paperTextChunkRepository
                .findByPaperIdAndPageNumberBetweenOrderByPageNumberAscChunkIdAsc(paperId, startPage, endPage)
                .stream()
                .sorted(Comparator
                        .comparing(PaperTextChunk::getPageNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PaperTextChunk::getChunkId, Comparator.nullsLast(Integer::compareTo)))
                .map(chunk -> toSearchResult(chunk, paper.orElse(null)))
                .toList();
    }

    public List<SearchResult> inspectPaper(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return List.of();
        }
        Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId);
        return paperTextChunkRepository
                .findByPaperIdOrderByChunkIdAsc(paperId)
                .stream()
                .sorted(Comparator
                        .comparing(PaperTextChunk::getPageNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PaperTextChunk::getChunkId, Comparator.nullsLast(Integer::compareTo)))
                .map(chunk -> toSearchResult(chunk, paper.orElse(null)))
                .toList();
    }

    private SearchResult toSearchResult(PaperTextChunk chunk, Paper paper) {
        SearchResult result = new SearchResult(
                chunk.getPaperId(),
                chunk.getChunkId(),
                chunk.getTextContent(),
                1.0d,
                chunk.getUserId(),
                chunk.getOrgTag(),
                chunk.isPublic(),
                paperTitle(paper),
                originalFilename(paper),
                chunk.getPageNumber(),
                chunk.getAnchorText(),
                "PAGE_WINDOW",
                chunk.getTextContent(),
                chunk.getElementType(),
                chunk.getSectionTitle(),
                chunk.getSectionLevel(),
                chunk.getBboxJson(),
                chunk.getParserName(),
                chunk.getParserVersion(),
                sourceKind(chunk),
                chunk.getTableId(),
                null,
                null,
                false
        );
        result.setFigureId(chunk.getFigureId());
        result.setFormulaId(chunk.getFormulaId());
        result.setEvidenceRole(chunk.getEvidenceRole());
        result.setRetrievalRoute("PAGE_WINDOW_INSPECT");
        result.setRankReason("page-window:" + chunk.getPageNumber());
        return result;
    }

    private String sourceKind(PaperTextChunk chunk) {
        return chunk.getSourceKind() == null || chunk.getSourceKind().isBlank() ? "TEXT" : chunk.getSourceKind();
    }

    private String paperTitle(Paper paper) {
        return paper == null ? "" : paper.getPaperTitle();
    }

    private String originalFilename(Paper paper) {
        return paper == null ? "" : paper.getOriginalFilename();
    }
}
