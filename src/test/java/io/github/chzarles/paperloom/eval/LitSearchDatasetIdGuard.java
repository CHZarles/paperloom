package io.github.chzarles.paperloom.eval;

import java.nio.file.Path;
import java.util.Locale;

final class LitSearchDatasetIdGuard {

    static final int LITSEARCH_FULL_CORPUS_ROWS = 64_183;

    private LitSearchDatasetIdGuard() {
    }

    static void rejectObviousPartialCorpusAsFull(String datasetId, Path corpusPath) {
        if (!"litsearch-full".equals(datasetId) || corpusPath == null || corpusPath.getFileName() == null) {
            return;
        }
        String filename = corpusPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.contains("sample") || filename.contains("mini")) {
            throw new IllegalArgumentException(
                    "Refusing to report " + filename + " as litsearch-full; use a sample-specific dataset id"
            );
        }
    }

    static void rejectPartialCorpusSizeAsFull(String datasetId, int paperCount, String sourceDescription) {
        if (!"litsearch-full".equals(datasetId)) {
            return;
        }
        if (paperCount != LITSEARCH_FULL_CORPUS_ROWS) {
            throw new IllegalArgumentException(
                    "Refusing to report " + paperCount + " LitSearch corpus papers from "
                            + sourceDescription + " as litsearch-full; expected 64,183 papers."
                            + " Use a sample-specific dataset id for partial corpus runs."
            );
        }
    }
}
