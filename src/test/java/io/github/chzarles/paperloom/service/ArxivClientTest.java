package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import io.github.chzarles.paperloom.config.OutboundWebClientFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArxivClientTest {

    @Test
    void parsesVersionedAtomEntriesAndBoundsAbstractPreview() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2401.01234v2</id>
                    <title> A   Paper </title>
                    <summary> first   sentence </summary>
                    <published>2024-01-01T00:00:00Z</published>
                    <updated>2024-02-01T00:00:00Z</updated>
                    <author><name>Ada</name></author>
                    <link title="pdf" href="https://arxiv.org/pdf/2401.01234v2"/>
                  </entry>
                </feed>
                """;

        List<ArxivClient.Paper> papers = ArxivClient.parseFeed(xml, 10);

        assertEquals(1, papers.size());
        assertEquals("2401.01234v2", papers.get(0).arxivId());
        assertEquals("A Paper", papers.get(0).title());
        assertEquals(List.of("Ada"), papers.get(0).authors());
        assertEquals("first sentence", papers.get(0).abstractPreview());
        assertEquals("2401.01234v2", ArxivClient.normalizeVersionedId("arXiv:2401.01234v2"));
        assertNull(ArxivClient.normalizeVersionedId("not-an-arxiv-id"));
    }

    @Test
    void fetchesAndParsesAnAtomFeedThroughTheConfiguredWebClient() throws Exception {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>https://arxiv.org/abs/2401.01234v2</id>
                    <title>Transformer Search</title>
                    <summary>Bounded result.</summary>
                    <published>2024-01-01T00:00:00Z</published>
                    <updated>2024-02-01T00:00:00Z</updated>
                    <author><name>Ada</name></author>
                    <link title="pdf" href="https://arxiv.org/pdf/2401.01234v2"/>
                  </entry>
                </feed>
        """;
        AtomicReference<String> requestQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/query", exchange -> {
            requestQuery.set(exchange.getRequestURI().getQuery());
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/query";
            ArxivClient client = new ArxivClient(new OutboundWebClientFactory(), baseUrl, 2);
            ArxivClient.SearchResult result = client.search(new ArxivClient.SearchRequest(
                    "transformer", List.of(), List.of(), null, null));

            assertEquals(1, result.returnedCount());
            assertEquals("2401.01234v2", result.papers().get(0).arxivId());
            assertEquals("bounded", result.coverage());
            assertEquals("all:transformer", requestQuery.get()
                    .replace("search_query=", "").split("&", 2)[0]);
        } finally {
            server.stop(0);
        }
    }
}
