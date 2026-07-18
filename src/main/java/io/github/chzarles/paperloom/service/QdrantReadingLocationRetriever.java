package io.github.chzarles.paperloom.service;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class QdrantReadingLocationRetriever implements ReadingLocationRetriever {

    private static final int MAX_CANDIDATE_LIMIT = 100;

    private final QdrantClient qdrantClient;

    public QdrantReadingLocationRetriever(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    @Override
    public RetrievalCandidates retrieve(LocationRetrievalRequest request) {
        String query = String.join(" ", List.of(request.queryText(), request.sectionQuery())).trim();
        if (query.isBlank()) {
            return new RetrievalCandidates(List.of(), 0, qdrantClient.indexVersion());
        }
        QdrantSparseVector vector = LexicalBm25Encoder.encodeQuery(query);
        if (vector.indices().isEmpty()) {
            return new RetrievalCandidates(List.of(), 0, qdrantClient.indexVersion());
        }

        qdrantClient.verifyCollection();
        int topK = Math.max(1, request.topK());
        boolean needsLeadCandidatePool = request.activeModels().size() == 1
                || SearchText.tokens(query).size() <= 2;
        int candidateLimit = needsLeadCandidatePool
                ? MAX_CANDIDATE_LIMIT
                : Math.min(MAX_CANDIDATE_LIMIT, Math.max(40, topK * 4));
        Map<String, Object> filter = qdrantClient.filter(
                request.activeModels(), request.pageFrom(), request.pageTo());
        Map<String, RankedLocationCandidate> candidates = new LinkedHashMap<>();
        for (QdrantSearchHit hit : qdrantClient.searchLexical(vector, filter, candidateLimit)) {
            String locationRef = stringPayload(hit.payload(), "location_ref");
            if (locationRef.isBlank()) {
                continue;
            }
            RankedLocationCandidate candidate = new RankedLocationCandidate(
                    locationRef, hit.payload(), hit.score());
            candidates.merge(locationRef, candidate,
                    (left, right) -> right.lexicalScore() > left.lexicalScore() ? right : left);
        }

        Set<String> hints = request.elementTypeHints().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<RankedLocationCandidate> ranked = candidates.values().stream()
                .sorted(Comparator.comparingDouble(RankedLocationCandidate::lexicalScore).reversed()
                        .thenComparing((RankedLocationCandidate candidate) -> !matchesElementHint(candidate.payload(), hints))
                        .thenComparing(RankedLocationCandidate::locationRef))
                .toList();
        return new RetrievalCandidates(ranked, ranked.size(), qdrantClient.indexVersion());
    }

    private boolean matchesElementHint(Map<String, Object> payload, Set<String> hints) {
        if (hints.isEmpty() || payload == null) {
            return false;
        }
        Object values = payload.get("element_types");
        if (values instanceof List<?> list && list.stream()
                .map(Object::toString)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(hints::contains)) {
            return true;
        }
        return hints.contains(stringPayload(payload, "element_type").toLowerCase(Locale.ROOT));
    }

    private String stringPayload(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
