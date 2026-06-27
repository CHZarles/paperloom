package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PaperPageLocatorTool {

    private PaperPageLocatorTool() {
    }

    public static List<PaperPageWindow> locatePages(String query,
                                                    List<PaperPageDocument> pages,
                                                    int topK,
                                                    int radius) {
        return PaperPageLocator.rank(query, pages, topK).stream()
                .map(hit -> new PaperPageWindow(
                        hit.page(),
                        PaperPageLocator.expandNeighbors(hit.page(), pages, radius),
                        hit.score(),
                        hit.reasons()
                ))
                .toList();
    }

    public static PaperPageInspection inspectPage(PaperPageWindow window,
                                                  List<SearchResult> chunks) {
        Set<String> pageKeys = new LinkedHashSet<>(window == null ? List.of() : window.pageKeys());
        List<SearchResult> windowChunks = (chunks == null ? List.<SearchResult>of() : chunks).stream()
                .filter(chunk -> chunk != null
                        && chunk.getPaperId() != null
                        && chunk.getPageNumber() != null
                        && pageKeys.contains(chunk.getPaperId() + ":" + chunk.getPageNumber()))
                .sorted(Comparator
                        .comparing(SearchResult::getPaperId)
                        .thenComparing(SearchResult::getPageNumber)
                        .thenComparing(chunk -> chunk.getChunkId() == null ? Integer.MAX_VALUE : chunk.getChunkId()))
                .toList();
        return PaperPageInspection.from(window, windowChunks);
    }
}
