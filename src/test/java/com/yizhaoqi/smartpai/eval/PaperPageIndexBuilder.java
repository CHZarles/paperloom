package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class PaperPageIndexBuilder {

    private PaperPageIndexBuilder() {
    }

    public static List<PaperPageDocument> fromSearchResults(List<SearchResult> chunks) {
        Map<String, PageAccumulator> pages = new LinkedHashMap<>();
        for (SearchResult chunk : chunks == null ? List.<SearchResult>of() : chunks) {
            if (chunk == null || chunk.getPaperId() == null || chunk.getPageNumber() == null) {
                continue;
            }
            String key = chunk.getPaperId() + ":" + chunk.getPageNumber();
            PageAccumulator page = pages.computeIfAbsent(key, ignored -> new PageAccumulator(
                    chunk.getPaperId(),
                    firstNonBlank(chunk.getPaperTitle(), chunk.getOriginalFilename()),
                    chunk.getOriginalFilename(),
                    chunk.getPageNumber()
            ));
            page.add(chunk);
        }
        return pages.values().stream()
                .map(PageAccumulator::toDocument)
                .sorted(Comparator
                        .comparing(PaperPageDocument::paperId)
                        .thenComparingInt(PaperPageDocument::pageNumber))
                .toList();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static final class PageAccumulator {
        private final String paperId;
        private final String paperTitle;
        private final String originalFilename;
        private final int pageNumber;
        private final List<Integer> chunkIds = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();
        private final LinkedHashSet<String> sectionTitles = new LinkedHashSet<>();
        private final LinkedHashSet<String> sourceKinds = new LinkedHashSet<>();
        private final LinkedHashSet<String> tableIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> figureIds = new LinkedHashSet<>();

        private PageAccumulator(String paperId, String paperTitle, String originalFilename, int pageNumber) {
            this.paperId = paperId;
            this.paperTitle = paperTitle;
            this.originalFilename = originalFilename;
            this.pageNumber = pageNumber;
        }

        private void add(SearchResult chunk) {
            if (chunk.getChunkId() != null) {
                chunkIds.add(chunk.getChunkId());
            }
            addIfPresent(texts, firstNonBlank(chunk.getMatchedChunkText(), chunk.getTextContent()));
            addIfPresent(sectionTitles, chunk.getSectionTitle());
            addIfPresent(sourceKinds, chunk.getSourceKind());
            addIfPresent(tableIds, chunk.getTableId());
            addIfPresent(figureIds, chunk.getFigureId());
        }

        private PaperPageDocument toDocument() {
            return new PaperPageDocument(
                    paperId,
                    paperTitle,
                    originalFilename,
                    pageNumber,
                    String.join("\n", texts),
                    List.copyOf(chunkIds),
                    List.copyOf(sectionTitles),
                    List.copyOf(sourceKinds),
                    List.copyOf(tableIds),
                    List.copyOf(figureIds)
            );
        }

        private static void addIfPresent(List<String> values, String value) {
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }

        private static void addIfPresent(LinkedHashSet<String> values, String value) {
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
    }
}
