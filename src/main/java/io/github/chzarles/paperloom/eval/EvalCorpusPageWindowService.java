package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.entity.SearchResult;
import io.github.chzarles.paperloom.eval.model.EvalChunk;
import io.github.chzarles.paperloom.eval.model.EvalPaper;
import io.github.chzarles.paperloom.eval.repository.EvalChunkRepository;
import io.github.chzarles.paperloom.eval.repository.EvalPaperRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class EvalCorpusPageWindowService {

    private static final int MAX_WINDOW_RADIUS = 10;

    private final EvalPaperRepository evalPaperRepository;
    private final EvalChunkRepository evalChunkRepository;

    public EvalCorpusPageWindowService(EvalPaperRepository evalPaperRepository,
                                       EvalChunkRepository evalChunkRepository) {
        this.evalPaperRepository = evalPaperRepository;
        this.evalChunkRepository = evalChunkRepository;
    }

    public List<SearchResult> inspectPaper(RetrievalCorpus retrievalCorpus, String paperId) {
        String corpus = corpusName(retrievalCorpus);
        if (paperId == null || paperId.isBlank()) {
            return List.of();
        }
        Optional<EvalPaper> paper = evalPaperRepository.findByCorpusAndPaperId(corpus, paperId);
        return evalChunkRepository.findByCorpusAndPaperIdOrderByChunkIdAsc(corpus, paperId)
                .stream()
                .sorted(chunkComparator())
                .map(chunk -> toSearchResult(corpus, chunk, paper.orElse(null)))
                .toList();
    }

    public List<SearchResult> inspectPageWindow(RetrievalCorpus retrievalCorpus,
                                                String paperId,
                                                Integer pageNumber,
                                                int radius) {
        String corpus = corpusName(retrievalCorpus);
        if (paperId == null || paperId.isBlank() || pageNumber == null || pageNumber < 1) {
            return List.of();
        }
        int safeRadius = Math.min(Math.max(radius, 0), MAX_WINDOW_RADIUS);
        int startPage = Math.max(1, pageNumber - safeRadius);
        int endPage = pageNumber + safeRadius;
        Optional<EvalPaper> paper = evalPaperRepository.findByCorpusAndPaperId(corpus, paperId);
        return evalChunkRepository
                .findByCorpusAndPaperIdAndPageNumberBetweenOrderByPageNumberAscChunkIdAsc(
                        corpus,
                        paperId,
                        startPage,
                        endPage
                )
                .stream()
                .sorted(chunkComparator())
                .map(chunk -> toSearchResult(corpus, chunk, paper.orElse(null)))
                .toList();
    }

    private Comparator<EvalChunk> chunkComparator() {
        return Comparator
                .comparing(EvalChunk::getPageNumber, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(EvalChunk::getChunkId, Comparator.nullsLast(Integer::compareTo));
    }

    private SearchResult toSearchResult(String corpus, EvalChunk chunk, EvalPaper paper) {
        String text = chunk.getRetrievalTextContent() == null || chunk.getRetrievalTextContent().isBlank()
                ? chunk.getTextContent()
                : chunk.getRetrievalTextContent();
        SearchResult result = new SearchResult(
                chunk.getPaperId(),
                chunk.getChunkId(),
                text,
                1.0d,
                "eval-" + corpus + "-user",
                "eval-" + corpus,
                true,
                paperTitle(paper),
                originalFilename(paper, chunk),
                chunk.getPageNumber(),
                null,
                "EVAL_PAGE_WINDOW",
                text,
                "PARAGRAPH",
                chunk.getSectionTitle(),
                1,
                null,
                corpus,
                "eval-corpus",
                sourceKind(chunk),
                null,
                null,
                null,
                false
        );
        result.setEvidenceRole(chunk.getEvidenceRole());
        result.setRetrievalRoute("EVAL_PAGE_WINDOW_INSPECT");
        result.setRankReason("eval-page-window:" + chunk.getPageNumber());
        return result;
    }

    private String sourceKind(EvalChunk chunk) {
        return chunk.getSourceKind() == null || chunk.getSourceKind().isBlank() ? "TEXT" : chunk.getSourceKind();
    }

    private String paperTitle(EvalPaper paper) {
        return paper == null || paper.getTitle() == null ? "" : paper.getTitle();
    }

    private String originalFilename(EvalPaper paper, EvalChunk chunk) {
        if (paper != null && paper.getSourceJson() != null && !paper.getSourceJson().isBlank()) {
            return paper.getSourceJson();
        }
        return chunk.getPaperId() + ".json";
    }

    private String corpusName(RetrievalCorpus retrievalCorpus) {
        if (retrievalCorpus == RetrievalCorpus.EVAL_LITSEARCH) {
            return "litsearch";
        }
        if (retrievalCorpus == RetrievalCorpus.EVAL_QASPER) {
            return "qasper";
        }
        throw new IllegalArgumentException("eval page-window inspection requires an eval retrieval corpus");
    }
}
