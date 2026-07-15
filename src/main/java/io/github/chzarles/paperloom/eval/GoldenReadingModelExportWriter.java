package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperMetadata;
import io.github.chzarles.paperloom.service.PaperReadingModelBuildResult;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GoldenReadingModelExportWriter {

    private static final String SCHEMA_VERSION = "paperloom-reading-model-export/v1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void write(Path output, ExportRequest request) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), toPayload(request));
        try {
            Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    Map<String, Object> toPayload(ExportRequest request) throws IOException {
        ParsedPaper parsedPaper = request.parsedPaper();
        PaperReadingModelBuildResult result = request.buildResult();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("pack_id", request.packId());
        payload.put("paper_id", request.paperId());
        payload.put("model_version", request.modelVersion());
        payload.put("source_pdf_path", request.sourcePdfPath());
        payload.put("source_pdf_sha256", sha256(request.pdfBytes()));
        payload.put("pdf_page_count", request.pdfPageCount());
        payload.put("parser_name", parsedPaper.parserName());
        payload.put("parser_version", parsedPaper.parserVersion());
        payload.put("metadata", metadata(request));
        payload.put("diagnostics", diagnostics(result.diagnosticsJson()));
        payload.put("pages", result.pages());
        payload.put("sections", result.sections());
        payload.put("locations", result.locations());
        payload.put("reading_elements", result.readingElements());
        payload.put("parsed_tables", safeList(parsedPaper.tables()));
        payload.put("parsed_figures", safeList(parsedPaper.figures()));
        payload.put("parsed_formulas", safeList(parsedPaper.formulas()));
        return payload;
    }

    private Map<String, Object> metadata(ExportRequest request) {
        ParsedPaperMetadata parsed = request.parsedPaper().metadata();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("originalFilename", request.originalFilename());
        metadata.put("title", firstNonBlank(request.title(), parsed == null ? null : parsed.title()));
        metadata.put("authors", parsed == null ? null : parsed.authors());
        metadata.put("pageCount", request.pdfPageCount());
        metadata.put("creationDate", parsed == null ? null : parsed.creationDate());
        metadata.put("modificationDate", parsed == null ? null : parsed.modificationDate());
        return metadata;
    }

    private JsonNode diagnostics(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(value);
    }

    private List<?> safeList(List<?> values) {
        return values == null ? List.of() : values;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                encoded.append(String.format("%02x", value));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record ExportRequest(
            String packId,
            String paperId,
            String modelVersion,
            String sourcePdfPath,
            String originalFilename,
            String title,
            int pdfPageCount,
            byte[] pdfBytes,
            ParsedPaper parsedPaper,
            PaperReadingModelBuildResult buildResult
    ) {
    }
}

