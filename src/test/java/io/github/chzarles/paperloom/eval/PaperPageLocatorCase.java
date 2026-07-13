package io.github.chzarles.paperloom.eval;

import java.util.List;

public record PaperPageLocatorCase(
        String id,
        String query,
        List<String> goldPageKeys
) {
    public PaperPageLocatorCase {
        id = id == null ? "" : id;
        query = query == null ? "" : query;
        goldPageKeys = goldPageKeys == null ? List.of() : List.copyOf(goldPageKeys);
    }
}
