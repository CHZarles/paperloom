package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaperCandidateSearchService {

    private static final int RANK_TITLE_ALL_TOKENS = 10;
    private static final int RANK_ABSTRACT_ALL_TOKENS = 20;
    private static final int RANK_IDENTITY_ALL_TOKENS = 30;
    private static final int RANK_VENUE_AUTHOR_ALL_TOKENS = 40;
    private static final int RANK_FILENAME_ALL_TOKENS = 50;
    private static final int RANK_PARTIAL_METADATA = 80;
    private static final int ABSTRACT_PREVIEW_LENGTH = 240;

    private final PaperService paperService;

    public PaperCandidateSearchService(PaperService paperService) {
        this.paperService = paperService;
    }

    @Transactional(readOnly = true)
    public List<PaperCandidate> search(PaperCandidateSearchRequest request) {
        if (request == null) {
            return List.of();
        }

        List<String> tokens = SearchText.tokens(request.queryText());
        if (tokens.isEmpty()) {
            return List.of();
        }

        Map<String, PaperCandidate> bestCandidatesByPaperId = new LinkedHashMap<>();
        for (Paper paper : paperService.getAccessiblePapers(request.userId(), request.orgTags())) {
            if (paper == null || SearchText.isBlank(paper.getPaperId())) {
                continue;
            }
            candidateFor(paper, tokens).ifPresent(candidate -> {
                PaperCandidate existing = bestCandidatesByPaperId.get(candidate.paperId());
                if (existing == null || candidate.rank() < existing.rank()) {
                    bestCandidatesByPaperId.put(candidate.paperId(), candidate);
                }
            });
        }

        return bestCandidatesByPaperId.values().stream()
                .sorted(Comparator
                        .comparingInt(PaperCandidate::rank)
                        .thenComparing(candidate -> SearchText.normalize(candidate.title()))
                        .thenComparing(PaperCandidate::paperId))
                .limit(request.limit())
                .toList();
    }

    private java.util.Optional<PaperCandidate> candidateFor(Paper paper, List<String> tokens) {
        List<SearchText.FieldText> fields = metadataFields(paper);
        List<String> matchedFields = SearchText.matchedFields(tokens, fields);
        if (matchedFields.isEmpty()) {
            return java.util.Optional.empty();
        }

        int rank = rank(paper, tokens);
        String matchReason = matchReason(rank, matchedFields);
        return java.util.Optional.of(new PaperCandidate(
                paper.getPaperId(),
                paper.getPaperTitle(),
                paper.getAuthors(),
                paper.getPublicationYear(),
                paper.getVenue(),
                SearchText.preview(paper.getAbstractText(), tokens, ABSTRACT_PREVIEW_LENGTH),
                matchedFields,
                matchReason,
                rank
        ));
    }

    private int rank(Paper paper, List<String> tokens) {
        if (SearchText.containsAllTokens(paper.getPaperTitle(), tokens)) {
            return RANK_TITLE_ALL_TOKENS;
        }
        if (SearchText.containsAllTokens(paper.getAbstractText(), tokens)) {
            return RANK_ABSTRACT_ALL_TOKENS;
        }
        if (SearchText.containsAllTokens(identityFields(paper), tokens)) {
            return RANK_IDENTITY_ALL_TOKENS;
        }
        if (SearchText.containsAllTokens(join(paper.getVenue(), paper.getAuthors()), tokens)) {
            return RANK_VENUE_AUTHOR_ALL_TOKENS;
        }
        if (SearchText.containsAllTokens(paper.getOriginalFilename(), tokens)) {
            return RANK_FILENAME_ALL_TOKENS;
        }
        return RANK_PARTIAL_METADATA;
    }

    private List<SearchText.FieldText> metadataFields(Paper paper) {
        List<SearchText.FieldText> fields = new ArrayList<>();
        fields.add(new SearchText.FieldText("title", paper.getPaperTitle()));
        fields.add(new SearchText.FieldText("abstract", paper.getAbstractText()));
        fields.add(new SearchText.FieldText("authors", paper.getAuthors()));
        fields.add(new SearchText.FieldText("venue", paper.getVenue()));
        fields.add(new SearchText.FieldText("publicationYear", paper.getPublicationYear() == null
                ? ""
                : paper.getPublicationYear().toString()));
        fields.add(new SearchText.FieldText("doi", paper.getDoi()));
        fields.add(new SearchText.FieldText("arxivId", paper.getArxivId()));
        fields.add(new SearchText.FieldText("originalFilename", paper.getOriginalFilename()));
        return fields;
    }

    private String identityFields(Paper paper) {
        return join(
                paper.getDoi(),
                paper.getArxivId(),
                paper.getPublicationYear() == null ? null : paper.getPublicationYear().toString()
        );
    }

    private String join(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (!SearchText.isBlank(value)) {
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private String matchReason(int rank, List<String> matchedFields) {
        return switch (rank) {
            case RANK_TITLE_ALL_TOKENS -> "title matched all query tokens";
            case RANK_ABSTRACT_ALL_TOKENS -> "abstract matched all query tokens";
            case RANK_IDENTITY_ALL_TOKENS -> "identity metadata matched all query tokens";
            case RANK_VENUE_AUTHOR_ALL_TOKENS -> "venue/authors matched all query tokens";
            case RANK_FILENAME_ALL_TOKENS -> "filename matched all query tokens";
            default -> "partial metadata match: " + String.join(", ", matchedFields);
        };
    }
}
