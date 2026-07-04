package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.SmartPaiApplication;
import com.yizhaoqi.smartpai.paper.parser.PaperPdfParser;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
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
        classes = SmartPaiApplication.class,
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
            ParsedPaper parsedPaper;
            try (InputStream inputStream = Files.newInputStream(pdf)) {
                parsedPaper = paperPdfParser.parse(inputStream, pdf.getFileName().toString());
            }

            PaperReadingModelBuildResult result = builder.build(
                    "smoke-" + pdf.getFileName(),
                    "rm_smoke",
                    parsedPaper,
                    "smoke-user",
                    "smoke-org",
                    false
            );

            assertFalse(result.pages().isEmpty(), "no pages for " + pdf);
            assertEquals(result.pages().size(), result.locations().size(), "page/location mismatch for " + pdf);
            assertTrue(result.diagnosticsJson().contains("\"readablePageCount\""), result.diagnosticsJson());
            assertTrue(result.diagnosticsJson().contains("\"locationCount\""), result.diagnosticsJson());
        }
    }
}
