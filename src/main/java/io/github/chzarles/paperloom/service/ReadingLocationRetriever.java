package io.github.chzarles.paperloom.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ReadingLocationRetriever {

    RetrievalCandidates retrieve(LocationRetrievalRequest request);

    record LocationRetrievalRequest(Map<String, String> activeModels,
                                    String queryText,
                                    String sectionQuery,
                                    Set<String> elementTypeHints,
                                    Integer pageFrom,
                                    Integer pageTo,
                                    int topK) {
        public LocationRetrievalRequest {
            activeModels = activeModels == null ? Map.of() : Map.copyOf(activeModels);
            queryText = queryText == null ? "" : queryText.trim();
            sectionQuery = sectionQuery == null ? "" : sectionQuery.trim();
            elementTypeHints = elementTypeHints == null ? Set.of() : Set.copyOf(elementTypeHints);
        }
    }

    record RetrievalCandidates(List<RankedLocationCandidate> ranked,
                               int matchedCount,
                               String indexVersion) {
        public RetrievalCandidates {
            ranked = ranked == null ? List.of() : List.copyOf(ranked);
            indexVersion = indexVersion == null ? "" : indexVersion;
        }
    }

    record RankedLocationCandidate(String locationRef,
                                   Map<String, Object> payload,
                                   double lexicalScore) {
        public RankedLocationCandidate {
            locationRef = locationRef == null ? "" : locationRef;
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
