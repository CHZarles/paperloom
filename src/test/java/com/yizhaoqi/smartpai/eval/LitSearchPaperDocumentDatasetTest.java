package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LitSearchPaperDocumentDatasetTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void visitsJsonlDocumentsWithoutForcingCallersToMaterializeTheDataset() throws Exception {
        Path corpus = tempDir.resolve("litsearch-corpus.jsonl");
        Files.write(corpus, List.of(
                "# comment",
                "",
                OBJECT_MAPPER.writeValueAsString(new LitSearchPaperDocument(
                        "p1",
                        "Paper one",
                        "Abstract one",
                        "Body one",
                        List.of("c1")
                )),
                OBJECT_MAPPER.writeValueAsString(new LitSearchPaperDocument(
                        "p2",
                        "Paper two",
                        "Abstract two",
                        "Body two",
                        List.of()
                ))
        ));

        List<String> visitedPaperIds = new ArrayList<>();
        LitSearchPaperDocumentDataset.forEach(corpus, paper -> visitedPaperIds.add(paper.paperId()));

        assertEquals(List.of("p1", "p2"), visitedPaperIds);
    }

    @Test
    void visitsOnlyRequestedJsonlWindowWithoutParsingRowsPastTheLimit() throws Exception {
        Path corpus = tempDir.resolve("litsearch-corpus-window.jsonl");
        Files.write(corpus, List.of(
                OBJECT_MAPPER.writeValueAsString(paper("p1")),
                OBJECT_MAPPER.writeValueAsString(paper("p2")),
                "{not-json"
        ));

        List<String> visitedPaperIds = new ArrayList<>();
        LitSearchPaperDocumentDataset.forEachUntil(corpus, 1, 1, paper -> {
            visitedPaperIds.add(paper.paperId());
            return true;
        });

        assertEquals(List.of("p2"), visitedPaperIds);
    }

    private static LitSearchPaperDocument paper(String paperId) {
        return new LitSearchPaperDocument(
                paperId,
                "Title " + paperId,
                "Abstract " + paperId,
                "Body " + paperId,
                List.of()
        );
    }
}
