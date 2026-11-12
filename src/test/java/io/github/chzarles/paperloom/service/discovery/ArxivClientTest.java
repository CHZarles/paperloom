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
    void externalReadUsesFixedHttpsOriginPreservesVersionAndSendsNoToken() {
        OutboundWebClientFactory factory = mock(OutboundWebClientFactory.class);
        when(factory.builder(anyString())).thenAnswer(invocation -> {
            org.junit.jupiter.api.Assertions.assertEquals("https://arxiv.org", invocation.getArgument(0));
            return WebClient.builder().baseUrl(invocation.getArgument(0)).exchangeFunction(request -> {
                org.junit.jupiter.api.Assertions.assertEquals("https://arxiv.org/pdf/2309.13420v2", request.url().toString());
                org.junit.jupiter.api.Assertions.assertNull(request.headers().getFirst("Authorization"));
                return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                        .create(org.springframework.http.HttpStatus.OK).body("%PDF-1.5 content").build());
            });
        });
        ArxivClient client = new ArxivClient(factory);
        // The external tool must not inherit a configurable arbitrary PDF origin.
        ReflectionTestUtils.setField(client, "pdfBaseUrl", "http://internal/private");
        org.junit.jupiter.api.Assertions.assertTrue(client.downloadExternalPdf("2309.13420v2").length > 5);
    }

    @Test
    void externalReadRejectsRedirectsNonPdfAndOversizedResponses() {
        for (String kind : java.util.List.of("redirect", "html", "oversized")) {
            OutboundWebClientFactory factory = mock(OutboundWebClientFactory.class);
            when(factory.builder(anyString())).thenAnswer(invocation -> WebClient.builder().baseUrl(invocation.getArgument(0))
                    .exchangeFunction(request -> {
                        var response = org.springframework.web.reactive.function.client.ClientResponse
                                .create("redirect".equals(kind) ? org.springframework.http.HttpStatus.FOUND : org.springframework.http.HttpStatus.OK);
                        if ("redirect".equals(kind)) response.header("Location", "http://127.0.0.1/private").body("");
                        else if ("html".equals(kind)) response.body("<html>not PDF</html>");
                        else response.body(reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                                .wrap(new byte[16 * 1024 * 1024 + 1])));
                        return reactor.core.publisher.Mono.just(response.build());
                    }));
            ArxivClient client = new ArxivClient(factory);
            assertThrows(RuntimeException.class, () -> client.downloadExternalPdf("2309.13420"));
        }
    }

    @Test
    void externalIdRejectsUrlsOrTraversalAndPreservesLegacyVersion() {
        org.junit.jupiter.api.Assertions.assertEquals("hep-th/9901001v3", ArxivClient.requireExternalId("hep-th/9901001v3"));
        for (String value : java.util.List.of("https://arxiv.org/abs/2309.13420", "../../internal", "2309.13420?url=evil", "2309.13420v0")) {
            assertThrows(IllegalArgumentException.class, () -> ArxivClient.requireExternalId(value));
        }
    }

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
