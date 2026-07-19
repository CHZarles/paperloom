package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ProductPdfLaunchDataSeedHttpClient implements ProductPdfLaunchDataSeedRunner.LaunchDataSeedClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String apiBase;
    private final String token;
    private final Duration timeout;

    public ProductPdfLaunchDataSeedHttpClient(HttpClient httpClient,
                                              String apiBase,
                                              String token,
                                              Duration timeout) {
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.apiBase = trimTrailingSlash(apiBase == null || apiBase.isBlank()
                ? "http://127.0.0.1:8081/api/v1"
                : apiBase);
        this.token = token == null ? "" : token;
        this.timeout = timeout == null ? Duration.ofSeconds(180) : timeout;
    }

    @Override
    public void uploadChunk(ProductPdfLaunchDataSeedRunner.UploadChunkRequest request) {
        try {
            ProductPdfLaunchDataSeedRunner.UploadChunkRequest safeRequest = request == null
                    ? new ProductPdfLaunchDataSeedRunner.UploadChunkRequest("", 0, 0, "paper.pdf", 1, false, null, new byte[0])
                    : request;
            String boundary = "----PaperLoomLaunchSeed" + UUID.randomUUID();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/papers/upload/chunk"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(multipartBody(boundary, safeRequest)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            requireSuccessful(response, "upload chunk");
        } catch (Exception exception) {
            throw new IllegalStateException("upload chunk failed: " + requestSummary(request), exception);
        }
    }

    @Override
    public void merge(ProductPdfLaunchDataSeedRunner.MergeRequest request) {
        try {
            ProductPdfLaunchDataSeedRunner.MergeRequest safeRequest = request == null
                    ? new ProductPdfLaunchDataSeedRunner.MergeRequest("", "paper.pdf")
                    : request;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("paperId", safeRequest.paperId());
            body.put("paperTitle", safeRequest.paperTitle());
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/papers/upload/merge"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            requireSuccessful(response, "merge");
        } catch (Exception exception) {
            throw new IllegalStateException("merge failed: " + mergeSummary(request), exception);
        }
    }

    @Override
    public List<ProductPdfLaunchDataSeedRunner.PaperStatus> listUploadedPapers() {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/papers/uploads"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            requireSuccessful(response, "list uploaded papers");
            JsonNode data = OBJECT_MAPPER.readTree(response.body()).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<ProductPdfLaunchDataSeedRunner.PaperStatus> statuses = new ArrayList<>();
            for (JsonNode item : data) {
                Map<String, Object> raw = OBJECT_MAPPER.convertValue(item, new TypeReference<Map<String, Object>>() {});
                statuses.add(new ProductPdfLaunchDataSeedRunner.PaperStatus(
                        text(raw.get("paperId")),
                        firstText(raw, "originalFilename", "paperTitle"),
                        raw.get("uploadStatus"),
                        text(raw.get("processingStatus")),
                        longValue(raw.get("retrievalIndexedTokenCount")),
                        integerValue(raw.get("retrievalIndexedLocationCount")),
                        raw
                ));
            }
            return List.copyOf(statuses);
        } catch (Exception exception) {
            throw new IllegalStateException("list uploaded papers failed", exception);
        }
    }

    static List<byte[]> multipartBody(String boundary, ProductPdfLaunchDataSeedRunner.UploadChunkRequest request) {
        String safeBoundary = boundary == null || boundary.isBlank() ? "boundary" : boundary;
        List<byte[]> body = new ArrayList<>();
        addTextPart(body, safeBoundary, "paperId", request.paperId());
        addTextPart(body, safeBoundary, "chunkIndex", String.valueOf(request.chunkIndex()));
        addTextPart(body, safeBoundary, "totalSize", String.valueOf(request.totalSize()));
        addTextPart(body, safeBoundary, "paperTitle", request.paperTitle());
        addTextPart(body, safeBoundary, "totalChunks", String.valueOf(request.totalChunks()));
        addTextPart(body, safeBoundary, "isPublic", String.valueOf(request.isPublic()));
        if (request.orgTag() != null && !request.orgTag().isBlank()) {
            addTextPart(body, safeBoundary, "orgTag", request.orgTag());
        }
        body.add(("--" + safeBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + request.paperTitle() + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.add(request.bytes());
        body.add("\r\n".getBytes(StandardCharsets.UTF_8));
        body.add(("--" + safeBoundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body;
    }

    private static void addTextPart(List<byte[]> body, String boundary, String name, String value) {
        body.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value == null ? "" : value)
                + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void requireSuccessful(HttpResponse<String> response, String operation) {
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(operation + " returned status="
                    + response.statusCode() + ", body=" + response.body());
        }
    }

    private static String requestSummary(ProductPdfLaunchDataSeedRunner.UploadChunkRequest request) {
        if (request == null) {
            return "null";
        }
        return "paperId=" + request.paperId() + ", chunkIndex=" + request.chunkIndex();
    }

    private static String mergeSummary(ProductPdfLaunchDataSeedRunner.MergeRequest request) {
        if (request == null) {
            return "null";
        }
        return "paperId=" + request.paperId();
    }

    private static String firstText(Map<String, Object> raw, String first, String second) {
        String value = text(raw.get(first));
        return value == null || value.isBlank() ? text(raw.get(second)) : value;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            String text = text(value);
            return text == null || text.isBlank() ? null : Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = text(value);
            return text == null || text.isBlank() ? null : Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
