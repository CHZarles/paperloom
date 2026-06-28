package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.SmartPaiApplication;
import com.yizhaoqi.smartpai.eval.model.EvalPaper;
import com.yizhaoqi.smartpai.eval.repository.EvalPaperRepository;
import com.yizhaoqi.smartpai.service.PaperRetrievalService;
import com.yizhaoqi.smartpai.service.RetrievalBudget;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServiceBackedLitSearchBenchmarkCli {

    private ServiceBackedLitSearchBenchmarkCli() {
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
                    context.getBean(EvalPaperRepository.class),
                    options
            );
            System.out.println("retrieved=" + options.retrievedPath());
            System.out.println("runDir=" + runDir);
        }
    }

    static String[] springStartupArgs() {
        return new String[]{
                "--elasticsearch.init.enabled=false",
                "--spring.kafka.listener.auto-startup=false",
                "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "--admin.bootstrap.enabled=false",
                "--paper.bootstrap.enabled=false"
        };
    }

    public static Path run(PaperRetrievalService retrievalService, Options options) throws Exception {
        return runWithScope(retrievalService, options, List.of());
    }

    public static Path run(PaperRetrievalService retrievalService,
                           EvalPaperRepository evalPaperRepository,
                           Options options) throws Exception {
        List<String> scopePaperIds = options.scopeImportedOnly()
                ? importedPaperIds(evalPaperRepository, options.retrievalCorpus(), options.evalSplit())
                : List.of();
        if (options.scopeImportedOnly()) {
            LitSearchDatasetIdGuard.rejectPartialCorpusSizeAsFull(
                    options.datasetId(),
                    scopePaperIds.size(),
                    "imported eval papers retrievalCorpus=" + options.retrievalCorpus()
                            + ", evalSplit=" + options.evalSplit()
            );
        }
        if (options.scopeImportedOnly() && scopePaperIds.isEmpty()) {
            throw new IllegalStateException(
                    "No imported eval papers found for retrievalCorpus=" + options.retrievalCorpus()
                            + ", evalSplit=" + options.evalSplit()
                            + ". Import the benchmark corpus first or disable --scope-imported-only."
            );
        }
        return runWithScope(retrievalService, options, scopePaperIds);
    }

    private static Path runWithScope(PaperRetrievalService retrievalService,
                                     Options options,
                                     List<String> scopePaperIds) throws Exception {
        return new ServiceBackedLitSearchBenchmarkRunner(retrievalService).run(new ServiceBackedLitSearchBenchmarkRunner.Options(
                options.goldPath(),
                options.retrievedPath(),
                options.runsRoot(),
                options.registryPath(),
                options.cheatsheetPath(),
                options.harnessId(),
                options.datasetId(),
                options.runId(),
                options.startedAt(),
                options.userId(),
                options.budget(),
                options.topK(),
                scopePaperIds
        ));
    }

    private static List<String> importedPaperIds(EvalPaperRepository evalPaperRepository,
                                                 RetrievalCorpus retrievalCorpus,
                                                 String evalSplit) {
        if (evalPaperRepository == null) {
            return List.of();
        }
        return evalPaperRepository.findByCorpusAndSplit(corpusName(retrievalCorpus), evalSplit).stream()
                .map(EvalPaper::getPaperId)
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .distinct()
                .toList();
    }

    private static String corpusName(RetrievalCorpus retrievalCorpus) {
        if (retrievalCorpus != RetrievalCorpus.EVAL_LITSEARCH) {
            throw new IllegalArgumentException("--retrieval-corpus must be EVAL_LITSEARCH");
        }
        return "litsearch";
    }

    public record Options(
            Path goldPath,
            Path retrievedPath,
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
            boolean scopeImportedOnly,
            RetrievalCorpus retrievalCorpus,
            String evalSplit
    ) {
        public Options(Path goldPath,
                       Path retrievedPath,
                       Path runsRoot,
                       Path registryPath,
                       Path cheatsheetPath,
                       String harnessId,
                       String datasetId,
                       String runId,
                       String startedAt,
                       String userId,
                       RetrievalBudget budget,
                       int topK) {
            this(goldPath, retrievedPath, runsRoot, registryPath, cheatsheetPath, harnessId, datasetId,
                    runId, startedAt, userId, budget, topK, false, RetrievalCorpus.EVAL_LITSEARCH, "full");
        }

        public Options {
            budget = budget == null ? RetrievalBudget.forLibrarySearch() : budget;
            topK = topK <= 0 ? 20 : topK;
            retrievalCorpus = retrievalCorpus == null ? RetrievalCorpus.EVAL_LITSEARCH : retrievalCorpus;
            corpusName(retrievalCorpus);
            evalSplit = evalSplit == null || evalSplit.isBlank() ? "full" : evalSplit;
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
            String harnessId = values.getOrDefault("harness-id", "current-evidence-ledger");
            String datasetId = values.getOrDefault("dataset-id", "litsearch-full");
            return new Options(
                    Path.of(required(values, "gold")),
                    Path.of(required(values, "retrieved")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    Path.of(values.getOrDefault("registry", "eval/rag/harnesses.yaml")),
                    Path.of(values.getOrDefault("cheatsheet", "eval/rag/CHEATSHEET.md")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    values.getOrDefault("user-id", "eval-litsearch-user"),
                    RetrievalBudget.forLibrarySearch(),
                    Integer.parseInt(values.getOrDefault("top-k", "20")),
                    Boolean.parseBoolean(values.getOrDefault("scope-imported-only", "false")),
                    requiredCorpus(values),
                    values.getOrDefault("eval-split", "full")
            );
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }

        private static RetrievalCorpus requiredCorpus(Map<String, String> values) {
            String value = required(values, "retrieval-corpus");
            RetrievalCorpus corpus;
            try {
                corpus = RetrievalCorpus.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("--retrieval-corpus must be EVAL_LITSEARCH");
            }
            if (corpus != RetrievalCorpus.EVAL_LITSEARCH) {
                throw new IllegalArgumentException("--retrieval-corpus must be EVAL_LITSEARCH");
            }
            return corpus;
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            String timestamp = startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z");
            return timestamp + "-" + harnessId + "-" + datasetId;
        }
    }
}
