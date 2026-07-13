package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

final class RagEvalGateStatus {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagEvalGateStatus() {
    }

    static GateResult read(Path runDir) throws IOException {
        if (runDir == null) {
            throw new IllegalArgumentException("runDir is required");
        }
        JsonNode root = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        return new GateResult(
                runDir,
                root.path("runId").asText(""),
                root.path("harnessId").asText(""),
                root.path("caseCount").asInt(),
                root.path("passed").asInt(),
                root.path("failed").asInt(),
                root.path("passRate").asDouble()
        );
    }

    static int printFailureAndExitCode(Path runDir) throws IOException {
        GateResult result = read(runDir);
        if (result.passing()) {
            return 0;
        }
        System.err.println(result.failureSummary());
        return 1;
    }

    record GateResult(
            Path runDir,
            String runId,
            String harnessId,
            int caseCount,
            int passed,
            int failed,
            double passRate
    ) {
        boolean passing() {
            return caseCount > 0 && failed == 0 && passed == caseCount;
        }

        String failureSummary() {
            return "eval gate failed: runId=" + runId
                    + ", harnessId=" + harnessId
                    + ", passed=" + passed + "/" + caseCount
                    + ", passRate=" + passRate
                    + ", runDir=" + runDir;
        }
    }
}
