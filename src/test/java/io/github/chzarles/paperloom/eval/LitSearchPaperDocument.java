package io.github.chzarles.paperloom.eval;

import java.util.List;

public record LitSearchPaperDocument(
        String paperId,
        String title,
        String abstractText,
        String fullPaperText,
        List<String> citationCorpusIds
) {
    public LitSearchPaperDocument {
        paperId = paperId == null ? "" : paperId;
        title = title == null ? "" : title;
        abstractText = abstractText == null ? "" : abstractText;
        fullPaperText = fullPaperText == null ? "" : fullPaperText;
        citationCorpusIds = citationCorpusIds == null ? List.of() : List.copyOf(citationCorpusIds);
    }
}
