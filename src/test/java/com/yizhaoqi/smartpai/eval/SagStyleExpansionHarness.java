package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SagStyleExpansionHarness {

    private static final Pattern TERM = Pattern.compile("[\\p{IsAlphabetic}][\\p{IsAlphabetic}\\p{IsDigit}-]{2,}");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "are", "for", "with", "what", "which", "while", "this", "that",
            "from", "into", "does", "were", "been", "being", "have", "has", "had",
            "evaluation", "results", "result", "paper", "section", "table", "reports"
    );

    private SagStyleExpansionHarness() {
    }

    public static List<Hit> retrieve(String query, List<SearchResult> chunks, int topK) {
        List<Event> events = buildEvents(chunks);
        Set<String> queryEntities = extractEntities(query);
        Set<String> bridgeEntities = bridgeEntities(events, queryEntities);
        return events.stream()
                .map(event -> score(event, queryEntities, bridgeEntities))
                .filter(hit -> hit.score() > 0.0d)
                .sorted(Comparator
                        .comparingDouble(Hit::score).reversed()
                        .thenComparing(hit -> hit.chunk().getPaperId())
                        .thenComparing(hit -> hit.chunk().getChunkId()))
                .limit(Math.max(0, topK))
                .toList();
    }

    private static List<Event> buildEvents(List<SearchResult> chunks) {
        List<Event> events = new ArrayList<>();
        for (SearchResult chunk : chunks == null ? List.<SearchResult>of() : chunks) {
            if (chunk == null || chunk.getPaperId() == null || chunk.getChunkId() == null) {
                continue;
            }
            events.add(new Event(chunk, extractEntities(eventText(chunk))));
        }
        return events;
    }

    private static Set<String> bridgeEntities(List<Event> events, Set<String> queryEntities) {
        LinkedHashSet<String> bridge = new LinkedHashSet<>();
        for (Event event : events) {
            if (!disjoint(event.entities(), queryEntities)) {
                bridge.addAll(event.entities());
            }
        }
        bridge.removeAll(queryEntities);
        return bridge;
    }

    private static Hit score(Event event, Set<String> queryEntities, Set<String> bridgeEntities) {
        LinkedHashSet<String> matchedEntities = new LinkedHashSet<>();
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        Set<String> direct = intersection(event.entities(), queryEntities);
        Set<String> twoHop = direct.isEmpty() ? intersection(event.entities(), bridgeEntities) : Set.of();
        double score = 0.0d;
        if (!direct.isEmpty()) {
            score += direct.size() * 2.0d;
            matchedEntities.addAll(direct);
            reasons.add("query-entity");
        }
        if (!twoHop.isEmpty()) {
            score += twoHop.size() * 1.5d;
            matchedEntities.addAll(twoHop);
            reasons.add("two-hop-entity");
        }
        if (isResultLike(event.chunk())) {
            score += 1.2d;
            reasons.add("result-like");
        }
        if (event.chunk().getSourceKind() != null
                && event.chunk().getSourceKind().equalsIgnoreCase("TABLE")) {
            score += 0.8d;
            reasons.add("table");
        }
        return new Hit(event.chunk(), score, List.copyOf(matchedEntities), List.copyOf(reasons));
    }

    private static boolean isResultLike(SearchResult chunk) {
        String text = eventText(chunk).toLowerCase(Locale.ROOT);
        return text.contains("result")
                || text.contains("report")
                || text.contains("table")
                || text.contains("accuracy")
                || text.contains(" f1")
                || text.contains("score");
    }

    private static String eventText(SearchResult chunk) {
        return String.join("\n",
                nullToText(chunk.getSectionTitle()),
                nullToText(chunk.getMatchedChunkText() == null ? chunk.getTextContent() : chunk.getMatchedChunkText())
        );
    }

    private static Set<String> extractEntities(String text) {
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String term = matcher.group().toLowerCase(Locale.ROOT);
            if (!STOPWORDS.contains(term)) {
                entities.add(term);
            }
        }
        return entities;
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : left == null ? Set.<String>of() : left) {
            if (right != null && right.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        for (String value : left == null ? Set.<String>of() : left) {
            if (right != null && right.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static String nullToText(String value) {
        return value == null ? "" : value;
    }

    private record Event(
            SearchResult chunk,
            Set<String> entities
    ) {
    }

    public record Hit(
            SearchResult chunk,
            double score,
            List<String> matchedEntities,
            List<String> reasons
    ) {
        public Hit {
            matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
