package com.yizhaoqi.smartpai.eval;

import java.util.List;

public record RagBenchmarkVerdict(
        String caseId,
        boolean passed,
        List<String> failures,
        List<String> failureClass
) {
    public RagBenchmarkVerdict {
        failures = failures == null ? List.of() : failures;
        failureClass = failureClass == null ? List.of() : failureClass;
    }
}
