package io.github.chzarles.paperloom.service.discovery;

import com.sun.net.httpserver.HttpServer;
import io.github.chzarles.paperloom.config.OutboundWebClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArxivClientTest {

    @Test
    void oversizedPdfReportsTooLargeInsteadOfUnavailable() throws Exception {
        byte[] body = ("%PDF-1.5 " + "x".repeat(64)).getBytes(StandardCharsets.US_ASCII);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pdf/2409.04701", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            OutboundWebClientFactory factory = mock(OutboundWebClientFactory.class);
            when(factory.builder(anyString()))
                    .thenAnswer(invocation -> WebClient.builder().baseUrl(invocation.getArgument(0)));

            ArxivClient client = new ArxivClient(factory);
            ReflectionTestUtils.setField(client, "apiBaseUrl", "https://export.arxiv.org");
            ReflectionTestUtils.setField(client, "pdfBaseUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(client, "timeoutSeconds", 5);
            ReflectionTestUtils.setField(client, "maxPdfBytes", 8L);

            assertThrows(ArxivPdfTooLargeException.class, () -> client.downloadPdf("2409.04701"));
        } finally {
            server.stop(0);
        }
    }
}
