package io.github.chzarles.paperloom.eval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPdfLaunchDataSeedHttpClientTest {

    @Test
    void sendsMultipartUploadChunkWithAuthAndFrontendCompatibleFields() throws Exception {
        try (RecordingServer server = RecordingServer.start()) {
            server.respond("/api/v1/papers/upload/chunk", """
                    {"code":200,"data":{"paperId":"paper-a","uploaded":[0]}}
                    """);
            ProductPdfLaunchDataSeedHttpClient client = client(server);

            client.uploadChunk(new ProductPdfLaunchDataSeedRunner.UploadChunkRequest(
                    "paper-a",
                    0,
                    3,
                    "paper-a.pdf",
                    1,
                    false,
                    "default",
                    "abc".getBytes(StandardCharsets.UTF_8)
            ));

            RecordingServer.RecordedRequest request = server.requests.get(0);
            assertEquals("POST", request.method());
            assertEquals("/api/v1/papers/upload/chunk", request.path());
            assertEquals("Bearer seed-token", request.header("Authorization"));
            assertTrue(request.header("Content-Type").contains("multipart/form-data"));
            assertTrue(request.body().contains("name=\"paperId\""));
            assertTrue(request.body().contains("paper-a"));
            assertTrue(request.body().contains("name=\"chunkIndex\""));
            assertTrue(request.body().contains("0"));
            assertTrue(request.body().contains("name=\"totalSize\""));
            assertTrue(request.body().contains("3"));
            assertTrue(request.body().contains("name=\"paperTitle\""));
            assertTrue(request.body().contains("paper-a.pdf"));
            assertTrue(request.body().contains("name=\"totalChunks\""));
            assertTrue(request.body().contains("1"));
            assertTrue(request.body().contains("name=\"isPublic\""));
            assertTrue(request.body().contains("false"));
            assertTrue(request.body().contains("abc"));
        }
    }

    @Test
    void mergeAndListUploadedPapersUseProductApiShape() throws Exception {
        try (RecordingServer server = RecordingServer.start()) {
            server.respond("/api/v1/papers/upload/merge", """
                    {"code":200,"data":{"paperId":"paper-a"}}
                    """);
            server.respond("/api/v1/papers/uploads", """
                    {"code":200,"data":[{"paperId":"paper-a","originalFilename":"paper-a.pdf","uploadStatus":1,"processingStatus":"COMPLETED","actualEmbeddingTokens":10,"actualChunkCount":2}]}
                    """);
            ProductPdfLaunchDataSeedHttpClient client = client(server);

            client.merge(new ProductPdfLaunchDataSeedRunner.MergeRequest("paper-a", "paper-a.pdf"));
            List<ProductPdfLaunchDataSeedRunner.PaperStatus> statuses = client.listUploadedPapers();

            assertEquals("POST", server.requests.get(0).method());
            assertEquals("/api/v1/papers/upload/merge", server.requests.get(0).path());
            assertTrue(server.requests.get(0).body().contains("\"paperId\":\"paper-a\""));
            assertTrue(server.requests.get(0).body().contains("\"paperTitle\":\"paper-a.pdf\""));
            assertEquals("GET", server.requests.get(1).method());
            assertEquals("/api/v1/papers/uploads", server.requests.get(1).path());
            assertEquals(1, statuses.size());
            assertEquals("paper-a", statuses.get(0).paperId());
            assertEquals("COMPLETED", statuses.get(0).processingStatus());
            assertEquals(10L, statuses.get(0).actualEmbeddingTokens());
            assertEquals(2, statuses.get(0).actualChunkCount());
        }
    }

    private ProductPdfLaunchDataSeedHttpClient client(RecordingServer server) {
        return new ProductPdfLaunchDataSeedHttpClient(
                HttpClient.newHttpClient(),
                server.baseUrl() + "/api/v1",
                "seed-token",
                Duration.ofSeconds(5)
        );
    }

    private static final class RecordingServer implements AutoCloseable {
        private final HttpServer server;
        private final List<RecordedRequest> requests = new ArrayList<>();

        private RecordingServer(HttpServer server) {
            this.server = server;
        }

        static RecordingServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RecordingServer recordingServer = new RecordingServer(server);
            server.start();
            return recordingServer;
        }

        void respond(String path, String body) {
            server.createContext(path, exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                requests.add(new RecordedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> String.join(",", entry.getValue())
                                )),
                        requestBody
                ));
                byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
                exchange.close();
            });
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record RecordedRequest(
                String method,
                String path,
                Map<String, String> headers,
                String body
        ) {
            private String header(String name) {
                return headers.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse("");
            }
        }
    }
}
