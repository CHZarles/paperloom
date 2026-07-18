package io.github.chzarles.paperloom.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LexicalBm25Encoder {

    static final double K1 = 1.2;
    static final double B = 0.75;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}_]+");

    private LexicalBm25Encoder() {
    }

    static EncodedDocument encodeDocument(String text, double averageDocumentLength) {
        TokenCounts tokens = tokenCounts(text);
        if (tokens.termFrequency().isEmpty()) {
            return new EncodedDocument(new QdrantSparseVector(List.of(), List.of()), 0, tokens.hashCollisions());
        }
        double safeAverage = averageDocumentLength > 0 ? averageDocumentLength : tokens.tokenCount();
        List<Integer> indices = tokens.termFrequency().keySet().stream().sorted().toList();
        List<Float> values = indices.stream().map(index -> {
            int frequency = tokens.termFrequency().get(index);
            double denominator = frequency + K1 * (
                    1 - B + B * tokens.tokenCount() / safeAverage
            );
            return (float) (frequency * (K1 + 1) / denominator);
        }).toList();
        return new EncodedDocument(
                new QdrantSparseVector(indices, values),
                tokens.tokenCount(),
                tokens.hashCollisions()
        );
    }

    static QdrantSparseVector encodeQuery(String text) {
        TokenCounts tokens = tokenCounts(text);
        List<Integer> indices = tokens.termFrequency().keySet().stream().sorted().toList();
        return new QdrantSparseVector(indices, indices.stream().map(ignored -> 1.0f).toList());
    }

    static int tokenCount(String text) {
        return tokenCounts(text).tokenCount();
    }

    static double averageDocumentLength(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        long total = 0;
        int documents = 0;
        for (String text : texts) {
            int length = tokenCount(text);
            if (length <= 0) {
                continue;
            }
            total += length;
            documents++;
        }
        return documents == 0 ? 0 : (double) total / documents;
    }

    private static TokenCounts tokenCounts(String text) {
        Map<Integer, Integer> termFrequency = new LinkedHashMap<>();
        Map<Integer, String> tokensByIndex = new LinkedHashMap<>();
        int tokenCount = 0;
        int collisions = 0;
        Matcher matcher = TOKEN_PATTERN.matcher(SearchText.normalize(text));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() < 2) {
                continue;
            }
            int index = stableTokenIndex(token);
            String existing = tokensByIndex.putIfAbsent(index, token);
            if (existing != null && !existing.equals(token)) {
                collisions++;
            }
            termFrequency.merge(index, 1, Integer::sum);
            tokenCount++;
        }
        return new TokenCounts(termFrequency, tokenCount, collisions);
    }

    private static int stableTokenIndex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getInt() & Integer.MAX_VALUE;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record EncodedDocument(QdrantSparseVector vector, int tokenCount, int hashCollisions) {
    }

    private record TokenCounts(Map<Integer, Integer> termFrequency, int tokenCount, int hashCollisions) {
    }
}
