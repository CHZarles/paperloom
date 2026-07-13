package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.entity.SearchResult;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EvidenceQuality {

    private static final int MIN_MEANINGFUL_CHARS = 24;
    private static final Pattern PURE_NUMBER = Pattern.compile("^[\\d.\\-–—]+$");
    private static final Pattern PAGE_MARKER = Pattern.compile(
            "^(page|p\\.?|第)?\\s*\\d+\\s*(页|/\\s*\\d+)?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MOSTLY_URL_OR_DOI = Pattern.compile(
            "^(https?://|www\\.|doi:?\\s*10\\.)\\S+$",
            Pattern.CASE_INSENSITIVE
    );

    private EvidenceQuality() {
    }

    public static boolean isUsable(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (PURE_NUMBER.matcher(lower).matches()
                || PAGE_MARKER.matcher(lower).matches()
                || MOSTLY_URL_OR_DOI.matcher(lower).matches()
                || looksLikeKeywordList(normalized)) {
            return false;
        }
        if (normalized.length() < MIN_MEANINGFUL_CHARS) {
            return false;
        }
        long lettersOrDigits = normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .count();
        if (lettersOrDigits < 12) {
            return false;
        }
        double signalRatio = lettersOrDigits / (double) normalized.codePointCount(0, normalized.length());
        return signalRatio >= 0.45d;
    }

    public static boolean isUsable(SearchResult result, double minScore) {
        if (result == null) {
            return false;
        }
        if (result.getScore() != null && result.getScore() < minScore) {
            return false;
        }
        return isUsable(bestEvidenceText(result));
    }

    public static String bestEvidenceText(SearchResult result) {
        if (result == null) {
            return "";
        }
        if (isNotBlank(result.getMatchedChunkText())) {
            return result.getMatchedChunkText();
        }
        if (isNotBlank(result.getTableText())) {
            return result.getTableText();
        }
        if (isNotBlank(result.getTableMarkdown())) {
            return result.getTableMarkdown();
        }
        return result.getTextContent();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static boolean looksLikeKeywordList(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("^keywords?\\s*[:：].+")) {
            return true;
        }
        String[] parts = text.split("[,，;；]");
        if (parts.length < 5 || text.matches(".*[.!?。！？].*")) {
            return false;
        }
        int shortPhrases = 0;
        for (String rawPart : parts) {
            String part = rawPart.trim();
            if (part.isBlank()) {
                continue;
            }
            int wordCount = part.split("\\s+").length;
            if (wordCount <= 4 && part.length() <= 32) {
                shortPhrases++;
            }
        }
        return shortPhrases >= 5;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
