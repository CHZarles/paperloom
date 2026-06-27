package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.service.PaperAnswerService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RagBenchmarkRunner {

    private final PaperAnswerService paperAnswerService;
    private final RagBenchmarkEvaluator evaluator;

    public RagBenchmarkRunner(PaperAnswerService paperAnswerService, RagBenchmarkEvaluator evaluator) {
        this.paperAnswerService = paperAnswerService;
        this.evaluator = evaluator == null ? new RagBenchmarkEvaluator() : evaluator;
    }

    public RagBenchmarkRun run(String userId, String conversationId, List<RagBenchmarkCase> cases) {
        List<RagBenchmarkCase> safeCases = cases == null ? List.of() : cases;
        List<RagBenchmarkActual> actuals = new ArrayList<>();
        List<RagBenchmarkVerdict> verdicts = new ArrayList<>();
        for (RagBenchmarkCase testCase : safeCases) {
            PaperAnswerService.AnswerResult answer = paperAnswerService.answer(
                    userId,
                    conversationId,
                    testCase.query(),
                    scopeFor(testCase)
            );
            RagBenchmarkActual actual = actualFor(answer);
            actuals.add(actual);
            verdicts.add(evaluator.evaluate(testCase, actual));
        }
        return new RagBenchmarkRun(safeCases, actuals, verdicts);
    }

    private PaperAnswerService.AnswerScope scopeFor(RagBenchmarkCase testCase) {
        if (testCase == null || testCase.scope() == null) {
            return null;
        }
        if (!"MANUAL_SOURCE".equals(testCase.scopeMode())) {
            return null;
        }
        if (testCase.scope().paperIds().isEmpty() && testCase.scope().paperTitles().isEmpty()) {
            return null;
        }
        return new PaperAnswerService.AnswerScope(
                testCase.scope().paperIds(),
                testCase.scope().paperTitles(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private RagBenchmarkActual actualFor(PaperAnswerService.AnswerResult answer) {
        PaperAnswerService.AnswerResult safeAnswer = answer == null
                ? new PaperAnswerService.AnswerResult("", Map.of(), PaperAnswerService.Intent.CLARIFY, 0, 0, false)
                : answer;
        return new RagBenchmarkActual(
                safeAnswer.intent() == null ? "" : safeAnswer.intent().name(),
                safeAnswer.markdown(),
                safeAnswer.referenceMappings(),
                diagnosticsFor(safeAnswer.diagnostics())
        );
    }

    private Map<String, Object> diagnosticsFor(PaperAnswerService.AnswerDiagnostics diagnostics) {
        if (diagnostics == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("route", diagnostics.route());
        values.put("scopeMode", diagnostics.scopeMode());
        values.put("scannedCount", diagnostics.scannedCount());
        values.put("acceptedEvidenceCount", diagnostics.acceptedEvidenceCount());
        values.put("sourceCount", diagnostics.sourceCount());
        values.put("stopReason", diagnostics.stopReason());
        values.put("plannerRounds", diagnostics.plannerRounds());
        values.put("attemptedQueries", diagnostics.attemptedQueries());
        values.put("fallbackUsed", diagnostics.fallbackUsed());
        return values;
    }
}
