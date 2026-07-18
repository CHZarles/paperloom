package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LexicalBm25EncoderTest {

    @Test
    void appliesBm25TermFrequencyAndDocumentLengthNormalization() {
        LexicalBm25Encoder.EncodedDocument encoded =
                LexicalBm25Encoder.encodeDocument("alpha alpha beta", 3.0);
        Map<Integer, Float> weights = weights(encoded.vector());
        int alpha = LexicalBm25Encoder.encodeQuery("alpha").indices().get(0);
        int beta = LexicalBm25Encoder.encodeQuery("beta").indices().get(0);

        assertEquals(3, encoded.tokenCount());
        assertEquals(1.375, weights.get(alpha), 0.000001);
        assertEquals(1.0, weights.get(beta), 0.000001);
    }

    @Test
    void queryDeduplicatesNormalizedTermsAndUsesUnitWeights() {
        QdrantSparseVector query = LexicalBm25Encoder.encodeQuery("Ａlpha alpha BETA");

        assertEquals(2, query.indices().size());
        assertEquals(java.util.List.of(1.0f, 1.0f), query.values());
    }

    private Map<Integer, Float> weights(QdrantSparseVector vector) {
        Map<Integer, Float> result = new HashMap<>();
        for (int index = 0; index < vector.indices().size(); index++) {
            result.put(vector.indices().get(index), vector.values().get(index));
        }
        return result;
    }
}
