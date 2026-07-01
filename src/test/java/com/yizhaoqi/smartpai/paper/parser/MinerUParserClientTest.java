package com.yizhaoqi.smartpai.paper.parser;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinerUParserClientTest {

    @Test
    void submitUsesMinerUAsyncApiContractAfterHealthCheck() throws Exception {
        AtomicBoolean healthCalled = new AtomicBoolean(false);
        AtomicReference<String> multipartBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/health", exchange -> {
            healthCalled.set(true);
            respond(exchange, 200, "{\"status\":\"ok\"}");
        });
        server.createContext("/tasks", exchange -> {
            multipartBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            respond(exchange, 200, """
                    {
                      "content_list": [
                        {"type": "text", "text": "MinerU parsed text", "page_idx": 0}
                      ],
                      "markdown": "# MinerU"
                    }
                    """);
        });
        server.start();
        try {
            MinerUParserClient client = configuredClient("http://localhost:" + server.getAddress().getPort());

            MinerUParserClient.MinerUParseResult result = client.parse("%PDF-test".getBytes(StandardCharsets.UTF_8), "paper.pdf");

            assertTrue(healthCalled.get());
            assertTrue(multipartBody.get().contains("name=\"files\""));
            assertTrue(multipartBody.get().contains("name=\"backend\""));
            assertTrue(multipartBody.get().contains("pipeline"));
            assertTrue(multipartBody.get().contains("name=\"parse_method\""));
            assertTrue(multipartBody.get().contains("auto"));
            assertTrue(multipartBody.get().contains("name=\"return_md\""));
            assertTrue(multipartBody.get().contains("name=\"return_content_list\""));
            assertTrue(multipartBody.get().contains("name=\"return_middle_json\""));
            assertTrue(multipartBody.get().contains("name=\"response_format_zip\""));
            assertTrue(multipartBody.get().contains("filename=\"paper.pdf\""));
            assertTrue(result.contentListJson().contains("MinerU parsed text"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableSidecarFailsWithActionableErrorBeforeSubmit() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        MinerUParserClient client = configuredClient("http://localhost:" + unusedPort);

        MinerUUnavailableException error = assertThrows(
                MinerUUnavailableException.class,
                () -> client.parse("%PDF-test".getBytes(StandardCharsets.UTF_8), "paper.pdf")
        );

        assertTrue(error.getMessage().contains("MinerU sidecar unavailable"));
        assertTrue(error.getMessage().contains("PAPER_PARSING_PROVIDER=opendataloader"));
    }

    @Test
    void downloadsLargeAsyncResultZip() throws Exception {
        byte[] resultZip = largeMinerUResultZip();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/tasks", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 202, "{\"task_id\":\"task-1\",\"status\":\"pending\"}");
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/tasks/task-1")) {
                respond(exchange, 200, "{\"task_id\":\"task-1\",\"status\":\"completed\"}");
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/tasks/task-1/result")) {
                respondBytes(exchange, 200, "application/zip", resultZip);
                return;
            }
            respond(exchange, 404, "{}");
        });
        server.start();
        try {
            MinerUParserClient client = configuredClient("http://localhost:" + server.getAddress().getPort());

            MinerUParserClient.MinerUParseResult result = client.parse("%PDF-test".getBytes(StandardCharsets.UTF_8), "paper.pdf");

            assertTrue(result.contentListJson().contains("Large MinerU result"));
            assertTrue(result.rawResultZipBytes().length > 262_144);
        } finally {
            server.stop(0);
        }
    }

    private MinerUParserClient configuredClient(String baseUrl) {
        MinerUParserClient client = new MinerUParserClient();
        ReflectionTestUtils.setField(client, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(client, "healthPath", "/health");
        ReflectionTestUtils.setField(client, "healthTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(client, "submitPath", "/tasks");
        ReflectionTestUtils.setField(client, "fileFieldName", "files");
        ReflectionTestUtils.setField(client, "backend", "pipeline");
        ReflectionTestUtils.setField(client, "parseMethod", "auto");
        ReflectionTestUtils.setField(client, "returnMarkdown", true);
        ReflectionTestUtils.setField(client, "returnContentList", true);
        ReflectionTestUtils.setField(client, "returnMiddleJson", true);
        ReflectionTestUtils.setField(client, "responseFormatZip", true);
        ReflectionTestUtils.setField(client, "statusPathTemplate", "/tasks/{taskId}");
        ReflectionTestUtils.setField(client, "resultPathTemplate", "/tasks/{taskId}/result");
        ReflectionTestUtils.setField(client, "timeoutSeconds", 5L);
        ReflectionTestUtils.setField(client, "pollIntervalSeconds", 1L);
        return client;
    }

    private byte[] largeMinerUResultZip() throws IOException {
        byte[] randomBytes = new byte[300_000];
        new Random(42).nextBytes(randomBytes);
        String largeText = Base64.getEncoder().encodeToString(randomBytes);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("content_list.json"));
            zip.write(("""
                    [
                      {"type":"text","text":"Large MinerU result %s","page_idx":0}
                    ]
                    """.formatted(largeText)).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("paper.md"));
            zip.write("# Large MinerU result".getBytes(StandardCharsets.UTF_8));
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

    private void respondBytes(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
