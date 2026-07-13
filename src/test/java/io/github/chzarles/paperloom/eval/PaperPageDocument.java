package io.github.chzarles.paperloom.eval;

import java.util.List;

public record PaperPageDocument(
        String paperId,
        String paperTitle,
        String originalFilename,
        int pageNumber,
        String pageText,
        List<Integer> chunkIds,
        List<String> sectionTitles,
        List<String> sourceKinds,
        List<String> tableIds,
        List<String> figureIds
) {
    public PaperPageDocument {
        paperId = paperId == null ? "" : paperId;
        paperTitle = paperTitle == null ? "" : paperTitle;
        originalFilename = originalFilename == null ? "" : originalFilename;
        pageText = pageText == null ? "" : pageText;
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        sectionTitles = sectionTitles == null ? List.of() : List.copyOf(sectionTitles);
        sourceKinds = sourceKinds == null ? List.of() : List.copyOf(sourceKinds);
        tableIds = tableIds == null ? List.of() : List.copyOf(tableIds);
        figureIds = figureIds == null ? List.of() : List.copyOf(figureIds);
    }

    public String locatorText() {
        return String.join("\n",
                paperTitle,
                originalFilename,
                String.join(" ", sectionTitles),
                String.join(" ", sourceKinds),
                pageText
        ).trim();
    }
}
