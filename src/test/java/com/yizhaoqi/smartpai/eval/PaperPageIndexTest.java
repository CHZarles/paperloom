package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPageIndexTest {

    @Test
    void groupsChunksByPaperAndPageWithLocationMetadata() {
        List<SearchResult> chunks = List.of(
                chunk("paper-a", "Agentic Retrieval", 4, 7,
                        "Experiments",
                        "TABLE",
                        "table-2",
                        null,
                        "Table 2 reports accuracy under increasing noise."),
                chunk("paper-a", "Agentic Retrieval", 4, 8,
                        "Experiments",
                        "TEXT",
                        null,
                        null,
                        "The high-noise setting stresses context scaling."),
                chunk("paper-a", "Agentic Retrieval", 5, 9,
                        "Limitations",
                        "TEXT",
                        null,
                        null,
                        "The method is limited by retrieval latency.")
        );

        List<PaperPageDocument> pages = PaperPageIndexBuilder.fromSearchResults(chunks);

        assertEquals(2, pages.size());
        PaperPageDocument page4 = pages.get(0);
        assertEquals("paper-a", page4.paperId());
        assertEquals("Agentic Retrieval", page4.paperTitle());
        assertEquals(4, page4.pageNumber());
        assertEquals(List.of(7, 8), page4.chunkIds());
        assertEquals(List.of("Experiments"), page4.sectionTitles());
        assertEquals(List.of("TABLE", "TEXT"), page4.sourceKinds());
        assertEquals(List.of("table-2"), page4.tableIds());
        assertTrue(page4.pageText().contains("increasing noise"));
        assertTrue(page4.locatorText().contains("Agentic Retrieval"));
        assertTrue(page4.locatorText().contains("Experiments"));
    }

    @Test
    void ranksPagesWithQuerySectionAndSourceKindSignals() {
        List<PaperPageDocument> pages = PaperPageIndexBuilder.fromSearchResults(List.of(
                chunk("paper-a", "Agentic Retrieval", 2, 3,
                        "Method",
                        "TEXT",
                        null,
                        null,
                        "The architecture uses a planner and retrieval tools."),
                chunk("paper-a", "Agentic Retrieval", 4, 7,
                        "Experiments",
                        "TABLE",
                        "table-2",
                        null,
                        "Table 2 reports accuracy under increasing noise."),
                chunk("paper-b", "Survey Paper", 1, 1,
                        "Introduction",
                        "TEXT",
                        null,
                        null,
                        "This survey introduces search systems.")
        ));

        List<PaperPageHit> hits = PaperPageLocator.rank(
                "高噪声实验 accuracy table",
                pages,
                3
        );

        assertEquals(3, hits.size());
        assertEquals("paper-a", hits.get(0).page().paperId());
        assertEquals(4, hits.get(0).page().pageNumber());
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertTrue(hits.get(0).reasons().contains("source:TABLE"));
        assertTrue(hits.get(0).reasons().contains("section:Experiments"));
    }

    @Test
    void keepsTargetTermRerankBehindExplicitRankingOption() {
        List<PaperPageDocument> pages = PaperPageIndexBuilder.fromSearchResults(List.of(
                chunk("paper-a", "Adaptive Retrieval", 4, 41,
                        "Evaluation Results",
                        "TEXT",
                        null,
                        null,
                        "We compare related approaches against evaluation datasets. "
                                + "Experiments report results and comparison tables."),
                chunk("paper-a", "Adaptive Retrieval", 9, 91,
                        "Baselines",
                        "TEXT",
                        null,
                        null,
                        "The baseline was a majority classifier used for comparison.")
        ));

        String expandedScientificQaQuery = "what was the baseline? baseline baselines compare compared comparison "
                + "related approaches against evaluation experiments results dataset";

        List<PaperPageHit> defaultHits = PaperPageLocator.rank(expandedScientificQaQuery, pages, 2);
        List<PaperPageHit> targetAwareHits = PaperPageLocator.rank(
                expandedScientificQaQuery,
                pages,
                2,
                PaperPageLocator.RankingOptions.withTargetTermBoost()
        );

        assertEquals(4, defaultHits.get(0).page().pageNumber());
        assertTrue(defaultHits.get(0).reasons().stream().noneMatch(reason -> reason.startsWith("target:")));
        assertEquals(9, targetAwareHits.get(0).page().pageNumber());
        assertTrue(targetAwareHits.get(0).reasons().contains("target:baseline"));
    }

    @Test
    void expandsNeighborPagesInsideTheSamePaperOnly() {
        List<PaperPageDocument> pages = PaperPageIndexBuilder.fromSearchResults(List.of(
                chunk("paper-a", "Agentic Retrieval", 3, 5, "Setup", "TEXT", null, null, "Setup details."),
                chunk("paper-a", "Agentic Retrieval", 4, 7, "Experiments", "TABLE", "table-2", null, "Noise results."),
                chunk("paper-a", "Agentic Retrieval", 5, 9, "Discussion", "TEXT", null, null, "Discussion text."),
                chunk("paper-b", "Other", 4, 1, "Experiments", "TEXT", null, null, "Other paper page.")
        ));
        PaperPageHit center = PaperPageLocator.rank("noise results", pages, 1).get(0);

        List<PaperPageDocument> expanded = PaperPageLocator.expandNeighbors(center.page(), pages, 1);

        assertEquals(List.of(3, 4, 5), expanded.stream().map(PaperPageDocument::pageNumber).toList());
        assertTrue(expanded.stream().allMatch(page -> page.paperId().equals("paper-a")));
    }

    private SearchResult chunk(String paperId,
                               String title,
                               int pageNumber,
                               int chunkId,
                               String sectionTitle,
                               String sourceKind,
                               String tableId,
                               String figureId,
                               String text) {
        SearchResult result = new SearchResult(paperId, chunkId, text, 1.0d);
        result.setPaperTitle(title);
        result.setOriginalFilename(title + ".pdf");
        result.setPageNumber(pageNumber);
        result.setSectionTitle(sectionTitle);
        result.setSourceKind(sourceKind);
        result.setTableId(tableId);
        result.setFigureId(figureId);
        result.setMatchedChunkText(text);
        return result;
    }
}
