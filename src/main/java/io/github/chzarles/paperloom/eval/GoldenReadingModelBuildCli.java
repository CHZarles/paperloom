package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.paper.parser.MinerUOutputMapper;
import io.github.chzarles.paperloom.paper.parser.MinerUPaperPdfParser;
import io.github.chzarles.paperloom.paper.parser.MinerUParserClient;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperArtifactPayload;
import io.github.chzarles.paperloom.service.PaperReadingModelBuildResult;
import io.github.chzarles.paperloom.service.PaperReadingModelBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GoldenReadingModelBuildCli {

    private GoldenReadingModelBuildCli() {
    }

    public static void main(String[] args) {
        try {
            Summary summary = run(Options.parse(args));
            System.out.println(new ObjectMapper().writeValueAsString(summary));
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static Summary run(Options options) throws Exception {
        byte[] pdfBytes = Files.readAllBytes(options.pdf());
        int pageCount;
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            pageCount = document.getNumberOfPages();
        }

        ParsedPaper parsedPaper;
        try (AnnotationConfigApplicationContext context = minerUContext(options.minerUBaseUrl());
             InputStream pdfInputStream = Files.newInputStream(options.pdf())) {
            MinerUParserClient client = context.getBean(MinerUParserClient.class);
            parsedPaper = new MinerUPaperPdfParser(client, new MinerUOutputMapper())
                    .parse(pdfInputStream, options.pdf().getFileName().toString());
        }

        PaperReadingModelBuildResult result = new PaperReadingModelBuilder().build(
                options.paperId(),
                options.modelVersion(),
                parsedPaper,
                pageCount,
                "golden-export",
                "golden-export",
                false
        );
        writeParserArtifacts(parsedPaper, options.artifactsDir());
        new GoldenReadingModelExportWriter().write(
                options.output(),
                new GoldenReadingModelExportWriter.ExportRequest(
                        options.packId(),
                        options.paperId(),
                        options.modelVersion(),
                        options.sourcePdfPath(),
                        options.pdf().getFileName().toString(),
                        options.title(),
                        pageCount,
                        pdfBytes,
                        parsedPaper,
                        result
                )
        );
        return new Summary(
                options.paperId(),
                options.output().toString(),
                parsedPaper.parserName(),
                parsedPaper.parserVersion(),
                pageCount,
                result.sections().size(),
                result.readingElements().size(),
                parsedPaper.tables() == null ? 0 : parsedPaper.tables().size(),
                parsedPaper.figures() == null ? 0 : parsedPaper.figures().size(),
                parsedPaper.formulas() == null ? 0 : parsedPaper.formulas().size()
        );
    }

    private static AnnotationConfigApplicationContext minerUContext(String baseUrl) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("paper.parsing.mineru.base-url", baseUrl);
        properties.put("paper.parsing.mineru.timeout-seconds", "1800");
        properties.put("paper.parsing.mineru.health-timeout-seconds", "5");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("golden-export", properties));
        context.register(MinerUParserClient.class);
        context.refresh();
        return context;
    }

    private static void writeParserArtifacts(ParsedPaper parsedPaper, Path artifactsDir) throws Exception {
        if (artifactsDir == null || parsedPaper.artifacts() == null) {
            return;
        }
        Files.createDirectories(artifactsDir);
        for (ParsedPaperArtifactPayload artifact : parsedPaper.artifacts()) {
            if (artifact == null || artifact.bytes() == null || artifact.bytes().length == 0) {
                continue;
            }
            String filename = Path.of(artifact.filename()).getFileName().toString();
            Files.write(artifactsDir.resolve(filename), artifact.bytes());
        }
    }

    record Summary(
            String paperId,
            String output,
            String parserName,
            String parserVersion,
            int pageCount,
            int sectionCount,
            int readingElementCount,
            int tableCount,
            int figureCount,
            int formulaCount
    ) {
    }

    record Options(
            String packId,
            String paperId,
            String modelVersion,
            Path pdf,
            Path output,
            Path artifactsDir,
            String sourcePdfPath,
            String title,
            String minerUBaseUrl
    ) {
        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length || !args[index].startsWith("--")) {
                    throw new IllegalArgumentException("Expected --name value arguments");
                }
                values.put(args[index].substring(2), args[index + 1]);
            }
            return new Options(
                    values.getOrDefault("pack-id", "llm_agent_evaluation"),
                    required(values, "paper-id"),
                    required(values, "model-version"),
                    Path.of(required(values, "pdf")),
                    Path.of(required(values, "output")),
                    optionalPath(values.get("artifacts-dir")),
                    required(values, "source-pdf-path"),
                    required(values, "title"),
                    values.getOrDefault("mineru-base-url", "http://127.0.0.1:8000")
            );
        }

        private static Path optionalPath(String value) {
            return value == null || value.isBlank() ? null : Path.of(value);
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
