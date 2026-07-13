package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.service.ChatHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RagLiveBenchmarkRunner {

    private final LiveChatClient liveChatClient;
    private final RagBenchmarkEvaluator evaluator;

    public RagLiveBenchmarkRunner(LiveChatClient liveChatClient, RagBenchmarkEvaluator evaluator) {
        this.liveChatClient = liveChatClient;
        this.evaluator = evaluator == null ? new RagBenchmarkEvaluator() : evaluator;
    }

    public RagBenchmarkRun run(List<RagBenchmarkCase> cases) {
        List<RagBenchmarkCase> safeCases = cases == null ? List.of() : cases;
        List<RagBenchmarkActual> actuals = new ArrayList<>();
        List<RagBenchmarkVerdict> verdicts = new ArrayList<>();
        for (RagBenchmarkCase testCase : safeCases) {
            LiveChatResponse response = liveChatClient.ask(testCase);
            RagBenchmarkActual actual = actualFor(response);
            actuals.add(actual);
            verdicts.add(evaluator.evaluate(testCase, actual));
        }
        return new RagBenchmarkRun(safeCases, actuals, verdicts);
    }

    private RagBenchmarkActual actualFor(LiveChatResponse response) {
        LiveChatResponse safeResponse = response == null
                ? new LiveChatResponse("", Map.of(), Map.of())
                : response;
        return new RagBenchmarkActual(
                routeFrom(safeResponse.diagnostics()),
                safeResponse.markdown(),
                safeResponse.referenceMappings(),
                safeResponse.diagnostics()
        );
    }

    private String routeFrom(Map<String, Object> diagnostics) {
        Object route = diagnostics == null ? null : diagnostics.get("route");
        return route == null ? "" : String.valueOf(route);
    }

    public interface LiveChatClient {
        LiveChatResponse ask(RagBenchmarkCase testCase);
    }

    public record LiveChatResponse(
            String markdown,
            Map<Integer, ChatHandler.ReferenceInfo> referenceMappings,
            Map<String, Object> diagnostics
    ) {
        public LiveChatResponse {
            markdown = markdown == null ? "" : markdown;
            referenceMappings = referenceMappings == null ? Map.of() : referenceMappings;
            diagnostics = diagnostics == null ? Map.of() : diagnostics;
        }
    }
}
