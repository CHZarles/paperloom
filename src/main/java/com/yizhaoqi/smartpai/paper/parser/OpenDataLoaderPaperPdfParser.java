package com.yizhaoqi.smartpai.paper.parser;

import org.opendataloader.pdf.api.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

@Component
@ConditionalOnProperty(prefix = "paper.parsing", name = "provider", havingValue = "opendataloader")
public class OpenDataLoaderPaperPdfParser implements PaperPdfParser {

    private static final String PROVIDER_NAME = "opendataloader-pdf";
    private static final String PROVIDER_VERSION = "2.4.7";

    private final OpenDataLoaderJsonMapper jsonMapper;
    private final OpenDataLoaderProcessRunner processRunner;

    @Value("${paper.parsing.opendataloader.reading-order:xycut}")
    private String readingOrder = Config.READING_ORDER_XYCUT;

    @Value("${paper.parsing.opendataloader.hybrid:off}")
    private String hybrid = Config.HYBRID_OFF;

    @Value("${paper.parsing.opendataloader.image-output:off}")
    private String imageOutput = Config.IMAGE_OUTPUT_OFF;

    @Value("${paper.parsing.opendataloader.timeout-seconds:300}")
    private long timeoutSeconds = 300L;

    public OpenDataLoaderPaperPdfParser(OpenDataLoaderJsonMapper jsonMapper) {
        this(jsonMapper, OpenDataLoaderProcessRunner.production());
    }

    OpenDataLoaderPaperPdfParser(OpenDataLoaderJsonMapper jsonMapper, OpenDataLoaderProcessRunner processRunner) {
        this.jsonMapper = jsonMapper;
        this.processRunner = processRunner;
    }

    @Override
    public ParsedPaper parse(InputStream pdfInputStream, String originalFilename) {
        if (pdfInputStream == null) {
            throw new PaperParsingException("PDF input stream must not be null");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("paperloom-opendataloader-");
            Path inputPdf = tempDir.resolve("input.pdf");
            Files.copy(pdfInputStream, inputPdf);

            Config config = buildConfig(tempDir);
            processRunner.processFile(inputPdf, config, Duration.ofSeconds(timeoutSeconds), safeName(originalFilename));

            Path jsonOutput = tempDir.resolve("input.json");
            if (!Files.exists(jsonOutput)) {
                throw new PaperParsingException("OpenDataLoader did not produce JSON output for " + safeName(originalFilename));
            }

            String json = Files.readString(jsonOutput);
            ParsedPaper parsedPaper = jsonMapper.map(json, providerName(), providerVersion());
            return jsonMapper.withOriginalFilename(parsedPaper, originalFilename);
        } catch (IOException e) {
            throw new PaperParsingException("Failed to parse PDF with OpenDataLoader: " + safeName(originalFilename), e);
        } finally {
            deleteQuietly(tempDir);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String providerVersion() {
        return PROVIDER_VERSION;
    }

    private Config buildConfig(Path outputDir) {
        Config config = new Config();
        config.setOutputFolder(outputDir.toString());
        config.setGenerateJSON(true);
        config.setGenerateMarkdown(false);
        config.setGenerateHtml(false);
        config.setGenerateText(false);
        config.setGeneratePDF(false);
        config.setImageOutput(imageOutput);
        config.setHybrid(hybrid);
        config.setReadingOrder(readingOrder);
        return config;
    }

    private void deleteQuietly(Path tempDir) {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary parser files should not interrupt request handling during cleanup.
                }
            });
        } catch (IOException ignored) {
            // Temporary parser files should not interrupt request handling during cleanup.
        }
    }

    private String safeName(String originalFilename) {
        return originalFilename == null || originalFilename.isBlank() ? "<unknown PDF>" : originalFilename;
    }
}
