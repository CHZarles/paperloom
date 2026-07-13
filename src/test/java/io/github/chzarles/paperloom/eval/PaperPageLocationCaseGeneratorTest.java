package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperPageLocationCaseGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void derivesGoldPageKeysFromProductEvidenceRegexAndChunks() throws Exception {
        Path ragCases = tempDir.resolve("product-smoke.jsonl");
        Files.write(ragCases, List.of(
                """
                        {"id":"noise","query":"讲一讲高噪声场景","scopeMode":"MANUAL_SOURCE","scope":{"paperIds":["paper-a"],"paperTitles":["Paper A"]},"expectedRoute":"MANUAL_SOURCE_QA","requiredEvidenceRegex":["increasing noise|noise","Experiment 2"],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                        """.trim(),
                """
                        {"id":"non_paper","query":"现在的session id是什么","scopeMode":"AUTO_SOURCE","scope":{"paperIds":[],"paperTitles":[]},"expectedRoute":"CLARIFY","requiredEvidenceRegex":[],"expectedPaperIds":[],"requiresCitation":false}
                        """.trim(),
                """
                        {"id":"reference_generic","query":"解释 [1]","scopeMode":"REFERENCE_SOURCE","scope":{"paperIds":["paper-a"],"paperTitles":["Paper A"]},"expectedRoute":"REFERENCE_QA","requiredEvidenceRegex":["."],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                        """.trim()
        ));
        Path chunks = tempDir.resolve("chunks.jsonl");
        Files.write(chunks, List.of(
                """
                        {"paperId":"paper-a","paperTitle":"Paper A","pageNumber":4,"chunkId":7,"sectionTitle":"Experiments","sourceKind":"TABLE","text":"Experiment 2: Context Scaling with Increasing Noise reports accuracy under increasing noise."}
                        """.trim(),
                """
                        {"paperId":"paper-b","paperTitle":"Paper B","pageNumber":2,"chunkId":1,"sectionTitle":"Experiments","sourceKind":"TEXT","text":"Experiment 2 also mentions noise, but this paper is out of scope."}
                        """.trim(),
                """
                        {"paperId":"paper-a","paperTitle":"Paper A","pageNumber":5,"chunkId":9,"sectionTitle":"Limitations","sourceKind":"TEXT","text":"Latency limitation."}
                        """.trim()
        ));
        Path output = tempDir.resolve("page-location-cases.jsonl");

        List<PaperPageLocatorCase> generated = PaperPageLocationCaseGenerator.write(
                ragCases,
                chunks,
                output
        );

        assertEquals(1, generated.size());
        assertEquals("noise", generated.get(0).id());
        assertEquals("讲一讲高噪声场景", generated.get(0).query());
        assertEquals(List.of("paper-a:4"), generated.get(0).goldPageKeys());
        assertEquals(generated, PaperPageLocatorCaseDataset.load(output));
    }
}
