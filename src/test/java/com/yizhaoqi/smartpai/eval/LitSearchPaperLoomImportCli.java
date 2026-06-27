package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.SmartPaiApplication;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
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
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmartPaiApplication.class)
                .web(WebApplicationType.NONE)
                .run(springStartupArgs())) {
            LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                    context.getBean(PaperRepository.class),
                    context.getBean(PaperTextChunkRepository.class),
                    context.getBean(ElasticsearchService.class)
            );
            LitSearchPaperLoomImporter.ImportSummary summary = importer.importJsonl(
                    options.corpusPath(),
                    new LitSearchPaperLoomImporter.Options(
                            options.userId(),
                            options.orgTag(),
                            options.isPublic(),
                            options.maxChunkCharacters(),
                            options.evalSplit(),
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
                "--logging.level.com.yizhaoqi.smartpai.service.ElasticsearchService=WARN"
        };
    }

    public record Options(
            Path corpusPath,
            String userId,
            String orgTag,
            boolean isPublic,
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
                    values.getOrDefault("user-id", "eval-litsearch-user"),
                    values.getOrDefault("org-tag", "eval-litsearch"),
                    Boolean.parseBoolean(values.getOrDefault("public", "true")),
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
    }
}
