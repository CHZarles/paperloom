package io.github.chzarles.paperloom.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchHarnessHealthProbeTest {

    @Test
    void probeReportsHealthyWhenHarnessAnswers200() throws Exception {
        HttpServer server = startServer(200);
        try {
            ResearchHarnessHealthProbe probe = new ResearchHarnessHealthProbe(baseUrl(server), 2);
            assertTrue(probe.probe());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void probeReportsUnhealthyWhenHarnessAnswers503() throws Exception {
        HttpServer server = startServer(503);
        try {
            ResearchHarnessHealthProbe probe = new ResearchHarnessHealthProbe(baseUrl(server), 2);
            assertFalse(probe.probe());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void probeReportsUnhealthyWhenNothingListens() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ResearchHarnessHealthProbe probe =
                new ResearchHarnessHealthProbe("http://127.0.0.1:" + closedPort, 1);

        assertFalse(probe.probe());
    }

    @Test
    void trailingSlashInBaseUrlIsTolerated() throws Exception {
        HttpServer server = startServer(200);
        try {
            ResearchHarnessHealthProbe probe = new ResearchHarnessHealthProbe(baseUrl(server) + "/", 2);
            assertTrue(probe.probe());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer startServer(int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
