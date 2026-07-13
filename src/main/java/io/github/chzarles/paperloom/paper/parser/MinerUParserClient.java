package io.github.chzarles.paperloom.paper.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class MinerUParserClient {

    private static final int DEFAULT_MAX_RESULT_IN_MEMORY_BYTES = 64 * 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${paper.parsing.mineru.base-url:http://localhost:8000}")
    private String baseUrl;

    @Value("${paper.parsing.mineru.health-path:/health}")
    private String healthPath;

    @Value("${paper.parsing.mineru.submit-path:/tasks}")
    private String submitPath;

    @Value("${paper.parsing.mineru.file-field-name:files}")
    private String fileFieldName;

    @Value("${paper.parsing.mineru.backend:pipeline}")
    private String backend;

    @Value("${paper.parsing.mineru.parse-method:auto}")
    private String parseMethod;

    @Value("${paper.parsing.mineru.return-md:true}")
    private boolean returnMarkdown;

    @Value("${paper.parsing.mineru.return-content-list:true}")
    private boolean returnContentList;

    @Value("${paper.parsing.mineru.return-middle-json:true}")
    private boolean returnMiddleJson;

    @Value("${paper.parsing.mineru.return-images:true}")
    private boolean returnImages;

    @Value("${paper.parsing.mineru.response-format-zip:true}")
    private boolean responseFormatZip;

    @Value("${paper.parsing.mineru.status-path-template:/tasks/{taskId}}")
    private String statusPathTemplate;

    @Value("${paper.parsing.mineru.result-path-template:/tasks/{taskId}/result}")
    private String resultPathTemplate;

    @Value("${paper.parsing.mineru.timeout-seconds:900}")
    private long timeoutSeconds;

    @Value("${paper.parsing.mineru.poll-interval-seconds:3}")
    private long pollIntervalSeconds;

    @Value("${paper.parsing.mineru.health-timeout-seconds:5}")
    private long healthTimeoutSeconds;

    @Value("${paper.parsing.mineru.max-result-in-memory-bytes:67108864}")
    private int maxResultInMemoryBytes;

    public MinerUParseResult parse(byte[] pdfBytes, String originalFilename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PaperParsingException("PDF bytes must not be empty");
        }
        assertSidecarAvailable();
        WebClient client = buildClient();

        JsonNode submitResponse = submitTask(client, pdfBytes, originalFilename);
        MinerUParseResult immediate = parseResultFromJsonIfPresent(submitResponse, null);
        if (immediate != null && immediate.hasStructuredOutput()) {
            return immediate;
        }

        String taskId = firstText(submitResponse, "task_id", "taskId", "id");
        if (taskId == null || taskId.isBlank()) {
            throw new PaperParsingException("MinerU submit response did not contain task id");
        }
        waitUntilCompleted(client, taskId);
        byte[] resultBytes = downloadResult(client, taskId);
        return parseResultBytes(resultBytes);
    }

    private WebClient buildClient() {
        int maxBytes = maxResultInMemoryBytes > 0 ? maxResultInMemoryBytes : DEFAULT_MAX_RESULT_IN_MEMORY_BYTES;
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
    }

    private JsonNode submitTask(WebClient client, byte[] pdfBytes, String originalFilename) {
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part(normalizeFileFieldName(), new ByteArrayResource(pdfBytes) {
                    @Override
                    public String getFilename() {
                        return originalFilename == null || originalFilename.isBlank() ? "paper.pdf" : originalFilename;
                    }
                })
                .filename(originalFilename == null || originalFilename.isBlank() ? "paper.pdf" : originalFilename)
                .contentType(MediaType.APPLICATION_PDF);
        multipart.part("backend", normalizeBackend());
        multipart.part("parse_method", normalizeParseMethod());
        multipart.part("table_enable", "true");
        multipart.part("formula_enable", "true");
        multipart.part("return_md", Boolean.toString(returnMarkdown));
        multipart.part("return_content_list", Boolean.toString(returnContentList));
        multipart.part("return_middle_json", Boolean.toString(returnMiddleJson));
        multipart.part("return_images", Boolean.toString(returnImages));
        multipart.part("response_format_zip", Boolean.toString(responseFormatZip));

        try {
            String body = client.post()
                    .uri(submitPath)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(multipart.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(30, timeoutSeconds)));
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            throw minerUUnavailable("Failed to submit PDF to MinerU sidecar at " + endpoint(submitPath), e);
        }
    }

    private void waitUntilCompleted(WebClient client, String taskId) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(timeoutSeconds).toMillis();
        while (System.currentTimeMillis() < deadline) {
            JsonNode status = fetchStatus(client, taskId);
            String state = firstText(status, "status", "state", "task_status");
            String normalized = state == null ? "" : state.toLowerCase(Locale.ROOT);
            if (normalized.equals("completed") || normalized.equals("complete")
                    || normalized.equals("done") || normalized.equals("success")
                    || normalized.equals("finished")) {
                return;
            }
            if (normalized.equals("failed") || normalized.equals("error") || normalized.equals("interrupted")) {
                String message = firstText(status, "error", "message", "detail");
                throw new PaperParsingException("MinerU task failed: " + (message == null ? state : message));
            }
            sleep();
        }
        throw new PaperParsingException("MinerU task timed out after " + timeoutSeconds + " seconds");
    }

    private JsonNode fetchStatus(WebClient client, String taskId) {
        try {
            String body = client.get()
                    .uri(statusPathTemplate, taskId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            throw minerUUnavailable("Failed to fetch MinerU task status from " + endpoint(statusPathTemplate), e);
        }
    }

    private byte[] downloadResult(WebClient client, String taskId) {
        try {
            return client.get()
                    .uri(resultPathTemplate, taskId)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(Math.max(30, timeoutSeconds)));
        } catch (Exception e) {
            throw minerUUnavailable("Failed to download MinerU result from " + endpoint(resultPathTemplate), e);
        }
    }

    private void assertSidecarAvailable() {
        URL url = buildUrl(healthPath);
        int timeoutMillis = (int) Duration.ofSeconds(Math.max(1, healthTimeoutSeconds)).toMillis();
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("User-Agent", "PaperLoom-MinerU-HealthCheck/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 400) {
                throw new IOException("MinerU health check returned HTTP " + status);
            }
        } catch (Exception e) {
            throw minerUUnavailable(
                    "MinerU sidecar unavailable at " + endpoint(healthPath)
                            + ". Start the self-hosted MinerU service or explicitly set PAPER_PARSING_PROVIDER=opendataloader for local fallback.",
                    e
            );
        }
    }

    private URL buildUrl(String path) {
        try {
            URI base = URI.create(normalizeBaseUrl(baseUrl));
            String normalizedPath = path == null || path.isBlank() ? "/" : path.trim();
            if (!normalizedPath.startsWith("/")) {
                normalizedPath = "/" + normalizedPath;
            }
            return base.resolve(normalizedPath).toURL();
        } catch (Exception e) {
            throw new MinerUUnavailableException("Invalid MinerU sidecar URL: " + baseUrl, e);
        }
    }

    private PaperParsingException minerUUnavailable(String message, Exception cause) {
        return new MinerUUnavailableException(message, cause);
    }

    private String normalizeFileFieldName() {
        return fileFieldName == null || fileFieldName.isBlank() ? "files" : fileFieldName.trim();
    }

    private String normalizeBackend() {
        return backend == null || backend.isBlank() ? "pipeline" : backend.trim();
    }

    private String normalizeParseMethod() {
        return parseMethod == null || parseMethod.isBlank() ? "auto" : parseMethod.trim();
    }

    private String endpoint(String path) {
        String normalizedBase = normalizeBaseUrl(baseUrl);
        String normalizedPath = path == null || path.isBlank() ? "/" : path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank() ? "http://localhost:8000" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private MinerUParseResult parseResultBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PaperParsingException("MinerU result was empty");
        }
        if (isZip(bytes)) {
            return parseZip(bytes);
        }
        try {
            JsonNode json = objectMapper.readTree(bytes);
            MinerUParseResult result = parseResultFromJsonIfPresent(json, bytes);
            if (result != null && result.hasStructuredOutput()) {
                return result;
            }
        } catch (Exception e) {
            throw new PaperParsingException("MinerU result was neither zip nor supported JSON", e);
        }
        throw new PaperParsingException("MinerU result did not include content_list output");
    }

    private MinerUParseResult parseResultFromJsonIfPresent(JsonNode json, byte[] rawBytes) {
        if (json == null || json.isMissingNode() || json.isNull()) {
            return null;
        }
        String contentList = rawJson(json, "content_list", "contentList");
        String middle = rawJson(json, "middle_json", "middle", "middleJson");
        String markdown = firstText(json, "markdown", "md", "document");
        if (contentList == null && markdown == null) {
            return null;
        }
        return new MinerUParseResult(contentList, middle, markdown, rawBytes);
    }

    private MinerUParseResult parseZip(byte[] zipBytes) {
        String contentList = null;
        String middle = null;
        String markdown = null;
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("content_list.json")) {
                    contentList = readZipEntry(zipInputStream);
                } else if (name.endsWith("middle.json")) {
                    middle = readZipEntry(zipInputStream);
                } else if (name.endsWith(".md") && markdown == null) {
                    markdown = readZipEntry(zipInputStream);
                }
            }
        } catch (Exception e) {
            throw new PaperParsingException("Failed to parse MinerU result zip", e);
        }
        if (contentList == null || contentList.isBlank()) {
            throw new PaperParsingException("MinerU result zip did not contain content_list.json");
        }
        return new MinerUParseResult(contentList, middle, markdown, zipBytes);
    }

    private String readZipEntry(ZipInputStream zipInputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        zipInputStream.transferTo(outputStream);
        return outputStream.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    private String firstText(JsonNode json, String... fields) {
        if (json == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = json.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String rawJson(JsonNode json, String... fields) {
        if (json == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = json.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                try {
                    return objectMapper.writeValueAsString(value);
                } catch (Exception ignored) {
                    return value.asText();
                }
            }
        }
        return null;
    }

    private void sleep() {
        try {
            Thread.sleep(Duration.ofSeconds(Math.max(1, pollIntervalSeconds)).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaperParsingException("Interrupted while waiting for MinerU task", e);
        }
    }

    public record MinerUParseResult(
            String contentListJson,
            String middleJson,
            String markdown,
            byte[] rawResultZipBytes
    ) {
        boolean hasStructuredOutput() {
            return contentListJson != null && !contentListJson.isBlank();
        }
    }
}
