package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QasperBenchmarkConverterTest {

    @TempDir
    Path tempDir;

    @Test
    void convertsAnswerableQuestionsWithEvidenceToManualSourceCases() throws Exception {
        List<RagBenchmarkCase> cases = new QasperBenchmarkConverter()
                .convert(Path.of("src/test/resources/eval/qasper-mini.json"), 10);

        assertEquals(1, cases.size());
        RagBenchmarkCase testCase = cases.get(0);
        assertEquals("qasper_1912_01214_b6f15fb6279b", testCase.id());
        assertEquals("which multilingual approaches do they compare with?", testCase.query());
        assertEquals("en", testCase.language());
        assertEquals("QASPER_EVIDENCE_QA", testCase.taskType());
        assertEquals("MANUAL_SOURCE", testCase.scopeMode());
        assertEquals("MANUAL_SOURCE_QA", testCase.expectedRoute());
        assertEquals(List.of("1912.01214"), testCase.scope().paperIds());
        assertEquals(
                List.of("Cross-lingual Pre-training Based Transfer for Zero-shot Neural Machine Translation"),
                testCase.scope().paperTitles()
        );
        assertTrue(testCase.requiredAnswerRegex().stream().anyMatch(pattern -> pattern.contains("multilingual NMT")));
        assertTrue(testCase.requiredAnswerRegex().stream().noneMatch(pattern -> pattern.contains("BIBREF")));
        assertTrue(testCase.requiredEvidenceRegex().stream().anyMatch(pattern -> pattern.contains("related approaches of pivoting")));
        assertTrue(testCase.forbiddenEvidenceRegex().contains("^\\d+$"));
        assertTrue(testCase.requiresCitation());
    }

    @Test
    void writesConvertedCasesAsBenchmarkJsonl() throws Exception {
        QasperBenchmarkConverter converter = new QasperBenchmarkConverter();
        Path output = tempDir.resolve("qasper-dev-smoke.jsonl");

        converter.writeJsonl(Path.of("src/test/resources/eval/qasper-mini.json"), output, 10);

        List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(output);
        assertEquals(1, cases.size());
        assertEquals("QASPER_EVIDENCE_QA", cases.get(0).taskType());
        assertEquals(List.of("1912.01214"), cases.get(0).expectedPaperIds());
    }
}
