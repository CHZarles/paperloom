package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.SmartPaiApplication;
import com.yizhaoqi.smartpai.repository.PaperParserArtifactRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.repository.PaperVisualAssetRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProductPdfParserSmokeCli {

    private ProductPdfParserSmokeCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            Path runDir = runCommand(args);
            System.out.println("runDir=" + runDir);
            exitCode = RagEvalGateStatus.printFailureAndExitCode(runDir);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static Path runCommand(String[] args) throws Exception {
        return runCommand(args, startupArgs -> new SpringApplicationBuilder(SmartPaiApplication.class)
                .web(WebApplicationType.NONE)
                .run(startupArgs));
    }

    static Path runCommand(String[] args, SpringContextFactory contextFactory) throws Exception {
        Options options = Options.parse(args);
        try (ConfigurableApplicationContext context = contextFactory.start(springStartupArgs())) {
            return run(
                    context.getBean(PaperRepository.class),
                    context.getBean(PaperTextChunkRepository.class),
                    context.getBean(PaperParserArtifactRepository.class),
                    context.getBean(PaperVisualAssetRepository.class),
                    context.getBean(PaperReadingModelRepository.class),
                    context.getBean(PaperReadingElementRepository.class),
                    options
            );
        } catch (Exception exception) {
            return ProductPdfParserSmokeRunner.runStartupFailure(
                    new ProductPdfParserSmokeRunner.Options(
                            options.manifestPath(),
                            options.runsRoot(),
                            options.runId(),
                            options.startedAt(),
                            options.harnessId(),
                            options.datasetId()
                    ),
                    startupFailureMessage(exception)
            );
        }
    }

    private static String startupFailureMessage(Exception exception) {
        if (exception == null) {
            return "startup unavailable";
        }
        return sanitizeFailureMessage(exception.getClass().getSimpleName() + ": " + exception.getMessage());
    }

    static String sanitizeFailureMessage(String message) {
        String safeMessage = message == null || message.isBlank() ? "startup unavailable" : message;
        return safeMessage
                .replaceAll("(?i)(password|passwd|pwd|token|secret|api[_-]?key)=([^\\s,;]+)", "$1=<redacted>")
                .replaceAll("(?i)(password|passwd|pwd|token|secret|api[_-]?key)\\s*:\\s*([^\\s,;]+)", "$1=<redacted>")
                .replaceAll("://([^:/@\\s]+):([^@/\\s]+)@", "://$1:<redacted>@");
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

    @FunctionalInterface
    interface SpringContextFactory {
        ConfigurableApplicationContext start(String[] startupArgs);
    }

    public static Path run(PaperRepository paperRepository,
                           PaperTextChunkRepository chunkRepository,
                           PaperParserArtifactRepository parserArtifactRepository,
                           PaperVisualAssetRepository visualAssetRepository,
                           PaperReadingModelRepository readingModelRepository,
                           PaperReadingElementRepository readingElementRepository,
                           Options options) throws Exception {
        ProductPdfParserSmokeRunner runner = new ProductPdfParserSmokeRunner(
                paperRepository,
                chunkRepository,
                parserArtifactRepository,
                visualAssetRepository,
                readingModelRepository,
                readingElementRepository
        );
        return runner.run(new ProductPdfParserSmokeRunner.Options(
                options.manifestPath(),
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId()
        ));
    }

    public record Options(
            Path manifestPath,
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId
    ) {
        public Options {
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = startedAt == null || startedAt.isBlank() ? Instant.now().toString() : startedAt;
            harnessId = harnessId == null || harnessId.isBlank() ? "product-pdf-parser-smoke" : harnessId;
            datasetId = datasetId == null || datasetId.isBlank() ? "product-pdf-parser-smoke" : datasetId;
            runId = runId == null || runId.isBlank() ? defaultRunId(startedAt, harnessId, datasetId) : runId;
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
            String harnessId = values.getOrDefault("harness-id", "product-pdf-parser-smoke");
            String datasetId = values.getOrDefault("dataset-id", "product-pdf-parser-smoke");
            return new Options(
                    Path.of(required(values, "manifest")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    harnessId,
                    datasetId
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
