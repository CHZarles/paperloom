package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QasperPageWindowDatasetWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesRagCasesChunksAndPageCasesFromQasperJson() throws Exception {
        Path ragCases = tempDir.resolve("qasper-mini-cases.jsonl");
        Path chunks = tempDir.resolve("qasper-mini-chunks.jsonl");
        Path pageCases = tempDir.resolve("qasper-mini-page-cases.jsonl");

        QasperPageWindowDatasetWriter.write(
                Path.of("src/test/resources/eval/qasper-mini.json"),
                ragCases,
                chunks,
                pageCases,
                10
        );

        List<RagBenchmarkCase> convertedCases = RagBenchmarkDataset.load(ragCases);
        assertEquals(1, convertedCases.size());
        assertEquals("qasper_1912_01214_b6f15fb6279b", convertedCases.get(0).id());

        List<PaperPageChunk> convertedChunks = Files.readAllLines(chunks).stream()
                .map(line -> {
                    try {
                        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(line, PaperPageChunk.class);
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                })
                .toList();
        assertEquals(2, convertedChunks.size());
        assertEquals("1912.01214", convertedChunks.get(0).paperId());
        assertEquals("Abstract", convertedChunks.get(0).sectionTitle());
        assertEquals(1, convertedChunks.get(0).pageNumber());
        assertEquals("Experiments", convertedChunks.get(1).sectionTitle());
        assertEquals(2, convertedChunks.get(1).pageNumber());
        assertTrue(convertedChunks.get(1).text().contains("multilingual NMT"));

        List<PaperPageLocatorCase> locatorCases = PaperPageLocatorCaseDataset.load(pageCases);
        assertEquals(1, locatorCases.size());
        assertEquals("qasper_1912_01214_b6f15fb6279b", locatorCases.get(0).id());
        assertEquals(List.of("1912.01214:2"), locatorCases.get(0).goldPageKeys());
    }
}
