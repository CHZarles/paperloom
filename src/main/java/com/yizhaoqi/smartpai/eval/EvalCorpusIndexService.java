package com.yizhaoqi.smartpai.eval;

import java.util.Locale;

public class EvalCorpusIndexService {

    public EvalIndices indicesFor(String corpus) {
        String normalizedCorpus = normalizeCorpus(corpus);
        return new EvalIndices(
                "eval_" + normalizedCorpus + "_paper_search",
                "eval_" + normalizedCorpus + "_chunks"
        );
    }

    private String normalizeCorpus(String corpus) {
        if (corpus == null || corpus.isBlank()) {
            throw new IllegalArgumentException("corpus is required");
        }
        String normalized = corpus.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("corpus must contain only lowercase letters, numbers, or underscores");
        }
        return normalized;
    }

    public record EvalIndices(String paperSearchIndex, String chunksIndex) {
    }
}
