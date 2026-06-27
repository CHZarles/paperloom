package com.yizhaoqi.smartpai.eval;

import java.util.List;

public record PaperPageWindow(
        PaperPageDocument centerPage,
        List<PaperPageDocument> pages,
        double score,
        List<String> reasons
) {
    public PaperPageWindow {
        pages = pages == null ? List.of() : List.copyOf(pages);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public String centerPageKey() {
        return PaperPageLocatorScorer.pageKey(centerPage);
    }

    public List<String> pageKeys() {
        return pages.stream()
                .map(PaperPageLocatorScorer::pageKey)
                .toList();
    }

    public List<Integer> pageNumbers() {
        return pages.stream()
                .map(PaperPageDocument::pageNumber)
                .toList();
    }
}
