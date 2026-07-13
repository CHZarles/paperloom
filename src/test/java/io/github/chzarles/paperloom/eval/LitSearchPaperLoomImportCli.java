package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.PaperLoomApplication;
import io.github.chzarles.paperloom.eval.repository.EvalChunkRepository;
import io.github.chzarles.paperloom.eval.repository.EvalPaperRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LitSearchPaperLoomImportCli {

    private LitSearchPaperLoomImportCli() {
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
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PaperLoomApplication.class)
                .web(WebApplicationType.NONE)
                .run(springStartupArgs())) {
            LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                    context.getBean(EvalPaperRepository.class),
                    context.getBean(EvalChunkRepository.class),
                    context.getBean(EvalCorpusIndexService.class)
            );
            LitSearchPaperLoomImporter.ImportSummary summary = importer.importJsonl(
                    options.corpusPath(),
                    new LitSearchPaperLoomImporter.Options(
                            options.evalSplit(),
                            options.maxChunkCharacters(),
                            options.indexBatchSize()
                    ),
                    options.startOffset(),
                    options.limit()
            );
            System.out.println("importedPapers=" + summary.paperCount());
            System.out.println("importedChunks=" + summary.chunkCount());
        }
    }

    static String[] springStartupArgs() {
        return new String[]{
                "--elasticsearch.init.enabled=false",
                "--spring.kafka.listener.auto-startup=false",
                "--spring.kafka.admin.auto-create=false",
                "--admin.bootstrap.enabled=false",
                "--paper.bootstrap.enabled=false",
                "--spring.jpa.show-sql=false",
                "--logging.level.root=WARN",
                "--logging.level.org.hibernate.SQL=WARN",
                "--logging.level.io.github.chzarles.paperloom.eval.EvalCorpusIndexService=WARN"
        };
    }

    public record Options(
            Path corpusPath,
            RetrievalCorpus retrievalCorpus,
            int startOffset,
            int limit,
            int maxChunkCharacters,
            String evalSplit,
            int indexBatchSize
    ) {
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
            return new Options(
                    Path.of(required(values, "corpus")),
                    requiredCorpus(values, RetrievalCorpus.EVAL_LITSEARCH),
                    Integer.parseInt(values.getOrDefault("start-offset", "0")),
                    Integer.parseInt(values.getOrDefault("limit", "0")),
                    Integer.parseInt(values.getOrDefault("max-chunk-characters", "1800")),
                    values.getOrDefault("eval-split", "full"),
                    Integer.parseInt(values.getOrDefault("index-batch-size", "500"))
            );
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }

        private static RetrievalCorpus requiredCorpus(Map<String, String> values, RetrievalCorpus expected) {
            String value = required(values, "retrieval-corpus");
            RetrievalCorpus corpus;
            try {
                corpus = RetrievalCorpus.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("--retrieval-corpus must be " + expected);
            }
            if (corpus != expected) {
                throw new IllegalArgumentException("--retrieval-corpus must be " + expected);
            }
            return corpus;
        }
    }
}
