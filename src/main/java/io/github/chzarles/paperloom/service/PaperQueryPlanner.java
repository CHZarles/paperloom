package io.github.chzarles.paperloom.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PaperQueryPlanner {

    public RetrievalPlan plan(String userQuery) {
        return plan(userQuery, null);
    }

    public RetrievalPlan plan(String userQuery, RetrievalIntent forcedIntent) {
        String normalizedForIntent = normalizeQuery(userQuery);
        RetrievalIntent intent = forcedIntent == null ? RetrievalIntent.GENERAL : forcedIntent;
        String normalized = normalizedForIntent;
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (!normalized.isBlank()) {
            queries.add(normalized);
        }
        return new RetrievalPlan(
                userQuery == null ? "" : userQuery,
                normalized,
                intent,
                List.copyOf(queries),
                List.of(),
                List.of()
        );
    }

    private String normalizeQuery(String userQuery) {
        if (userQuery == null) {
            return "";
        }
        return userQuery.replaceAll("\\s+", " ").trim();
    }

    public enum RetrievalIntent {
        EXPERIMENT_RESULT,
        METHOD,
        LIMITATION,
        SUMMARY,
        LITERATURE_SEARCH,
        GENERAL
    }

    public record RetrievalPlan(
            String originalQuery,
            String normalizedQuery,
            RetrievalIntent intent,
            List<String> queryTexts,
            List<String> preferredSourceKinds,
            List<String> preferredSections
    ) {
        public RetrievalPlan {
            queryTexts = queryTexts == null ? List.of() : dedupe(queryTexts);
            preferredSourceKinds = preferredSourceKinds == null ? List.of() : dedupe(preferredSourceKinds);
            preferredSections = preferredSections == null ? List.of() : dedupe(preferredSections);
        }

        public boolean paperLevelSearch() {
            return intent == RetrievalIntent.LITERATURE_SEARCH;
        }

        private static List<String> dedupe(List<String> values) {
            Set<String> seen = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    seen.add(value.trim());
                }
            }
            return new ArrayList<>(seen);
        }
    }
}
