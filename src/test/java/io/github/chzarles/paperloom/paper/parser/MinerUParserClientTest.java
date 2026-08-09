package io.github.chzarles.paperloom.paper.parser;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
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
