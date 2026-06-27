package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.SmartPaiApplication;
import com.yizhaoqi.smartpai.service.EvidenceLedgerService;
import com.yizhaoqi.smartpai.service.PaperPageWindowService;
import com.yizhaoqi.smartpai.service.PaperRetrievalService;
import com.yizhaoqi.smartpai.service.RetrievalBudget;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ServiceBackedPageWindowBenchmarkCli {

    private ServiceBackedPageWindowBenchmarkCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            runCommand(args);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static void runCommand(String[] args) throws Exception {
        Options options = Options.parse(args);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmartPaiApplication.class)
                .web(WebApplicationType.NONE)
                .run(springStartupArgs())) {
            Path runDir = run(
                    context.getBean(PaperRetrievalService.class),
                    context.getBean(PaperPageWindowService.class),
                    context.getBean(EvidenceLedgerService.class),
                    options
            );
            System.out.println("runDir=" + runDir);
        }
    }

    static String[] springStartupArgs() {
        return new String[]{
                "--elasticsearch.init.enabled=false",
                "--spring.kafka.listener.auto-startup=false",
                "--admin.bootstrap.enabled=false",
                "--paper.bootstrap.enabled=false"
        };
    }

    public static Path run(PaperRetrievalService retrievalService,
                           PaperPageWindowService pageWindowService,
                           EvidenceLedgerService evidenceLedgerService,
                           Options options) throws Exception {
        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                evidenceLedgerService
        );
        ServiceBackedPageWindowBenchmarkRunner runner = new ServiceBackedPageWindowBenchmarkRunner(harness);
        Path runDir = runner.run(new ServiceBackedPageWindowBenchmarkRunner.Options(
                options.casesPath(),
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId(),
                options.userId(),
                options.budget(),
                new ServiceBackedPageWindowHarness.Options(
                        options.topK(),
                        options.windowRadius(),
                        options.queryPlanner(),
                        options.candidateSource()
                )
        ));
        RagCheatsheetWriter.write(
                options.cheatsheetPath(),
                options.registryPath(),
                options.runsRoot(),
                options.startedAt()
        );
        return runDir;
    }

    public record Options(
            Path casesPath,
            Path runsRoot,
            Path registryPath,
            Path cheatsheetPath,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt,
            String userId,
            RetrievalBudget budget,
            int topK,
            int windowRadius,
            String queryPlanner,
            String candidateSource
    ) {
        public Options(Path casesPath,
                       Path runsRoot,
                       Path registryPath,
                       Path cheatsheetPath,
                       String harnessId,
                       String datasetId,
                       String runId,
                       String startedAt,
                       String userId,
                       RetrievalBudget budget,
                       int topK,
                       int windowRadius,
                       String queryPlanner) {
            this(
                    casesPath,
                    runsRoot,
                    registryPath,
                    cheatsheetPath,
                    harnessId,
                    datasetId,
                    runId,
                    startedAt,
                    userId,
                    budget,
                    topK,
                    windowRadius,
                    queryPlanner,
                    "first-stage"
            );
        }

        public Options {
            budget = budget == null ? RetrievalBudget.forQa() : budget;
            topK = topK <= 0 ? 3 : topK;
            windowRadius = Math.max(0, windowRadius);
            queryPlanner = queryPlanner == null || queryPlanner.isBlank() ? "scientific-qa" : queryPlanner;
            candidateSource = candidateSource == null || candidateSource.isBlank() ? "first-stage" : candidateSource;
        }

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }
            String startedAt = values.getOrDefault("started-at", Instant.now().toString());
            String harnessId = values.getOrDefault("harness-id", "service-backed-page-window");
            String datasetId = values.getOrDefault("dataset-id", "product-rescue-smoke");
            return new Options(
                    Path.of(required(values, "cases")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    Path.of(values.getOrDefault("registry", "eval/rag/harnesses.yaml")),
                    Path.of(values.getOrDefault("cheatsheet", "eval/rag/CHEATSHEET.md")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    values.getOrDefault("user-id", "eval-page-window-user"),
                    RetrievalBudget.forQa(),
                    Integer.parseInt(values.getOrDefault("top-k", "3")),
                    Integer.parseInt(values.getOrDefault("window-radius", "1")),
                    values.getOrDefault("query-planner", "scientific-qa"),
                    values.getOrDefault("candidate-source", "first-stage")
            );
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            return startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z")
                    + "-" + harnessId + "-" + datasetId;
        }
    }
}
