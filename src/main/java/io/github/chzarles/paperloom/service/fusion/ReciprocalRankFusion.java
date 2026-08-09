package io.github.chzarles.paperloom.service.fusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (RRF) merges multiple ranked result lists into a single
 * ordering. For each item, score = sum of weight / (k + rank) across lists where the
 * item appears. Items absent from a list do not contribute.
 *
 * <p>Default k=10 prioritises top-rank sources (rank 0 contributes 1/11 instead of
 * 1/61). The original RRF paper uses k=60, which tends to flatten per-rank
 * differences in small candidate pools; a smaller k produces a wider score
 * distribution that downstream LLM consumers can rank against.
 */
public final class ReciprocalRankFusion {

    public static final int DEFAULT_K = 10;

    private ReciprocalRankFusion() {
    }

    @SafeVarargs
    public static <T> RrfResult<T> fuse(RankedList<T>... sources) {
        return fuse(DEFAULT_K, sources);
    }

    @SafeVarargs
    public static <T> RrfResult<T> fuse(int k, RankedList<T>... sources) {
        Map<T, Double> scores = new LinkedHashMap<>();
        for (RankedList<T> source : sources) {
            List<T> items = source.items();
            for (int rank = 0; rank < items.size(); rank++) {
                T item = items.get(rank);
                double contribution = source.weight() / (k + rank + 1L);
                scores.merge(item, contribution, Double::sum);
            }
        }
        List<T> ordered = new ArrayList<>(scores.keySet());
        ordered.sort(Comparator.<T, Double>comparing(scores::get).reversed());
        return new RrfResult<>(ordered, scores);
    }

    public record RankedList<T>(String source, List<T> items, double weight) {
        public RankedList(String source, List<T> items) {
            this(source, items, 1.0);
        }
    }

    public record RrfResult<T>(List<T> orderedItems, Map<T, Double> scores) {
    }
}
