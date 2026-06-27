package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LitSearchServiceSliceBuilderCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesGoldAndRetrievedCandidatePapersFromFullCorpusInCorpusOrder() throws Exception {
        Path gold = writeGold("q1", List.of("p1", "p2"));
        Path retrieved = writeRetrieved("q1", List.of("p3", "p1"));
        Path corpus = writeCorpus(List.of("p4", "p1", "p2", "p3"));
        Path output = tempDir.resolve("litsearch-service-slice.jsonl");

        LitSearchServiceSliceBuilderCli.Summary summary = LitSearchServiceSliceBuilderCli.build(
                new LitSearchServiceSliceBuilderCli.Options(
                        gold,
                        List.of(retrieved),
                        corpus,
                        output,
                        20
                )
        );

        List<LitSearchPaperDocument> papers = LitSearchPaperDocumentDataset.load(output);
        assertEquals(List.of("p1", "p2", "p3"), papers.stream().map(LitSearchPaperDocument::paperId).toList());
        assertEquals(3, summary.selectedCorpusIds());
        assertEquals(3, summary.writtenPapers());
        assertEquals(0, summary.missingCorpusIds());
    }

    @Test
    void limitsRetrievedCandidatesPerCaseWithoutDroppingGoldPapers() throws Exception {
        Path gold = writeGold("q1", List.of("p2"));
        Path retrieved = writeRetrieved("q1", List.of("p3", "p4"));
        Path corpus = writeCorpus(List.of("p2", "p3", "p4"));
        Path output = tempDir.resolve("litsearch-service-slice-capped.jsonl");

        LitSearchServiceSliceBuilderCli.build(new LitSearchServiceSliceBuilderCli.Options(
                gold,
                List.of(retrieved),
                corpus,
                output,
                1
        ));

        List<LitSearchPaperDocument> papers = LitSearchPaperDocumentDataset.load(output);
        assertEquals(List.of("p2", "p3"), papers.stream().map(LitSearchPaperDocument::paperId).toList());
    }

    private Path writeGold(String caseId, List<String> goldCorpusIds) throws Exception {
        Path path = tempDir.resolve(caseId + "-gold.jsonl");
        Files.writeString(path, OBJECT_MAPPER.writeValueAsString(new LitSearchBenchmarkCase(
                caseId,
                "LITSEARCH_RETRIEVAL",
                "mini",
                "find papers",
                0,
                0,
                goldCorpusIds
        )) + "\n");
        return path;
    }

    private Path writeRetrieved(String caseId, List<String> corpusIds) throws Exception {
        Path path = tempDir.resolve(caseId + "-retrieved.jsonl");
        Files.writeString(path, OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
                "caseId", caseId,
                "retrievedCorpusIds", corpusIds
        )) + "\n");
        return path;
    }

    private Path writeCorpus(List<String> paperIds) throws Exception {
        Path path = tempDir.resolve("corpus.jsonl");
        StringBuilder content = new StringBuilder();
        for (String paperId : paperIds) {
            content.append(OBJECT_MAPPER.writeValueAsString(new LitSearchPaperDocument(
                    paperId,
                    "Title " + paperId,
                    "Abstract " + paperId,
                    "Body " + paperId,
                    List.of()
            ))).append("\n");
        }
        Files.writeString(path, content.toString());
        return path;
    }
}
