package io.github.chzarles.paperloom.eval;

import java.util.List;

public record PaperPageHit(
        PaperPageDocument page,
        double score,
        List<String> reasons
) {
    public PaperPageHit {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
