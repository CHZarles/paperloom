package io.github.chzarles.paperloom.paper.parser;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinerUParserClientTest {

    @Test
    void reportsCloudApiHttpErrorsWithoutCallingThemUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v4/file-urls/batch", exchange -> respond(exchange, 404, "{}"));
        server.start();
        try {
            MinerUParserClient client = configuredClient("http://localhost:" + server.getAddress().getPort());

            PaperParsingException error = assertThrows(PaperParsingException.class,
                    () -> client.parse("pdf".getBytes(StandardCharsets.UTF_8), "paper.pdf"));

            assertEquals("MinerU cloud API returned HTTP 404 while attempting to request file upload URLs",
                    error.getMessage());
            assertTrue(error.getCause() instanceof WebClientResponseException.NotFound);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadsToMinerUCloudThenPollsAndDownloadsTheResultZip() throws Exception {
        byte[] resultZip = largeMinerUResultZip();
        AtomicReference<String> uploadRequest = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<byte[]> uploadedPdf = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            uploadRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String uploadUrl = "http://localhost:" + server.getAddress().getPort() + "/upload/paper.pdf";
            respond(exchange, 200, """
                    {"code":0,"msg":"ok","data":{"batch_id":"batch-1","file_urls":["%s"]}}
                    """.formatted(uploadUrl));
        });
        server.createContext("/upload/paper.pdf", exchange -> {
            assertEquals("PUT", exchange.getRequestMethod());
            assertEquals("application/pdf", exchange.getRequestHeaders().getFirst("Content-Type"));
            uploadedPdf.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> {
            String resultUrl = "http://localhost:" + server.getAddress().getPort() + "/result.zip";
            respond(exchange, 200, """
                    {"code":0,"msg":"ok","data":{"extract_result":[{"state":"done","full_zip_url":"%s"}]}}
                    """.formatted(resultUrl));
        });
        server.createContext("/result.zip", exchange -> respondBytes(exchange, 200, "application/zip", resultZip));
        server.start();
        try {
            MinerUParserClient client = configuredClient("http://localhost:" + server.getAddress().getPort());

            MinerUParserClient.MinerUParseResult result = client.parse(
                    "%PDF-test".getBytes(StandardCharsets.UTF_8),
                    "paper.pdf"
            );

            assertEquals("Bearer mineru-token", authorization.get());
            assertTrue(uploadRequest.get().contains("\"name\":\"paper.pdf\""));
            assertTrue(uploadRequest.get().contains("\"model_version\":\"vlm\""));
            assertArrayEquals("%PDF-test".getBytes(StandardCharsets.UTF_8), uploadedPdf.get());
            assertTrue(result.contentListJson().contains("Large MinerU result"));
            assertTrue(result.rawResultZipBytes().length > 262_144);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requiresCloudApiTokenBeforeSubmittingAFile() {
        MinerUParserClient client = configuredClient("http://localhost:1");
        ReflectionTestUtils.setField(client, "apiToken", "");

        PaperParsingException error = assertThrows(
                PaperParsingException.class,
                () -> client.parse("%PDF-test".getBytes(StandardCharsets.UTF_8), "paper.pdf")
        );

        assertTrue(error.getMessage().contains("PAPER_PARSING_MINERU_API_TOKEN"));
    }

    @Test
    void exchangesAccessKeyForATokenBeforeCallingTheCloudApi() throws Exception {
        AtomicReference<String> uploadedAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/auth", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"ak\":\"test-access-key\""));
            respond(exchange, 200, "{\"traceId\":\"t\",\"msgCode\":\"10000\",\"msg\":\"ok\","
                    + "\"data\":{\"nonce\":\"challenge-nonce\",\"algorithm\":\"HmacSHA1\"},\"success\":true}");
        });
        server.createContext("/getJwt", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"ak\":\"test-access-key\""));
            assertTrue(body.contains("\"d\":\"" + expectedSignature("challenge-nonce") + "\""));
            long exp = Instant.now().getEpochSecond() + 3600;
            String jwt = testJwt(exp);
            respond(exchange, 200, "{\"traceId\":\"t\",\"msgCode\":\"10000\",\"msg\":\"ok\","
                    + "\"data\":{\"jwt\":\"" + jwt + "\",\"expiration\":\"soon\",\"sso_uid\":1},\"success\":true}");
        });
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            uploadedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String uploadUrl = "http://localhost:" + server.getAddress().getPort() + "/upload/paper.pdf";
            respond(exchange, 200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"batch_id\":\"batch-1\","
                    + "\"file_urls\":[\"" + uploadUrl + "\"]}}");
        });
        server.createContext("/upload/paper.pdf", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> {
            String resultUrl = "http://localhost:" + server.getAddress().getPort() + "/result.zip";
            respond(exchange, 200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"extract_result\":"
                    + "[{\"state\":\"done\",\"full_zip_url\":\"" + resultUrl + "\"}]}}");
        });
        server.createContext("/result.zip", exchange ->
                respondBytes(exchange, 200, "application/zip", smallMinerUResultZip()));
        server.start();
        try {
            MinerUParserClient client = akskConfiguredClient("http://localhost:" + server.getAddress().getPort());

            client.parse("%PDF-test".getBytes(StandardCharsets.UTF_8), "paper.pdf");

            assertEquals("Bearer " + servedJwt(), uploadedAuthorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshesAnExpiredAccessTokenOnceAndRetriesTheRejectedCall() throws Exception {
        AtomicInteger batchCalls = new AtomicInteger();
        AtomicInteger authCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/auth", exchange -> {
            authCalls.incrementAndGet();
            respond(exchange, 200, "{\"traceId\":\"t\",\"msgCode\":\"10000\",\"msg\":\"ok\","
                    + "\"data\":{\"nonce\":\"challenge-nonce\",\"algorithm\":\"HmacSHA1\"},\"success\":true}");
        });
        server.createContext("/getJwt", exchange -> {
            long exp = Instant.now().getEpochSecond() + 3600;
            respond(exchange, 200, "{\"traceId\":\"t\",\"msgCode\":\"10000\",\"msg\":\"ok\","
                    + "\"data\":{\"jwt\":\"" + testJwt(exp) + "\"},\"success\":true}");
        });
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            if (batchCalls.incrementAndGet() == 1) {
                respond(exchange, 401, "{\"msgCode\":\"A0202\",\"msg\":\"user authenticate failed\"}");
                return;
            }
            respond(exchange, 200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\"\"]}}");
        });
        server.start();
        try {
            MinerUParserClient client = akskConfiguredClient("http://localhost:" + server.getAddress().getPort());

            PaperParsingException error = assertThrows(PaperParsingException.class,
                    () -> client.parse("%PDF-test".getBytes(StandardCharsets.UTF_8), "paper.pdf"));

            // The retried response carries a blank upload URL, so parsing stops after the auth retry succeeded.
            assertTrue(error.getMessage().contains("did not return an upload URL"));
            assertEquals(2, batchCalls.get());
            assertEquals(2, authCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryWithAStaticApiTokenAfterUnauthorizedResponses() throws Exception {
        AtomicInteger batchCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            batchCalls.incrementAndGet();
            respond(exchange, 401, "{\"msgCode\":\"A0202\",\"msg\":\"user authenticate failed\"}");
        });
        server.start();
        try {
            MinerUParserClient client = configuredClient("http://localhost:" + server.getAddress().getPort());

            PaperParsingException error = assertThrows(PaperParsingException.class,
                    () -> client.parse("%PDF-test".getBytes(StandardCharsets.UTF_8), "paper.pdf"));

            assertTrue(error.getMessage().contains("HTTP 401"));
            assertEquals(1, batchCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void toleratesTransientTaskNotFoundWhileMinerURegistersTheExtractionTask() throws Exception {
        AtomicInteger resultCalls = new AtomicInteger();
        byte[] resultZip = smallMinerUResultZip();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            String uploadUrl = "http://localhost:" + server.getAddress().getPort() + "/upload/paper.pdf";
            respond(exchange, 200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"batch_id\":\"batch-1\","
                    + "\"file_urls\":[\"" + uploadUrl + "\"]}}");
        });
        server.createContext("/upload/paper.pdf", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> {
            int call = resultCalls.incrementAndGet();
            if (call <= 2) {
                respond(exchange, 200, "{\"code\":-60012,\"msg\":\"task not found or expire\"}");
                return;
            }
            String resultUrl = "http://localhost:" + server.getAddress().getPort() + "/result.zip";
            respond(exchange, 200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"extract_result\":"
                    + "[{\"state\":\"done\",\"full_zip_url\":\"" + resultUrl + "\"}]}}");
        });
        server.createContext("/result.zip", exchange ->
                respondBytes(exchange, 200, "application/zip", resultZip));
        server.start();
        try {
            MinerUParserClient client = configuredClient("http://localhost:" + server.getAddress().getPort());

            MinerUParserClient.MinerUParseResult result = client.parse(
                    "%PDF-test".getBytes(StandardCharsets.UTF_8), "paper.pdf");

            assertTrue(result.contentListJson().contains("Small MinerU result"));
            assertEquals(3, resultCalls.get());
        } finally {
            server.stop(0);
        }
    }

    private static final AtomicReference<String> LAST_SERVED_JWT = new AtomicReference<>();

    private MinerUParserClient configuredClient(String baseUrl) {
        MinerUParserClient client = new MinerUParserClient();
        ReflectionTestUtils.setField(client, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(client, "apiToken", "mineru-token");
        ReflectionTestUtils.setField(client, "modelVersion", "vlm");
        ReflectionTestUtils.setField(client, "enableFormula", true);
        ReflectionTestUtils.setField(client, "enableTable", true);
        ReflectionTestUtils.setField(client, "timeoutSeconds", 5L);
        ReflectionTestUtils.setField(client, "pollIntervalSeconds", 1L);
        ReflectionTestUtils.setField(client, "maxResultInMemoryBytes", 1_048_576);
        return client;
    }

    private MinerUParserClient akskConfiguredClient(String baseUrl) {
        MinerUParserClient client = configuredClient(baseUrl);
        ReflectionTestUtils.setField(client, "apiToken", "");
        ReflectionTestUtils.setField(client, "authBaseUrl", baseUrl);
        ReflectionTestUtils.setField(client, "accessKey", "test-access-key");
        ReflectionTestUtils.setField(client, "secretKey", "test-secret-key");
        ReflectionTestUtils.setField(client, "tokenRefreshSkewSeconds", 300L);
        return client;
    }

    private String expectedSignature(String nonce) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    "test-secret-key".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String testJwt(long expEpochSecond) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"type\":\"JWT\",\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iss\":\"OpenXLab\",\"rol\":\"ROLE_REGISTER\",\"exp\":" + expEpochSecond + "}")
                .getBytes(StandardCharsets.UTF_8));
        String jwt = header + "." + payload + ".signature";
        LAST_SERVED_JWT.set(jwt);
        return jwt;
    }

    private String servedJwt() {
        return LAST_SERVED_JWT.get();
    }

    private byte[] smallMinerUResultZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("paper_content_list.json"));
            zip.write("[{\"type\":\"text\",\"text\":\"Small MinerU result\",\"page_idx\":0}]"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] largeMinerUResultZip() throws IOException {
        byte[] randomBytes = new byte[300_000];
        new Random(42).nextBytes(randomBytes);
        String largeText = Base64.getEncoder().encodeToString(randomBytes);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("paper_content_list.json"));
            zip.write(("""
                    [{"type":"text","text":"Large MinerU result %s","page_idx":0}]
                    """.formatted(largeText)).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("paper_layout.json"));
            zip.write("{\"pdf_info\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("full.md"));
            zip.write("# Large MinerU result".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("images/figure-1.jpg"));
            zip.write(randomBytes);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respondBytes(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, byte[] bytes)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
