package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPdfLaunchDataSeedCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void loginFailureStillWritesFailedEvalRunForTheManifest() throws Exception {
        Path pdf = tempDir.resolve("launch.pdf");
        Files.writeString(pdf, "%PDF-1.4\nlogin failure seed");
        Path manifest = tempDir.resolve("manifest.jsonl");
        Files.writeString(manifest, "{\"id\":\"launch_pdf\",\"path\":\"" + pdf + "\"}\n");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/users/login", exchange -> {
            byte[] body = "{\"code\":500,\"message\":\"login unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path runDir = ProductPdfLaunchDataSeedCli.runCommand(new String[]{
                    "--api-base", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    "--manifest", manifest.toString(),
                    "--runs-root", tempDir.resolve("runs").toString(),
                    "--run-id", "login-failure-seed",
                    "--poll-attempts", "1",
                    "--poll-interval-millis", "0"
            });

            JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases").get(0);
            assertFalse(row.path("passed").asBoolean());
            assertTrue(row.path("failures").toString().contains("login_failed"));
            assertTrue(row.path("failureClass").toString().contains("RUNTIME_UNAVAILABLE"));
        } finally {
            server.stop(0);
        }
    }
}
