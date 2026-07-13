package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReadingLiveLaunchSmokeCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void loginFailureStillWritesFailedEvalRunForAllCases() throws Exception {
        Path cases = tempDir.resolve("live-smoke-cases.jsonl");
        Files.writeString(cases, """
                {"id":"session_state","message":"scope?","requiredToolNames":["get_session_state"]}
                {"id":"read_selected","message":"read","focusPaperHandleFromCase":"session_state","requiredToolNames":["read_locations"],"requiresReference":true}
                """);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/users/login", exchange -> {
            byte[] body = "{\"code\":500,\"message\":\"login unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path runDir = ProductReadingLiveLaunchSmokeCli.runCommand(new String[]{
                    "--api-base", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    "--cases", cases.toString(),
                    "--runs-root", tempDir.resolve("runs").toString(),
                    "--run-id", "live-login-failure"
            });

            JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
            assertEquals(2, scorecard.path("caseCount").asInt());
            assertEquals(0, scorecard.path("passed").asInt());
            JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
            for (JsonNode row : rows) {
                assertFalse(row.path("passed").asBoolean());
                assertTrue(row.path("failureClass").toString().contains("RUNTIME_UNAVAILABLE"));
                assertTrue(row.path("failures").toString().contains("live_smoke_startup_failed"));
            }
        } finally {
            server.stop(0);
        }
    }
}
