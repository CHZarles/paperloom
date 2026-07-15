package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperMetadata;
import io.github.chzarles.paperloom.service.PaperReadingModelBuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenReadingModelExportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesTheProductionReadingModelShape() throws Exception {
        PaperReadingElement element = new PaperReadingElement();
        element.setPaperId("paper");
        element.setModelVersion("rm_test");
        element.setReadingElementId("reading_element_test");
        element.setLocationRef("reading_element_test");
        element.setElementType("PARAGRAPH");
        element.setPageNumber(1);
        element.setReadingOrder(2);
        element.setSectionTitle("Abstract");
        element.setSearchableText("A structured abstract.");
        element.setParserName("MinerU");
        element.setParserVersion("self-hosted");

        ParsedPaper parsedPaper = new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Parsed title", null, 1, null, null),
                List.of(),
                Map.of()
        );
        PaperReadingModelBuildResult result = new PaperReadingModelBuildResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(element),
                "{\"sectionCount\":1}"
        );
        Path output = tempDir.resolve("paper.reading-model.json");

        new GoldenReadingModelExportWriter().write(
                output,
                new GoldenReadingModelExportWriter.ExportRequest(
                        "pack",
                        "paper",
                        "rm_test",
                        "corpus/paper.pdf",
                        "paper.pdf",
                        "Authored title",
                        1,
                        "pdf".getBytes(),
                        parsedPaper,
                        result
                )
        );

        JsonNode root = new ObjectMapper().readTree(output.toFile());
        assertEquals("paperloom-reading-model-export/v1", root.path("schema_version").asText());
        assertEquals("Authored title", root.path("metadata").path("title").asText());
        assertEquals("Abstract", root.path("reading_elements").get(0).path("sectionTitle").asText());
        assertEquals(1, root.path("diagnostics").path("sectionCount").asInt());
        assertTrue(root.path("source_pdf_sha256").asText().matches("[0-9a-f]{64}"));
    }
}

