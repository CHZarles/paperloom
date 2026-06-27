package com.yizhaoqi.smartpai.eval;

import java.util.List;

public record RagBenchmarkRun(
        List<RagBenchmarkCase> cases,
        List<RagBenchmarkActual> actuals,
        List<RagBenchmarkVerdict> verdicts
) {
    public RagBenchmarkRun {
        cases = cases == null ? List.of() : cases;
        actuals = actuals == null ? List.of() : actuals;
        verdicts = verdicts == null ? List.of() : verdicts;
    }
}
