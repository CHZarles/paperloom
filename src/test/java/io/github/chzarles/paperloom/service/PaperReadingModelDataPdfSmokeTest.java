package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.PaperLoomApplication;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.paper.parser.MinerUUnavailableException;
import io.github.chzarles.paperloom.paper.parser.PaperPdfParser;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "paperloom.reading-model.real-pdf", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(
        classes = PaperLoomApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:paper_reading_model_smoke;MODE=MySQL;INIT=CREATE SCHEMA IF NOT EXISTS paperloom_eval;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.show-sql=false",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "elasticsearch.init.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "admin.bootstrap.enabled=false",
                "paper.bootstrap.enabled=false",
                "paper.parsing.provider=mineru",
                "paper.parsing.mineru.health-timeout-seconds=2"
        }
)
class PaperReadingModelDataPdfSmokeTest {

    @Autowired
    private PaperPdfParser paperPdfParser;

    private final PaperReadingModelBuilder builder = new PaperReadingModelBuilder();

    @Test
    void dataPdfsProduceReadablePagesAndPageLocations() throws Exception {
        List<Path> pdfs = List.of(
                Path.of("data/2308.03688.pdf"),
                Path.of("data/2401.13178.pdf"),
                Path.of("data/2503.05244.pdf")
        );

        for (Path pdf : pdfs) {
            assertTrue(Files.exists(pdf), "missing smoke PDF: " + pdf);
            int pdfPageCount;
            try (PDDocument document = PDDocument.load(pdf.toFile())) {
                pdfPageCount = document.getNumberOfPages();
            }
            ParsedPaper parsedPaper;
            try (InputStream inputStream = Files.newInputStream(pdf)) {
                try {
                    parsedPaper = paperPdfParser.parse(inputStream, pdf.getFileName().toString());
                } catch (MinerUUnavailableException exception) {
                    Assumptions.abort("MinerU sidecar unavailable for real PDF smoke: " + exception.getMessage());
                    return;
                }
            }

            PaperReadingModelBuildResult result = builder.build(
                    "smoke-" + pdf.getFileName(),
                    "rm_smoke",
                    parsedPaper,
                    pdfPageCount,
                    "smoke-user",
                    "smoke-org",
                    false
            );

            assertEquals(pdfPageCount, result.pages().size(), "physical page mismatch for " + pdf);
            long pageLocationCount = result.locations().stream()
                    .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                    .count();
            assertEquals(result.pages().size(), pageLocationCount, "page/location mismatch for " + pdf);
            assertTrue(result.pages().stream().anyMatch(page -> page.getCharCount() != null && page.getCharCount() > 0),
                    "no readable pages for " + pdf);
            assertTrue(result.diagnosticsJson().contains("\"readablePageCount\""), result.diagnosticsJson());
            assertTrue(result.diagnosticsJson().contains("\"locationCount\""), result.diagnosticsJson());
        }
    }
}
