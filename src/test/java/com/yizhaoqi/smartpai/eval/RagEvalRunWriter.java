package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RagEvalRunWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagEvalRunWriter() {
    }

    public static Path write(Path runsRoot,
                             String runId,
                             String startedAt,
                             String harnessId,
                             String datasetId,
                             String datasetPath,
                             RagBenchmarkRun run,
                             Map<String, Double> additionalMetrics) throws IOException {
        RagBenchmarkRun safeRun = run == null
                ? new RagBenchmarkRun(List.of(), List.of(), List.of())
                : run;
        Path runDir = runsRoot.resolve(runId);
        Files.createDirectories(runDir);
        RagScorecard scorecard = RagScorecard.from(
                runId,
                startedAt,
                harnessId,
                datasetId,
                safeRun,
                additionalMetrics
        );
        OBJECT_MAPPER.writeValue(runDir.resolve("run.json").toFile(), runJson(
                runId,
                startedAt,
                harnessId,
                datasetId,
                datasetPath,
                safeRun
        ));
        OBJECT_MAPPER.writeValue(runDir.resolve("scorecard.json").toFile(), scorecard);
        Files.writeString(runDir.resolve("report.md"), reportMarkdown(scorecard, safeRun.verdicts()));
        return runDir;
    }

    private static Map<String, Object> runJson(String runId,
                                               String startedAt,
                                               String harnessId,
                                               String datasetId,
                                               String datasetPath,
                                               RagBenchmarkRun run) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runId", runId);
        root.put("startedAt", startedAt);
        root.put("harnessId", harnessId);
        root.put("datasetId", datasetId);
        root.put("datasetPath", datasetPath);
        root.put("gitCommit", "unknown");
        root.put("cases", caseRows(run));
        return root;
    }

    private static List<Map<String, Object>> caseRows(RagBenchmarkRun run) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < run.verdicts().size(); i++) {
            RagBenchmarkVerdict verdict = run.verdicts().get(i);
            RagBenchmarkCase testCase = i < run.cases().size() ? run.cases().get(i) : null;
            RagBenchmarkActual actual = i < run.actuals().size() ? run.actuals().get(i) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", verdict.caseId());
            row.put("query", testCase == null ? null : testCase.query());
            row.put("passed", verdict.passed());
            row.put("route", actual == null ? null : actual.route());
            row.put("markdown", actual == null ? "" : actual.markdown());
            row.put("referenceMappings", actual == null ? Map.of() : actual.referenceMappings());
            row.put("failures", verdict.failures());
            row.put("failureClass", verdict.failureClass());
            row.put("diagnostics", actual == null ? Map.of() : actual.diagnostics());
            rows.add(row);
        }
        return rows;
    }

    private static String reportMarkdown(RagScorecard scorecard, List<RagBenchmarkVerdict> verdicts) {
        StringBuilder builder = new StringBuilder()
                .append("# PaperLoom RAG Eval Run\n\n")
                .append("Harness: `").append(scorecard.harnessId()).append("`\n\n")
                .append("Dataset: `").append(scorecard.datasetId()).append("`\n\n")
                .append("Pass rate: ").append(scorecard.passed()).append("/")
                .append(scorecard.caseCount()).append("\n\n");
        for (RagBenchmarkVerdict verdict : verdicts) {
            builder.append("- ")
                    .append(verdict.passed() ? "PASS" : "FAIL")
                    .append(" ")
                    .append(verdict.caseId());
            if (!verdict.failures().isEmpty()) {
                builder.append(" - ").append(String.join("; ", verdict.failures()));
            }
            builder.append("\n");
        }
        return builder.toString();
    }
}
