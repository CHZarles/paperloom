package com.yizhaoqi.smartpai.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RagScorecard(
        String runId,
        String startedAt,
        String harnessId,
        String datasetId,
        int caseCount,
        int passed,
        int failed,
        double passRate,
        double routeAccuracy,
        double answerRequiredHitRate,
        double evidenceRequiredHitRate,
        double citationMappingRate,
        double badEvidenceRate,
        double scopeLeakRate,
        double falseNegativeRate,
        double avgScannedCount,
        double avgAcceptedEvidenceCount,
        double fallbackRate,
        Map<String, Double> metrics
) {
    public RagScorecard {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static RagScorecard from(String runId,
                                    String startedAt,
                                    String harnessId,
                                    String datasetId,
                                    RagBenchmarkRun run,
                                    Map<String, Double> additionalMetrics) {
        RagBenchmarkRun safeRun = run == null
                ? new RagBenchmarkRun(List.of(), List.of(), List.of())
                : run;
        List<RagBenchmarkVerdict> verdicts = safeRun.verdicts();
        int caseCount = verdicts.size();
        int passed = (int) verdicts.stream().filter(RagBenchmarkVerdict::passed).count();
        int failed = caseCount - passed;
        double passRate = fraction(passed, caseCount);
        double routeAccuracy = rateWithout(verdicts, "INTENT_ROUTE");
        double answerRequiredHitRate = rateWithout(verdicts, "ANSWER_QUALITY");
        double evidenceRequiredHitRate = rateWithout(verdicts, "FALSE_NEGATIVE");
        double citationMappingRate = rateWithout(verdicts, "CITATION_MAPPING");
        double badEvidenceRate = rateWith(verdicts, "BAD_EVIDENCE");
        double scopeLeakRate = rateWith(verdicts, "SCOPE_CONTROL");
        double falseNegativeRate = rateWith(verdicts, "FALSE_NEGATIVE");
        double avgScannedCount = averageDiagnostic(safeRun.actuals(), "scannedCount");
        double avgAcceptedEvidenceCount = averageDiagnostic(safeRun.actuals(), "acceptedEvidenceCount");
        double fallbackRate = fallbackRate(safeRun.actuals());
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("passRate", passRate);
        metrics.put("routeAccuracy", routeAccuracy);
        metrics.put("answerRequiredHitRate", answerRequiredHitRate);
        metrics.put("evidenceRequiredHitRate", evidenceRequiredHitRate);
        metrics.put("citationMappingRate", citationMappingRate);
        metrics.put("badEvidenceRate", badEvidenceRate);
        metrics.put("scopeLeakRate", scopeLeakRate);
        metrics.put("falseNegativeRate", falseNegativeRate);
        metrics.put("avgScannedCount", avgScannedCount);
        metrics.put("avgAcceptedEvidenceCount", avgAcceptedEvidenceCount);
        metrics.put("fallbackRate", fallbackRate);
        if (additionalMetrics != null) {
            metrics.putAll(additionalMetrics);
        }
        return new RagScorecard(
                runId,
                startedAt,
                harnessId,
                datasetId,
                caseCount,
                passed,
                failed,
                passRate,
                routeAccuracy,
                answerRequiredHitRate,
                evidenceRequiredHitRate,
                citationMappingRate,
                badEvidenceRate,
                scopeLeakRate,
                falseNegativeRate,
                avgScannedCount,
                avgAcceptedEvidenceCount,
                fallbackRate,
                metrics
        );
    }

    private static double rateWithout(List<RagBenchmarkVerdict> verdicts, String failureClass) {
        int total = verdicts.size();
        long count = verdicts.stream()
                .filter(verdict -> !verdict.failureClass().contains(failureClass))
                .count();
        return fraction(count, total);
    }

    private static double rateWith(List<RagBenchmarkVerdict> verdicts, String failureClass) {
        int total = verdicts.size();
        long count = verdicts.stream()
                .filter(verdict -> verdict.failureClass().contains(failureClass))
                .count();
        return fraction(count, total);
    }

    private static double averageDiagnostic(List<RagBenchmarkActual> actuals, String key) {
        return actuals.stream()
                .mapToDouble(actual -> numeric(actual.diagnostics().get(key)))
                .average()
                .orElse(0.0d);
    }

    private static double fallbackRate(List<RagBenchmarkActual> actuals) {
        if (actuals.isEmpty()) {
            return 0.0d;
        }
        long count = actuals.stream()
                .filter(actual -> truthy(actual.diagnostics().get("fallbackUsed")))
                .count();
        return fraction(count, actuals.size());
    }

    private static double numeric(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof String text && Boolean.parseBoolean(text);
    }

    private static double fraction(double numerator, double denominator) {
        return denominator == 0.0d ? 0.0d : numerator / denominator;
    }
}
