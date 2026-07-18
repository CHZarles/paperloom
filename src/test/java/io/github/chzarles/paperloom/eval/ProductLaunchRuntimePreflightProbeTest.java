package io.github.chzarles.paperloom.eval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductLaunchRuntimePreflightProbeTest {

    @Test
    void tcpProbePassesWhenPortAcceptsConnections() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

            ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                    ProductLaunchRuntimePreflightRunner.ProbeRequest.tcp(
                            "mysql_tcp",
                            "127.0.0.1",
                            socket.getLocalPort()
                    ));

            assertTrue(result.passed());
        }
    }

    @Test
    void tcpProbeFailsWhenPortIsUnavailable() {
        ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofMillis(200));

        ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                ProductLaunchRuntimePreflightRunner.ProbeRequest.tcp(
                        "mysql_tcp",
                        "127.0.0.1",
                        9
                ));

        assertFalse(result.passed());
        assertTrue(result.failureClass().contains("RUNTIME_UNAVAILABLE"));
    }

    @Test
    void httpHealthProbeAcceptsConfiguredStatusCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

            ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                    ProductLaunchRuntimePreflightRunner.ProbeRequest.http(
                            "mineru_health",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/health",
                            java.util.List.of(200)
                    ));

            assertTrue(result.passed());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void frontendHttpProbeRequiresSpaShellMarker() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    <!doctype html><html><body><div id="app"></div></body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

            ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                    ProductLaunchRuntimePreflightRunner.ProbeRequest.frontendHttp(
                            "http://127.0.0.1:" + server.getAddress().getPort()
                    ));

            assertTrue(result.passed());
            assertTrue(Boolean.parseBoolean(String.valueOf(result.diagnostics().get("bodyMarkerPresent"))));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void frontendHttpProbeFailsWhenSpaShellMarkerIsMissing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<html><body>not the app</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

            ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                    ProductLaunchRuntimePreflightRunner.ProbeRequest.frontendHttp(
                            "http://127.0.0.1:" + server.getAddress().getPort()
                    ));

            assertFalse(result.passed());
            assertTrue(result.failureClass().contains("RUNTIME_UNAVAILABLE"));
            assertEquals(false, result.diagnostics().get("bodyMarkerPresent"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loginProbeRequiresTokenInResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/users/login", exchange -> {
            byte[] body = "{\"code\":200,\"data\":{\"token\":\"token-a\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

            ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                    ProductLaunchRuntimePreflightRunner.ProbeRequest.login(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                            "admin",
                            "secret"
                    ));

            assertTrue(result.passed());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blankConfigProbeFailsWithConfigMissing() {
        ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

        ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                ProductLaunchRuntimePreflightRunner.ProbeRequest.nonBlank("llm_key", "DEEPSEEK_API_KEY", "")
        );

        assertFalse(result.passed());
        assertTrue(result.failureClass().contains("CONFIG_MISSING"));
        assertTrue(String.valueOf(result.diagnostics().get("key")).contains("DEEPSEEK_API_KEY"));
    }

    @Test
    void disabledTraceConfigFailsWithConfigMissing() {
        ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

        ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                ProductLaunchRuntimePreflightRunner.ProbeRequest.traceConfig("false", "data/traces/product-react")
        );

        assertFalse(result.passed());
        assertTrue(result.failureClass().contains("CONFIG_MISSING"));
    }

    @Test
    void llmApiSmokeRequiresCallableChatCompletionsProvider() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            assertEquals("Bearer llm-key", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"choices":[{"message":{"content":"OK"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

            ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                    ProductLaunchRuntimePreflightRunner.ProbeRequest.llmApiSmoke(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "deepseek-chat",
                            "llm-key"
                    ));

            assertTrue(result.passed());
            assertTrue(Boolean.parseBoolean(String.valueOf(result.diagnostics().get("assistantMessagePresent"))));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerSmokeDoesNotSendRequestWhenKeyIsBlank() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            throw new AssertionError("blank-key provider smoke should not send an outbound request");
        });
        server.start();
        try {
            ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

            ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                    ProductLaunchRuntimePreflightRunner.ProbeRequest.llmApiSmoke(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "deepseek-chat",
                            ""
                    ));

            assertFalse(result.passed());
            assertTrue(result.failureClass().contains("CONFIG_MISSING"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerAuthFailureIsConfigInvalid() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

            ProductLaunchRuntimePreflightRunner.ProbeResult result = probe.check(
                    ProductLaunchRuntimePreflightRunner.ProbeRequest.llmApiSmoke(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "deepseek-chat",
                            "bad-key"
                    ));

            assertFalse(result.passed());
            assertTrue(result.failureClass().contains("CONFIG_INVALID"));
            assertFalse(result.diagnostics().toString().contains("bad-key"));
        } finally {
            server.stop(0);
        }
    }
}
