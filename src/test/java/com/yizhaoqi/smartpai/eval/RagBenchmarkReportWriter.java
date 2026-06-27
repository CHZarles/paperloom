package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class RagBenchmarkReportWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagBenchmarkReportWriter() {
    }

    public static void write(Path reportDir,
                             List<RagBenchmarkCase> cases,
                             List<RagBenchmarkVerdict> verdicts) throws IOException {
        Files.createDirectories(reportDir);
        List<RagBenchmarkCase> safeCases = cases == null ? List.of() : cases;
        List<RagBenchmarkVerdict> safeVerdicts = verdicts == null ? List.of() : verdicts;
        Files.writeString(reportDir.resolve("latest.md"), markdown(safeCases, safeVerdicts));
        Files.writeString(reportDir.resolve("latest.json"), OBJECT_MAPPER.writeValueAsString(json(safeCases, safeVerdicts)));
    }

    private static String markdown(List<RagBenchmarkCase> cases, List<RagBenchmarkVerdict> verdicts) {
        long passed = verdicts.stream().filter(RagBenchmarkVerdict::passed).count();
        Map<String, Long> failuresByClass = verdicts.stream()
                .flatMap(verdict -> verdict.failureClass().stream())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        StringBuilder builder = new StringBuilder()
                .append("# CiteWeave RAG-Bench Product Rescue\n\n")
                .append("Pass rate: ").append(passed).append("/").append(verdicts.size()).append("\n\n");
        if (!failuresByClass.isEmpty()) {
            builder.append("## Failure Classes\n\n");
            failuresByClass.forEach((name, count) -> builder.append("- ").append(name).append(": ").append(count).append("\n"));
            builder.append("\n");
        }
        Map<String, RagBenchmarkCase> casesById = cases.stream()
                .collect(Collectors.toMap(RagBenchmarkCase::id, Function.identity(), (left, ignored) -> left, LinkedHashMap::new));
        builder.append("## Cases\n\n");
        for (RagBenchmarkVerdict verdict : verdicts) {
            RagBenchmarkCase testCase = casesById.get(verdict.caseId());
            builder.append("- ")
                    .append(verdict.passed() ? "PASS" : "FAIL")
                    .append(" ")
                    .append(verdict.caseId());
            if (testCase != null) {
                builder.append(" - `").append(testCase.query()).append("`");
            }
            if (!verdict.failures().isEmpty()) {
                builder.append(" - ").append(String.join("; ", verdict.failures()));
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private static Map<String, Object> json(List<RagBenchmarkCase> cases, List<RagBenchmarkVerdict> verdicts) {
        long passed = verdicts.stream().filter(RagBenchmarkVerdict::passed).count();
        List<Map<String, Object>> caseResults = new ArrayList<>();
        Map<String, RagBenchmarkCase> casesById = cases.stream()
                .collect(Collectors.toMap(RagBenchmarkCase::id, Function.identity(), (left, ignored) -> left, LinkedHashMap::new));
        for (RagBenchmarkVerdict verdict : verdicts) {
            RagBenchmarkCase testCase = casesById.get(verdict.caseId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", verdict.caseId());
            row.put("passed", verdict.passed());
            row.put("query", testCase == null ? null : testCase.query());
            row.put("failureClass", verdict.failureClass());
            row.put("failures", verdict.failures());
            caseResults.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("total", verdicts.size());
        root.put("passed", passed);
        root.put("failed", verdicts.size() - passed);
        root.put("cases", caseResults);
        return root;
    }
}
