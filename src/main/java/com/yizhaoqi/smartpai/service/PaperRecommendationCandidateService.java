package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PaperRecommendationCandidateService {

    public static final String EVIDENCE_SUPPORTED = "SUPPORTED";
    public static final String EVIDENCE_METADATA_ONLY = "METADATA_ONLY";
    public static final String EVIDENCE_NO_CURRENT_READING_MODEL = "NO_CURRENT_READING_MODEL";
    public static final String EVIDENCE_NO_READING_LOCATION_MATCH = "NO_READING_LOCATION_MATCH";

    private final PaperCandidateSearchService paperCandidateSearchService;
    private final ReadingModelGrepSearchService readingModelGrepSearchService;

    public PaperRecommendationCandidateService(PaperCandidateSearchService paperCandidateSearchService,
                                               ReadingModelGrepSearchService readingModelGrepSearchService) {
        this.paperCandidateSearchService = paperCandidateSearchService;
        this.readingModelGrepSearchService = readingModelGrepSearchService;
    }

    @Transactional(readOnly = true)
    public List<PaperRecommendationCandidate> search(PaperRecommendationSearchRequest request) {
        if (request == null || SearchText.tokens(request.queryText()).isEmpty()) {
            return List.of();
        }

        List<PaperCandidate> paperCandidates = paperCandidateSearchService.search(new PaperCandidateSearchRequest(
                request.queryText(),
                request.userId(),
                request.orgTags(),
                request.paperLimit()
        ));
        if (paperCandidates.isEmpty()) {
            return List.of();
        }

        List<String> paperIds = paperCandidates.stream().map(PaperCandidate::paperId).toList();
        int locationLimit = request.paperLimit() * request.perPaperLocationLimit();
        List<ReadingLocationCandidate> locationCandidates = readingModelGrepSearchService.search(
                new ReadingModelGrepSearchRequest(paperIds, request.queryText(), List.of(), null, null, locationLimit)
        );
        Map<String, List<ReadingLocationCandidate>> locationsByPaperId = locationCandidates.stream()
                .collect(Collectors.groupingBy(
                        ReadingLocationCandidate::paperId,
                        Collectors.collectingAndThen(Collectors.toList(), locations ->
                                locations.stream().limit(request.perPaperLocationLimit()).toList())
                ));

        return paperCandidates.stream()
                .map(candidate -> toRecommendationCandidate(candidate, locationsByPaperId, request.perPaperLocationLimit()))
                .sorted(Comparator
                        .comparingInt((PaperRecommendationCandidate candidate) ->
                                EVIDENCE_SUPPORTED.equals(candidate.evidenceStatus()) ? 0 : 1)
                        .thenComparing(candidate -> indexOfPaperId(paperIds, candidate.paperId())))
                .toList();
    }

    private PaperRecommendationCandidate toRecommendationCandidate(PaperCandidate candidate,
                                                                   Map<String, List<ReadingLocationCandidate>> locationsByPaperId,
                                                                   int perPaperLocationLimit) {
        List<ReadingLocationCandidate> supportingLocations = locationsByPaperId
                .getOrDefault(candidate.paperId(), List.of())
                .stream()
                .limit(perPaperLocationLimit)
                .toList();
        String evidenceStatus = evidenceStatus(candidate.paperId(), supportingLocations);
        return new PaperRecommendationCandidate(
                candidate.paperId(),
                candidate.title(),
                candidate.authors(),
                candidate.publicationYear(),
                candidate.venue(),
                candidate.abstractPreview(),
                candidate.matchReason(),
                evidenceStatus,
                supportingLocations
        );
    }

    private String evidenceStatus(String paperId, List<ReadingLocationCandidate> supportingLocations) {
        if (!supportingLocations.isEmpty()) {
            return EVIDENCE_SUPPORTED;
        }
        return readingModelGrepSearchService.hasCurrentModel(paperId)
                ? EVIDENCE_NO_READING_LOCATION_MATCH
                : EVIDENCE_NO_CURRENT_READING_MODEL;
    }

    private int indexOfPaperId(List<String> paperIds, String paperId) {
        int index = paperIds.indexOf(paperId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
