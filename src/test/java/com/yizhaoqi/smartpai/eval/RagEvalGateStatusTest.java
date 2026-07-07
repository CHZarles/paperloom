package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvalGateStatusTest {

    @TempDir
    Path tempDir;

    @Test
    void passingScorecardPassesTheGate() throws Exception {
        Path runDir = scorecard("""
                {"runId":"passing-run","harnessId":"product-launch-runtime-preflight","caseCount":10,"passed":10,"failed":0,"passRate":1.0}
                """);

        RagEvalGateStatus.GateResult result = RagEvalGateStatus.read(runDir);

        assertTrue(result.passing());
    }

    @Test
    void failedScorecardFailsTheGateWithConciseSummary() throws Exception {
        Path runDir = scorecard("""
                {"runId":"failed-run","harnessId":"product-launch-runtime-preflight","caseCount":10,"passed":5,"failed":5,"passRate":0.5}
                """);

        RagEvalGateStatus.GateResult result = RagEvalGateStatus.read(runDir);

        assertFalse(result.passing());
        assertTrue(result.failureSummary().contains("failed-run"));
        assertTrue(result.failureSummary().contains("passed=5/10"));
        assertTrue(result.failureSummary().contains(runDir.toString()));
    }

    @Test
    void zeroCaseScorecardFailsTheGate() throws Exception {
        Path runDir = scorecard("""
                {"runId":"empty-run","harnessId":"product-launch-runtime-preflight","caseCount":0,"passed":0,"failed":0,"passRate":0.0}
                """);

        assertFalse(RagEvalGateStatus.read(runDir).passing());
    }

    private Path scorecard(String json) throws Exception {
        Path runDir = tempDir.resolve("run-" + System.nanoTime());
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("scorecard.json"), json);
        return runDir;
    }
}
