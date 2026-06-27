package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPageLocatorToolTest {

    @Test
    void locatePagesReturnsRankedWindowsAroundCenterPages() {
        List<SearchResult> chunks = List.of(
                chunk(4, 41, "Setup", "TEXT", null, "Setup details."),
                chunk(5, 51, "4.1 Experiment 1", "TABLE", "table-1", "Table 1 reports overall accuracy."),
                chunk(6, 61, "4.2 Experiment 2", "TEXT", null, "Noise discussion."),
                chunk(7, 71, "4.2 Experiment 2", "TABLE", "table-2", "Table 2 reports session limit noise results.")
        );
        List<PaperPageDocument> pages = PaperPageIndexBuilder.fromSearchResults(chunks);

        List<PaperPageWindow> windows = PaperPageLocatorTool.locatePages(
                "高噪声 session limit table",
                pages,
                1,
                2
        );

        assertEquals(1, windows.size());
        PaperPageWindow window = windows.get(0);
        assertEquals("paper-a:7", window.centerPageKey());
        assertEquals(List.of("paper-a:5", "paper-a:6", "paper-a:7"), window.pageKeys());
        assertTrue(window.score() > 0.0d);
        assertTrue(window.reasons().contains("table"));
    }

    @Test
    void inspectPageReturnsChunksInsideTheLocatedWindowWithProvenance() {
        List<SearchResult> chunks = List.of(
                chunk(5, 51, "4.1 Experiment 1", "TABLE", "table-1", "Table 1 reports overall accuracy."),
                chunk(6, 61, "4.2 Experiment 2", "TEXT", null, "Noise discussion."),
                chunk(7, 71, "4.2 Experiment 2", "TABLE", "table-2", "Table 2 reports session limit noise results."),
                chunk(9, 91, "Appendix", "TEXT", null, "Appendix text.")
        );
        List<PaperPageDocument> pages = PaperPageIndexBuilder.fromSearchResults(chunks);
        PaperPageWindow window = PaperPageLocatorTool.locatePages("session limit table", pages, 1, 1).get(0);

        PaperPageInspection inspection = PaperPageLocatorTool.inspectPage(window, chunks);

        assertEquals(window, inspection.window());
        assertEquals(List.of(6, 7), inspection.pageNumbers());
        assertEquals(List.of(61, 71), inspection.chunkIds());
        assertEquals(List.of("table-2"), inspection.tableIds());
        assertTrue(inspection.sourceKinds().contains("TABLE"));
        assertTrue(inspection.text().contains("session limit noise"));
    }

    private SearchResult chunk(int pageNumber,
                               int chunkId,
                               String sectionTitle,
                               String sourceKind,
                               String tableId,
                               String text) {
        SearchResult result = new SearchResult("paper-a", chunkId, text, 1.0d);
        result.setPaperTitle("Is Grep All You Need? How Agent Harnesses Reshape Agentic Search");
        result.setOriginalFilename("paper-a.pdf");
        result.setPageNumber(pageNumber);
        result.setSectionTitle(sectionTitle);
        result.setSourceKind(sourceKind);
        result.setTableId(tableId);
        result.setMatchedChunkText(text);
        return result;
    }
}
