package com.yizhaoqi.smartpai.eval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

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
    void readingPhaseFlagMustBeExplicitlyEnabledForLaunch() {
        ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(Duration.ofSeconds(2));

        ProductLaunchRuntimePreflightRunner.ProbeResult disabled = probe.check(
                ProductLaunchRuntimePreflightRunner.ProbeRequest.readingFlag("false")
        );
        ProductLaunchRuntimePreflightRunner.ProbeResult enabled = probe.check(
                ProductLaunchRuntimePreflightRunner.ProbeRequest.readingFlag("true")
        );

        assertFalse(disabled.passed());
        assertTrue(disabled.failureClass().contains("CONFIG_MISSING"));
        assertTrue(enabled.passed());
    }
}
