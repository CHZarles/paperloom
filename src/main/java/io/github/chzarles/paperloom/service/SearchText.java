package io.github.chzarles.paperloom.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SearchText {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Set<String> STOPWORDS = Set.of(
            "a",
            "an",
            "the",
            "for",
            "on",
            "of",
            "about",
            "related",
            "recommend",
            "recommended",
            "paper",
            "papers",
            "article",
            "articles",
            "find",
            "search",
            "推荐",
            "相关",
            "相关论文",
            "论文",
            "文献"
    );

    private SearchText() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        return WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");
    }

    static String compactWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE_PATTERN.matcher(value.trim()).replaceAll(" ");
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static List<String> tokens(String queryText) {
        String normalized = normalize(queryText);
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        Set<String> tokens = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() < 2 || STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return new ArrayList<>(tokens);
    }

    static List<String> matchedFields(List<String> tokens, List<FieldText> fields) {
        return fields.stream()
                .filter(field -> containsAnyToken(field.text(), tokens))
                .map(FieldText::name)
                .toList();
    }

    static boolean containsAllTokens(String value, List<String> tokens) {
        if (tokens.isEmpty() || isBlank(value)) {
            return false;
        }
        String normalized = normalize(value);
        return tokens.stream().allMatch(normalized::contains);
    }

    static boolean containsAnyToken(String value, List<String> tokens) {
        if (tokens.isEmpty() || isBlank(value)) {
            return false;
        }
        String normalized = normalize(value);
        return tokens.stream().anyMatch(normalized::contains);
    }

    static String firstMatchedText(List<String> tokens, List<FieldText> fields) {
        return fields.stream()
                .filter(field -> containsAnyToken(field.text(), tokens))
                .map(FieldText::text)
                .findFirst()
                .orElse("");
    }

    static String preview(String value, List<String> tokens, int maxLength) {
        String compact = compactWhitespace(value);
        if (compact.isEmpty()) {
            return "";
        }

        int effectiveMax = Math.max(20, maxLength);
        String normalized = normalize(compact);
        int matchIndex = tokens.stream()
                .mapToInt(normalized::indexOf)
                .filter(index -> index >= 0)
                .min()
                .orElse(0);
        int start = Math.max(0, matchIndex - effectiveMax / 3);
        int end = Math.min(compact.length(), start + effectiveMax);
        if (end - start < effectiveMax) {
            start = Math.max(0, end - effectiveMax);
        }

        String prefix = start > 0 ? "..." : "";
        String suffix = end < compact.length() ? "..." : "";
        return prefix + compact.substring(start, end) + suffix;
    }

    record FieldText(String name, String text) {
    }
}
