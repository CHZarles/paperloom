package io.github.chzarles.paperloom.service.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.config.OutboundWebClientFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExternalPaperServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ClientRequest> requests = new ArrayList<>();
    private final ArxivClient arxiv = mock(ArxivClient.class);

    private ExternalPaperService service(Function<ClientRequest, ClientResponse> response) {
        OutboundWebClientFactory factory = mock(OutboundWebClientFactory.class);
        when(factory.builder(anyString())).thenAnswer(invocation -> WebClient.builder().baseUrl(invocation.getArgument(0))
                .exchangeFunction(request -> {
                    requests.add(request);
                    assertEquals("https", request.url().getScheme());
                    assertTrue(List.of("api.crossref.org", "www.ebi.ac.uk").contains(request.url().getHost()));
                    assertNull(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
                    return Mono.just(response.apply(request));
                }));
        return new ExternalPaperService(arxiv, factory, mapper);
    }
    private ClientResponse response(int code, String body) {
        return ClientResponse.create(HttpStatus.valueOf(code)).header("Content-Type", "application/json")
                .body(body).build();
    }
    private String searchBody(String results, int count) {
        return "{\"hitCount\":" + count + ",\"resultList\":{\"result\":" + results + "}}";
    }
    private String xml(String content) {
        return "<article><front><article-meta><article-id pub-id-type='doi'>10.1000/test</article-id></article-meta></front>"
                + "<body><sec><title>Methods</title><p>" + content + "</p></sec></body></article>";
    }
    private ExternalPaperService oa(String xml) {
        return service(request -> request.url().getPath().endsWith("/search")
                ? response(200, searchBody("[{\"doi\":\"10.1000/test\",\"isOpenAccess\":\"Y\",\"pmcid\":\"PMC123\"}]", 1))
                : response(200, xml));
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> result) { return (List<Map<String, Object>>) result.get("items"); }

    @Test
    void crossrefIsBibliographicMetadataNotEvidenceOrFullText() {
        var result = service(request -> response(200, """
                {"message":{"total-results":1,"items":[{"DOI":"10.1000/test","title":["A paper"],
                "container-title":["Journal"],"published":{"date-parts":[[2025,1]]},"author":[{"given":"A","family":"Ding"}]}]}}
                """)).search("Ding density clustering", 5);
        assertEquals("metadata_only", result.get("basis"));
        assertEquals(false, result.get("evidence"));
        assertEquals("10.1000/test", items(result).get(0).get("doi"));
        assertFalse(items(result).get(0).containsKey("text"));
        assertTrue(requests.get(0).url().getRawQuery().contains("query.bibliographic="));
        assertTrue(requests.get(0).url().getRawQuery().contains("rows=5"));
        verifyNoInteractions(arxiv);
    }

    @Test
    void metadataUpstreamFailureIsNotSuccessfulEmptySearch() {
        var result = service(request -> response(503, "unavailable")).search("density", 2);
        assertEquals("failed", result.get("status"));
        assertEquals("metadata_upstream_failed", result.get("reason"));
    }

    @Test
    void arxivPdfReadingIsIndependentAndPreservesVersionAndActualPages() throws Exception {
        when(arxiv.downloadExternalPdf("2309.13420v2")).thenReturn(pdf(
                "First page retains readable source sentences about density peak clustering methods and neighbors.",
                "Second page retains independent source sentences about evaluation datasets and clustering noise."));
        var service = service(request -> { throw new AssertionError("no Crossref or library call"); });
        var first = service.read("2309.13420v2", null, 0, 100);
        assertEquals("partial", first.get("status"));
        assertEquals("https://arxiv.org/pdf/2309.13420v2", first.get("sourceUrl"));
        assertEquals(1, items(first).get(0).get("page"));
        assertEquals(100, first.get("nextOffset"));
        var rest = service.read("2309.13420v2", null, 100, 20000);
        assertEquals("complete", rest.get("status"));
        assertNull(rest.get("nextOffset"));
        assertEquals(100, items(rest).get(0).get("offset"));
        assertEquals(2, items(rest).get(0).get("page"));
        assertEquals("pdf_text_only_no_ocr", rest.get("extraction"));
        assertEquals("external_fulltext", rest.get("basis"));
        assertEquals(0, requests.size());
    }

    @Test
    void scannedOrEmptyPdfDoesNotBecomeFulltextEvidence() throws Exception {
        when(arxiv.downloadExternalPdf("2309.13420")).thenReturn(pdf(""));
        var result = service(request -> response(200, "{}")).read("2309.13420", null, 0, 100);
        assertEquals("unavailable", result.get("status"));
        assertEquals("pdf_text_unavailable_or_scanned", result.get("reason"));
        assertEquals("unavailable", result.get("basis"));
        assertTrue(items(result).isEmpty());
    }

    @Test
    void invalidPdfAndDownloadFailureAreExplicitlyUnavailable() {
        when(arxiv.downloadExternalPdf("2309.13420")).thenReturn("not a pdf".getBytes(StandardCharsets.UTF_8));
        var service = service(request -> response(200, "{}"));
        assertEquals("unavailable", service.read("2309.13420", null, 0, 100).get("status"));
        when(arxiv.downloadExternalPdf("2309.13420")).thenThrow(new ArxivUnavailableException("404", null));
        assertEquals("external_fulltext_failed", service.read("2309.13420", null, 0, 100).get("reason"));
    }

    @Test
    void doiRequiresUniqueExactOaMatchThenReadsXmlBodyAndRealSection() {
        var result = oa(xml("Density method uses neighbor information.")).read(null, "https://doi.org/10.1000/TEST", 0, 20000);
        assertEquals("complete", result.get("status"));
        assertEquals("PMC123", result.get("pmcid"));
        assertEquals("Methods", items(result).get(1).get("section"));
        assertEquals("Density method uses neighbor information.", items(result).get(1).get("text"));
        assertEquals("https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML", result.get("sourceUrl"));
        assertEquals(2, requests.size());
        assertFalse(items(result).get(1).containsKey("page"));
    }

    @Test
    void xmlPaginationUsesSourceCharacterOffsetsAndReportsPartial() {
        var service = oa(xml("x".repeat(200)));
        var first = service.read(null, "10.1000/test", 0, 20);
        assertEquals("partial", first.get("status"));
        assertEquals(20, first.get("nextOffset"));
        var second = service.read(null, "10.1000/test", 20, 20);
        assertEquals(20, items(second).get(0).get("offset"));
        assertEquals(40, second.get("nextOffset"));
    }

    @Test
    void missingAmbiguousOrNonOaDoiNeverDownloadsFulltext() {
        for (String results : List.of("[]", "[{\"doi\":\"10.1000/other\",\"isOpenAccess\":\"Y\",\"pmcid\":\"PMC1\"}]",
                "[{\"doi\":\"10.1000/test\",\"isOpenAccess\":\"N\",\"pmcid\":\"PMC1\"}]",
                "[{\"doi\":\"10.1000/test\"},{\"doi\":\"10.1000/test\"}]")) {
            int before = requests.size();
            var result = service(request -> response(200, searchBody(results, 2))).read(null, "10.1000/test", 0, 100);
            assertEquals("unavailable", result.get("status"));
            assertEquals(before + 1, requests.size());
        }
    }

    @Test
    void xmlExternalEntitiesAndMismatchedDoiAreRejected() {
        for (String xml : List.of("<!DOCTYPE article [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" + xml("&xxe;"),
                xml("source").replace("10.1000/test", "10.1000/other"))) {
            var result = oa(xml).read(null, "10.1000/test", 0, 100);
            assertEquals("unavailable", result.get("status"));
            assertTrue(items(result).isEmpty());
        }
    }

    @Test
    void upstreamRedirectIsNotFollowedAndFulltextFailureIsExplicit() {
        var result = service(request -> ClientResponse.create(HttpStatus.FOUND)
                .header("Location", "http://127.0.0.1/private").body("").build()).read(null, "10.1000/test", 0, 100);
        assertEquals("unavailable", result.get("status"));
        assertEquals(1, requests.size());
    }

    @Test
    void inputLimitsRejectArbitraryUrlsAndNeverTouchNetwork() {
        var service = service(request -> { throw new AssertionError(); });
        assertThrows(IllegalArgumentException.class, () -> service.read("http://internal/pdf", null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.read(null, "http://internal/doi", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.read(null, "10.1000/test\" OR *", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.read(null, "10.1000/test\\escape", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.read("2309.13420", "10.1000/test", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.read("2309.13420", null, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.read("2309.13420", null, 0, 20001));
        assertThrows(IllegalArgumentException.class, () -> service.search("x", 21));
        assertThrows(IllegalArgumentException.class, () -> service.search("x".repeat(1001), 1));
        assertEquals(0, requests.size());
    }

    @Test
    void httpResponseSizeIsBounded() {
        var result = service(request -> ClientResponse.create(HttpStatus.OK)
                .body(Flux.just(new DefaultDataBufferFactory().wrap(new byte[8 * 1024 * 1024 + 1]))).build())
                .search("density", 1);
        assertEquals("failed", result.get("status"));
    }

    private byte[] pdf(String... pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 8); content.newLineAtOffset(20, 700);
                    content.showText(text); content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
