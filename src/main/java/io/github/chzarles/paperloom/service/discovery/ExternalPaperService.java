package io.github.chzarles.paperloom.service.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.config.OutboundWebClientFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.web.util.UriBuilder;
import java.net.URI;

/** Public-source acquisition only: no library, ingestion, Reading Model or arbitrary URL access. */
@Service
public class ExternalPaperService {
    private static final String CROSSREF = "https://api.crossref.org";
    private static final String EUROPE_PMC = "https://www.ebi.ac.uk";
    private static final String REST = "/europepmc/webservices/rest";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EXTRACTED_CHARS = 2_000_000;
    private final ArxivClient arxiv;
    private final OutboundWebClientFactory clients;
    private final ObjectMapper mapper;

    public ExternalPaperService(ArxivClient arxiv, OutboundWebClientFactory clients, ObjectMapper mapper) {
        this.arxiv = arxiv;
        this.clients = clients;
        this.mapper = mapper;
    }

    public Map<String, Object> search(String query, Integer requestedMax) {
        if (query == null || query.isBlank() || query.length() > 1000) {
            throw new IllegalArgumentException("query 必须为 1..1000 字符");
        }
        int max = requestedMax == null ? 10 : requestedMax;
        if (max < 1 || max > 20) throw new IllegalArgumentException("max 必须为 1..20");
        try {
            byte[] bytes = get(CROSSREF, b -> b.path("/works").queryParam("query.bibliographic", "{query}")
                    .queryParam("rows", max).build(query.trim()), "application/json");
            JsonNode message = mapper.readTree(bytes).path("message");
            if (!message.path("items").isArray()) throw new IllegalStateException("invalid Crossref response");
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode item : message.path("items")) {
                if (items.size() == max) break;
                String doi = item.path("DOI").asText("");
                if (doi.isBlank() || doi.length() > 300) continue;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("doi", doi);
                result.put("title", first(item.path("title")));
                result.put("venue", first(item.path("container-title")));
                result.put("authors", authors(item.path("author")));
                JsonNode date = item.path("published").path("date-parts").path(0).path(0);
                if (date.isInt()) result.put("year", date.asInt());
                result.put("sourceUrl", CROSSREF + "/works/" + encodePath(doi));
                items.add(result);
            }
            return Map.of("status", "ok", "source", "crossref", "basis", "metadata_only", "evidence", false,
                    "items", items, "total", message.path("total-results").asLong(items.size()));
        } catch (RuntimeException e) {
            return Map.of("status", "failed", "source", "crossref", "basis", "metadata_only", "evidence", false,
                    "reason", "metadata_upstream_failed", "items", List.of());
        } catch (Exception e) {
            return Map.of("status", "failed", "source", "crossref", "basis", "metadata_only", "evidence", false,
                    "reason", "invalid_metadata_response", "items", List.of());
        }
    }

    public Map<String, Object> read(String arxivId, String rawDoi, Integer requestedOffset, Integer requestedMaxChars) {
        boolean hasArxiv = arxivId != null && !arxivId.isBlank();
        boolean hasDoi = rawDoi != null && !rawDoi.isBlank();
        if (hasArxiv == hasDoi) throw new IllegalArgumentException("arxivId 与 doi 必须且只能提供一个");
        int offset = requestedOffset == null ? 0 : requestedOffset;
        int maxChars = requestedMaxChars == null ? 12000 : requestedMaxChars;
        if (offset < 0 || offset > MAX_EXTRACTED_CHARS || maxChars < 1 || maxChars > 20000) {
            throw new IllegalArgumentException("offset 必须为 0..2000000，maxChars 必须为 1..20000");
        }
        String id = hasArxiv ? ArxivClient.requireExternalId(arxivId) : normalizeDoi(rawDoi);
        String source = hasArxiv ? "arxiv_pdf" : "europe_pmc_xml";
        String url = hasArxiv ? "https://arxiv.org/pdf/" + id : EUROPE_PMC + REST + "/search";
        try {
            Extracted extracted = hasArxiv ? readPdf(id) : readDoi(id);
            if (extracted.parts().isEmpty()) return unavailable(source, url, id, hasArxiv, extracted.reason());
            return slice(extracted, source, id, hasArxiv, offset, maxChars);
        } catch (Exception e) {
            return unavailable(source, url, id, hasArxiv, "external_fulltext_failed");
        }
    }

    private Extracted readPdf(String id) throws Exception {
        byte[] pdf = arxiv.downloadExternalPdf(id);
        List<Part> parts = new ArrayList<>();
        int retained = 0;
        boolean truncated = false;
        try (PDDocument document = PDDocument.load(pdf)) {
            if (document.isEncrypted()) throw new IllegalStateException("encrypted_pdf");
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                if (retained >= MAX_EXTRACTED_CHARS || page > 500) { truncated = true; break; }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                int size = Math.min(text.length(), MAX_EXTRACTED_CHARS - retained);
                if (size < text.length()) truncated = true;
                if (size > 0) parts.add(new Part(text.substring(0, size), page, null));
                retained += size;
            }
        }
        long readable = parts.stream().map(Part::text).flatMapToInt(String::chars).filter(Character::isLetterOrDigit).count();
        if (readable < 80) return new Extracted(List.of(), true, "pdf_text_unavailable_or_scanned", "https://arxiv.org/pdf/" + id, null);
        return new Extracted(parts, truncated, truncated ? "extraction_limit" : null, "https://arxiv.org/pdf/" + id, null);
    }

    private Extracted readDoi(String doi) throws Exception {
        byte[] search = get(EUROPE_PMC, b -> b.path(REST + "/search")
                .queryParam("query", "{query}").queryParam("format", "json").queryParam("pageSize", 100)
                .build("DOI:\"" + doi + "\""), "application/json");
        JsonNode response = mapper.readTree(search);
        JsonNode results = response.path("resultList").path("result");
        if (!results.isArray()) throw new IllegalStateException("invalid Europe PMC search response");
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode result : results) {
            if (doi.equalsIgnoreCase(result.path("doi").asText(""))) matches.add(result);
        }
        if (response.path("hitCount").asInt(results.size()) > 100 || matches.size() != 1) {
            return new Extracted(List.of(), true, matches.isEmpty() ? "doi_fulltext_not_found" : "doi_identity_ambiguous",
                    EUROPE_PMC + REST + "/search", null);
        }
        JsonNode match = matches.get(0);
        String pmcid = match.path("pmcid").asText("");
        if (!"Y".equals(match.path("isOpenAccess").asText()) || !pmcid.matches("PMC[0-9]+")) {
            return new Extracted(List.of(), true, "open_access_fulltext_unavailable", EUROPE_PMC + REST + "/search", null);
        }
        String url = EUROPE_PMC + REST + "/" + pmcid + "/fullTextXML";
        byte[] xml = get(EUROPE_PMC, b -> b.path(REST + "/" + pmcid + "/fullTextXML").build(), "application/xml");
        return parseXml(xml, doi, pmcid, url);
    }

    private Extracted parseXml(byte[] xml, String doi, String pmcid, String url) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Element article = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
        if (!"article".equals(article.getTagName())) throw new IllegalStateException("not article XML");
        // Verify the fulltext's identity too; a search result is not authority for another document.
        var ids = article.getElementsByTagName("article-id");
        boolean identity = false;
        for (int i = 0; i < ids.getLength(); i++) {
            Element element = (Element) ids.item(i);
            if ("doi".equals(element.getAttribute("pub-id-type")) && doi.equalsIgnoreCase(element.getTextContent().trim())) identity = true;
        }
        if (!identity) throw new IllegalStateException("fulltext DOI mismatch");
        var bodies = article.getElementsByTagName("body");
        if (bodies.getLength() != 1) return new Extracted(List.of(), true, "xml_body_unavailable", url, pmcid);
        List<Part> parts = new ArrayList<>();
        boolean truncated = collectXml(bodies.item(0), "Body", parts, new int[]{0});
        return new Extracted(parts, truncated, truncated ? "extraction_limit" : null, url, pmcid);
    }

    private boolean collectXml(Node node, String section, List<Part> parts, int[] retained) {
        if (retained[0] >= MAX_EXTRACTED_CHARS) return true;
        if (node instanceof Element element && "sec".equals(element.getTagName())) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element title && "title".equals(title.getTagName())) { section = bounded(title.getTextContent().trim(), 300); break; }
            }
        }
        if (node instanceof Element element && List.of("p", "title", "table-wrap", "fig", "disp-formula", "list").contains(element.getTagName())) {
            String text = element.getTextContent().trim();
            int size = Math.min(text.length(), MAX_EXTRACTED_CHARS - retained[0]);
            if (size > 0) { parts.add(new Part(text.substring(0, size), null, section)); retained[0] += size; }
            return size < text.length();
        }
        boolean truncated = false;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) truncated |= collectXml(child, section, parts, retained);
        return truncated;
    }

    private Map<String, Object> slice(Extracted document, String source, String id, boolean isArxiv, int offset, int maxChars) {
        List<Map<String, Object>> items = new ArrayList<>();
        int position = 0;
        int remaining = maxChars;
        int next = offset;
        for (Part part : document.parts()) {
            int end = position + part.text().length();
            if (end > offset && remaining > 0) {
                int start = Math.max(0, offset - position);
                int size = Math.min(remaining, part.text().length() - start);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("text", part.text().substring(start, start + size));
                item.put("offset", position + start);
                if (part.page() != null) item.put("page", part.page());
                if (part.section() != null) item.put("section", part.section());
                items.add(item);
                remaining -= size;
                next = position + start + size;
            }
            position = end;
        }
        if (offset >= position) return unavailable(source, document.url(), id, isArxiv, "offset_out_of_range");
        boolean more = next < position;
        Map<String, Object> response = base(source, document.url(), id, isArxiv);
        response.put("status", more || document.truncated() ? "partial" : "complete");
        response.put("incomplete", more || document.truncated());
        response.put("basis", "external_fulltext");
        response.put("items", items);
        response.put("nextOffset", more ? next : null);
        if (document.pmcid() != null) response.put("pmcid", document.pmcid());
        if (document.reason() != null) response.put("reason", document.reason());
        response.put("extraction", isArxiv ? "pdf_text_only_no_ocr" : "xml_body_text");
        response.put("limitations", isArxiv
                ? "Text extraction only, without OCR. Figures, tables and equations may be missing. Complete means extracted text delivered, not complete visual content."
                : "Article body text only. Complete means extracted body text delivered, not full visual or supplementary content.");
        return response;
    }

    private Map<String, Object> unavailable(String source, String url, String id, boolean isArxiv, String reason) {
        Map<String, Object> response = base(source, url, id, isArxiv);
        response.put("status", "unavailable");
        response.put("basis", "unavailable");
        response.put("incomplete", true);
        response.put("reason", reason == null ? "fulltext_unavailable" : reason);
        response.put("items", List.of());
        response.put("nextOffset", null);
        return response;
    }

    private Map<String, Object> base(String source, String url, String id, boolean isArxiv) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("sourceUrl", url);
        result.put(isArxiv ? "arxivId" : "doi", id);
        return result;
    }

    private byte[] get(String base, Function<UriBuilder, URI> uri, String accept) {
        // Constant HTTPS bases only. WebClient/Reactor defaults do not follow redirects.
        return clients.builder(base).codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES)).build()
                .get().uri(uri).header("Accept", accept).exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) return response.createException().flatMap(reactor.core.publisher.Mono::error);
                    return response.bodyToMono(byte[].class);
                }).block(Duration.ofSeconds(25));
    }

    private String normalizeDoi(String raw) {
        String doi = raw.trim().toLowerCase(Locale.ROOT);
        if (doi.startsWith("https://doi.org/")) doi = doi.substring("https://doi.org/".length());
        if (doi.length() > 300 || doi.indexOf('"') >= 0 || doi.indexOf('\\') >= 0
                || !doi.matches("10\\.[0-9]{4,9}/[^\\s\\p{Cntrl}]+")) throw new IllegalArgumentException("doi 格式无效");
        return doi;
    }

    private String bounded(String text, int max) { return text.substring(0, Math.min(text.length(), max)); }
    private String first(JsonNode node) { return node.isArray() && !node.isEmpty() ? bounded(node.path(0).asText(""), 1000) : ""; }
    private List<String> authors(JsonNode nodes) {
        List<String> authors = new ArrayList<>();
        if (nodes.isArray()) for (JsonNode node : nodes) {
            if (authors.size() == 20) break;
            authors.add(bounded((node.path("given").asText("") + " " + node.path("family").asText("")).trim(), 200));
        }
        return authors;
    }
    private String encodePath(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private record Part(String text, Integer page, String section) {}
    private record Extracted(List<Part> parts, boolean truncated, String reason, String url, String pmcid) {}
}
