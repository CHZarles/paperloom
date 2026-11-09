package io.github.chzarles.paperloom.paper.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class MinerUParserClient {

    private static final int DEFAULT_MAX_RESULT_IN_MEMORY_BYTES = 64 * 1024 * 1024;

    /** MinerU reports this business code while an uploaded batch has no extraction task registered yet. */
    private static final int RESULT_CODE_TASK_NOT_FOUND = -60012;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${paper.parsing.mineru.base-url:https://mineru.net}")
    private String baseUrl;

    @Value("${paper.parsing.mineru.api-token:}")
    private String apiToken;

    @Value("${paper.parsing.mineru.auth-base-url:https://sso.openxlab.org.cn/gw/uaa-be/api/v1/open}")
    private String authBaseUrl;

    @Value("${paper.parsing.mineru.access-key:}")
    private String accessKey;

    @Value("${paper.parsing.mineru.secret-key:}")
    private String secretKey;

    @Value("${paper.parsing.mineru.token-refresh-skew-seconds:300}")
    private long tokenRefreshSkewSeconds;

    /**
     * Access token exchanged from the MinerU AK/SK credentials. Guarded by {@link #tokenRefreshLock}.
     */
    private volatile String cachedAccessToken;
    private volatile long cachedAccessTokenExpirationEpochSecond;
    private final Object tokenRefreshLock = new Object();

    @Value("${paper.parsing.mineru.model-version:vlm}")
    private String modelVersion;

    @Value("${paper.parsing.mineru.enable-formula:true}")
    private boolean enableFormula;

    @Value("${paper.parsing.mineru.enable-table:true}")
    private boolean enableTable;

    @Value("${paper.parsing.mineru.timeout-seconds:3600}")
    private long timeoutSeconds;

    @Value("${paper.parsing.mineru.poll-interval-seconds:3}")
    private long pollIntervalSeconds;

    @Value("${paper.parsing.mineru.max-result-in-memory-bytes:67108864}")
    private int maxResultInMemoryBytes;

    public MinerUParseResult parse(byte[] pdfBytes, String originalFilename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PaperParsingException("PDF bytes must not be empty");
        }

        WebClient client = buildClient();
        String filename = normalizeFilename(originalFilename);
        JsonNode uploadRequest = requestUploadUrls(client, filename);
        JsonNode data = data(uploadRequest, "request file upload URLs");
        String batchId = requiredText(data, "batch_id", "request file upload URLs");
        JsonNode fileUrls = data.path("file_urls");
        if (!fileUrls.isArray() || fileUrls.isEmpty() || fileUrls.get(0).asText().isBlank()) {
            throw new PaperParsingException("MinerU API did not return an upload URL");
        }

        uploadFile(fileUrls.get(0).asText(), pdfBytes);
        JsonNode result = waitUntilCompleted(client, batchId);
        String resultUrl = requiredText(result, "full_zip_url", "get parse result");
        return parseResultBytes(downloadResult(client, resultUrl));
    }

    private WebClient buildClient() {
        int maxBytes = maxResultInMemoryBytes > 0 ? maxResultInMemoryBytes : DEFAULT_MAX_RESULT_IN_MEMORY_BYTES;
        return WebClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
    }

    private JsonNode requestUploadUrls(WebClient client, String filename) {
        Map<String, Object> request = Map.of(
                "files", List.of(Map.of("name", filename, "data_id", UUID.randomUUID().toString())),
                "model_version", normalizeModelVersion(),
                "enable_formula", enableFormula,
                "enable_table", enableTable
        );
        return postJson(client, "/api/v4/file-urls/batch", request, "request file upload URLs");
    }

    private JsonNode waitUntilCompleted(WebClient client, String batchId) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(Math.max(1, timeoutSeconds)).toMillis();
        // MinerU registers the extraction task asynchronously after the upload, and "task not found" is a
        // transient state during that registration window.
        long registrationGraceDeadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        while (System.currentTimeMillis() < deadline) {
            JsonNode response = getJsonRaw(client, "/api/v4/extract-results/batch/{batchId}", batchId, "get parse result");
            int responseCode = response.path("code").asInt(Integer.MIN_VALUE);
            if (responseCode == RESULT_CODE_TASK_NOT_FOUND
                    && System.currentTimeMillis() < registrationGraceDeadline) {
                sleep();
                continue;
            }
            if (responseCode != 0) {
                throw new PaperParsingException(
                        "MinerU API failed to get parse result: " + response.path("msg").asText("unknown API error"));
            }
            JsonNode results = data(response, "get parse result").path("extract_result");
            if (!results.isArray() || results.isEmpty()) {
                throw new PaperParsingException("MinerU API did not return the uploaded file result");
            }

            JsonNode result = results.get(0);
            String state = result.path("state").asText("").toLowerCase(Locale.ROOT);
            if ("done".equals(state)) {
                return result;
            }
            if ("failed".equals(state)) {
                String message = result.path("err_msg").asText("MinerU parsing failed");
                throw new PaperParsingException("MinerU parsing failed: " + message);
            }
            sleep();
        }
        throw new PaperParsingException("MinerU parsing timed out after " + timeoutSeconds + " seconds");
    }

    private JsonNode postJson(WebClient client, String path, Object request, String operation) {
        return performJsonCall(operation,
                token -> client.post()
                        .uri(path)
                        .headers(headers -> headers.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(requestTimeout()),
                body -> parseSuccessfulResponse(body, operation));
    }

    private JsonNode getJson(WebClient client, String path, String batchId, String operation) {
        return performJsonCall(operation,
                token -> client.get()
                        .uri(path, batchId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(30)),
                body -> parseSuccessfulResponse(body, operation));
    }

    private JsonNode getJsonRaw(WebClient client, String path, String batchId, String operation) {
        return performJsonCall(operation,
                token -> client.get()
                        .uri(path, batchId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(30)),
                body -> parseJsonTree(body));
    }

    /**
     * Runs a JSON call with the current bearer token. AK/SK-exchanged tokens expire after roughly an hour, so an
     * HTTP 401 triggers one token refresh and a single retry instead of failing a long-running parse.
     */
    private JsonNode performJsonCall(String operation, Function<String, String> call, Function<String, JsonNode> parser) {
        BearerToken bearer = resolveBearerToken();
        try {
            return parser.apply(call.apply(bearer.value()));
        } catch (PaperParsingException e) {
            throw e;
        } catch (WebClientResponseException.Unauthorized e) {
            if (!bearer.refreshable()) {
                throw apiRejected(operation, e);
            }
            cachedAccessToken = null;
            try {
                return parser.apply(call.apply(resolveBearerToken().value()));
            } catch (PaperParsingException retryError) {
                throw retryError;
            } catch (WebClientResponseException retryError) {
                throw apiRejected(operation, retryError);
            } catch (Exception retryError) {
                throw apiUnavailable(operation, retryError);
            }
        } catch (WebClientResponseException e) {
            throw apiRejected(operation, e);
        } catch (Exception e) {
            throw apiUnavailable(operation, e);
        }
    }

    private JsonNode parseSuccessfulResponse(String body, String operation) {
        try {
            JsonNode response = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (response.path("code").asInt(Integer.MIN_VALUE) == 0) {
                return response;
            }
            String message = response.path("msg").asText("unknown API error");
            throw new PaperParsingException("MinerU API failed to " + operation + ": " + message);
        } catch (PaperParsingException e) {
            throw e;
        } catch (Exception e) {
            throw new PaperParsingException("MinerU API returned an invalid response while attempting to " + operation, e);
        }
    }

    private JsonNode data(JsonNode response, String operation) {
        JsonNode data = response.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new PaperParsingException("MinerU API did not return data while attempting to " + operation);
        }
        return data;
    }

    private String requiredText(JsonNode node, String field, String operation) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new PaperParsingException("MinerU API did not return " + field + " while attempting to " + operation);
        }
        return value;
    }

    private void uploadFile(String uploadUrl, byte[] pdfBytes) {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(uploadUrl).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            // The presigned upload URL includes this content type in its signature; other values are rejected with 403.
            connection.setRequestProperty("Content-Type", "application/pdf");
            connection.setConnectTimeout(requestTimeoutMillis());
            connection.setReadTimeout(requestTimeoutMillis());
            connection.setFixedLengthStreamingMode(pdfBytes.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(pdfBytes);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("upload returned HTTP " + status);
            }
        } catch (Exception e) {
            throw apiUnavailable("upload the PDF", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] downloadResult(WebClient client, String resultUrl) {
        try {
            return client.get()
                    .uri(URI.create(resultUrl))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(requestTimeout());
        } catch (Exception e) {
            throw apiUnavailable("download the parse result", e);
        }
    }

    private BearerToken resolveBearerToken() {
        String normalizedAccessKey = accessKey == null ? "" : accessKey.trim();
        String normalizedSecretKey = secretKey == null ? "" : secretKey.trim();
        if (!normalizedAccessKey.isEmpty() && !normalizedSecretKey.isEmpty()) {
            return new BearerToken(getOrRefreshAccessToken(), true);
        }
        String token = apiToken == null ? "" : apiToken.trim();
        if (!token.isEmpty()) {
            return new BearerToken(token, false);
        }
        throw new PaperParsingException(
                "PAPER_PARSING_MINERU_ACCESS_KEY and PAPER_PARSING_MINERU_SECRET_KEY are required for the MinerU cloud API"
                        + " (or a PAPER_PARSING_MINERU_API_TOKEN with a manually issued token)");
    }

    private String getOrRefreshAccessToken() {
        if (isCachedAccessTokenValid()) {
            return cachedAccessToken;
        }
        synchronized (tokenRefreshLock) {
            if (isCachedAccessTokenValid()) {
                return cachedAccessToken;
            }
            String jwt = exchangeAccessToken();
            cachedAccessToken = jwt;
            // Refresh a little before the reported expiration so a parse never starts with a dying token.
            cachedAccessTokenExpirationEpochSecond =
                    accessTokenExpirationEpochSecond(jwt) - Math.max(0, tokenRefreshSkewSeconds);
            return jwt;
        }
    }

    private boolean isCachedAccessTokenValid() {
        return cachedAccessToken != null && !cachedAccessToken.isBlank()
                && cachedAccessTokenExpirationEpochSecond > 0
                && Instant.now().getEpochSecond() < cachedAccessTokenExpirationEpochSecond;
    }

    /**
     * MinerU now issues Access Key / Secret Key credential pairs instead of long-lived API tokens. The pair is
     * exchanged for a short-lived access token (a JWT, roughly one hour) through the OpenXLab SSO token service:
     * a nonce challenge signed with the secret key, then {@code getJwt} with the signature.
     */
    private String exchangeAccessToken() {
        WebClient authClient = WebClient.builder()
                .baseUrl(normalizeBaseUrl(authBaseUrl))
                .build();
        String normalizedAccessKey = accessKey.trim();
        JsonNode challenge = postAuthJson(authClient, "/auth", Map.of("ak", normalizedAccessKey));
        JsonNode challengeData = authData(challenge, "request the MinerU access-token challenge");
        String nonce = requiredText(challengeData, "nonce", "request the MinerU access-token challenge");
        String algorithm = requiredText(challengeData, "algorithm", "request the MinerU access-token challenge");
        String signature = signNonce(nonce, algorithm);
        JsonNode token = postAuthJson(authClient, "/getJwt", Map.of("ak", normalizedAccessKey, "d", signature));
        return requiredText(authData(token, "exchange the MinerU access token"), "jwt", "exchange the MinerU access token");
    }

    private JsonNode postAuthJson(WebClient client, String path, Object request) {
        String body;
        try {
            body = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
        } catch (WebClientResponseException e) {
            throw apiRejected("authenticate with the MinerU token service", e);
        } catch (Exception e) {
            throw apiUnavailable("authenticate with the MinerU token service", e);
        }
        JsonNode response = parseJsonTree(body);
        if (!"10000".equals(response.path("msgCode").asText(""))) {
            String message = response.path("msg").asText("unknown error");
            throw new PaperParsingException("MinerU token service rejected the credentials: " + message);
        }
        return response;
    }

    private JsonNode authData(JsonNode response, String operation) {
        JsonNode data = response.path("data");
        if (data.isMissingNode() || data.isNull() || data.isEmpty()) {
            throw new PaperParsingException("MinerU token service did not return data while attempting to " + operation);
        }
        return data;
    }

    private String signNonce(String nonce, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secretKey.trim().getBytes(StandardCharsets.UTF_8), algorithm));
            return Base64.getEncoder().encodeToString(mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new PaperParsingException(
                    "MinerU token service requested an unsupported signature algorithm: " + algorithm, e);
        }
    }

    private long accessTokenExpirationEpochSecond(String jwt) {
        // The exchanged token is a JWT whose exp claim carries the exact epoch; fall back to a conservative TTL.
        try {
            String[] segments = jwt.split("\\.");
            if (segments.length >= 2) {
                String payload = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
                long exp = objectMapper.readTree(payload).path("exp").asLong(0);
                if (exp > 0) {
                    return exp;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the conservative default.
        }
        return Instant.now().getEpochSecond() + 1800;
    }

    private JsonNode parseJsonTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new PaperParsingException("MinerU token service returned an invalid response", e);
        }
    }

    private record BearerToken(String value, boolean refreshable) {
    }

    private String normalizeFilename(String originalFilename) {
        return originalFilename == null || originalFilename.isBlank() ? "paper.pdf" : originalFilename.trim();
    }

    private String normalizeModelVersion() {
        return modelVersion == null || modelVersion.isBlank() ? "vlm" : modelVersion.trim();
    }

    private Duration requestTimeout() {
        return Duration.ofSeconds(Math.max(30, timeoutSeconds));
    }

    private int requestTimeoutMillis() {
        return (int) Math.min(Integer.MAX_VALUE, requestTimeout().toMillis());
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank() ? "https://mineru.net" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private MinerUUnavailableException apiUnavailable(String operation, Exception cause) {
        return new MinerUUnavailableException("MinerU cloud API unavailable while attempting to " + operation, cause);
    }

    private PaperParsingException apiRejected(String operation, WebClientResponseException cause) {
        return new PaperParsingException("MinerU cloud API returned HTTP " + cause.getStatusCode().value()
                + " while attempting to " + operation, cause);
    }

    private MinerUParseResult parseResultBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PaperParsingException("MinerU result was empty");
        }
        if (isZip(bytes)) {
            return parseZip(bytes);
        }
        throw new PaperParsingException("MinerU result was not a ZIP archive");
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
                } else if (name.endsWith("middle.json") || name.endsWith("layout.json")) {
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

    private String readZipEntry(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        zipInputStream.transferTo(outputStream);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    private void sleep() {
        try {
            Thread.sleep(Duration.ofSeconds(Math.max(1, pollIntervalSeconds)).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaperParsingException("Interrupted while waiting for MinerU parsing", e);
        }
    }

    public record MinerUParseResult(
            String contentListJson,
            String middleJson,
            String markdown,
            byte[] rawResultZipBytes
    ) {
    }
}
