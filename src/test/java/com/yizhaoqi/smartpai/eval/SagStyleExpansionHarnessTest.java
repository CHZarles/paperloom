package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagStyleExpansionHarnessTest {

    @Test
    void expandsFromQueryEntityHitToRelatedResultChunkThroughSharedEntities() {
        List<SearchResult> chunks = List.of(
                chunk(1, 1, "Method",
                        "We compare DenseRetriever against a Sparse Baseline in the evaluation setup."),
                chunk(2, 2, "Results",
                        "Table 2 reports the Sparse Baseline reaches 71 F1 while the neural run reaches 78 F1."),
                chunk(3, 1, "Introduction",
                        "This unrelated paragraph discusses annotation quality and crowd workers.")
        );

        List<SagStyleExpansionHarness.Hit> hits = SagStyleExpansionHarness.retrieve(
                "What are the evaluation results for DenseRetriever?",
                chunks,
                2
        );

        assertEquals(2, hits.size());
        assertEquals(2, hits.get(0).chunk().getChunkId());
        assertTrue(hits.get(0).reasons().contains("two-hop-entity"));
        assertTrue(hits.get(0).matchedEntities().contains("sparse"));
        assertEquals(1, hits.get(1).chunk().getChunkId());
    }

    private SearchResult chunk(int chunkId, int pageNumber, String sectionTitle, String text) {
        SearchResult result = new SearchResult("paper-a", chunkId, text, 1.0d);
        result.setPaperTitle("DenseRetriever Evaluation");
        result.setOriginalFilename("dense.pdf");
        result.setPageNumber(pageNumber);
        result.setSectionTitle(sectionTitle);
        result.setSourceKind("TEXT");
        result.setMatchedChunkText(text);
        return result;
    }
}
