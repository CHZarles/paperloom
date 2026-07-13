package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.entity.SearchResult;

import java.util.LinkedHashSet;
import java.util.List;

public record PaperPageInspection(
        PaperPageWindow window,
        List<SearchResult> chunks,
        List<Integer> pageNumbers,
        List<Integer> chunkIds,
        List<String> sectionTitles,
        List<String> sourceKinds,
        List<String> tableIds,
        List<String> figureIds,
        String text
) {
    public PaperPageInspection {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        pageNumbers = pageNumbers == null ? List.of() : List.copyOf(pageNumbers);
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        sectionTitles = sectionTitles == null ? List.of() : List.copyOf(sectionTitles);
        sourceKinds = sourceKinds == null ? List.of() : List.copyOf(sourceKinds);
        tableIds = tableIds == null ? List.of() : List.copyOf(tableIds);
        figureIds = figureIds == null ? List.of() : List.copyOf(figureIds);
        text = text == null ? "" : text;
    }

    static PaperPageInspection from(PaperPageWindow window, List<SearchResult> chunks) {
        LinkedHashSet<Integer> pageNumbers = new LinkedHashSet<>();
        LinkedHashSet<Integer> chunkIds = new LinkedHashSet<>();
        LinkedHashSet<String> sectionTitles = new LinkedHashSet<>();
        LinkedHashSet<String> sourceKinds = new LinkedHashSet<>();
        LinkedHashSet<String> tableIds = new LinkedHashSet<>();
        LinkedHashSet<String> figureIds = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder();
        for (SearchResult chunk : chunks == null ? List.<SearchResult>of() : chunks) {
            if (chunk == null) {
                continue;
            }
            addIfPresent(pageNumbers, chunk.getPageNumber());
            addIfPresent(chunkIds, chunk.getChunkId());
            addIfPresent(sectionTitles, chunk.getSectionTitle());
            addIfPresent(sourceKinds, chunk.getSourceKind());
            addIfPresent(tableIds, chunk.getTableId());
            addIfPresent(figureIds, chunk.getFigureId());
            String chunkText = firstNonBlank(chunk.getMatchedChunkText(), chunk.getTextContent());
            if (!chunkText.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(chunkText);
            }
        }
        return new PaperPageInspection(
                window,
                chunks,
                List.copyOf(pageNumbers),
                List.copyOf(chunkIds),
                List.copyOf(sectionTitles),
                List.copyOf(sourceKinds),
                List.copyOf(tableIds),
                List.copyOf(figureIds),
                text.toString()
        );
    }

    private static void addIfPresent(LinkedHashSet<Integer> values, Integer value) {
        if (value != null) {
            values.add(value);
        }
    }

    private static void addIfPresent(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }
}
