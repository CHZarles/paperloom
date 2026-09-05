package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.OutboundWebClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ArxivClient {

    public static final int MAX_RESULTS = 10;
    public static final int MAX_QUERY_CHARS = 200;
    public static final int MAX_ABSTRACT_CHARS = 1_000;

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";
    private static final Pattern TERM = Pattern.compile("[^\\p{L}\\p{N}._-]+");
    private static final Pattern CATEGORY = Pattern.compile("[a-zA-Z]{2}(?:\\.[a-zA-Z]{2,4})(?:\\.[a-zA-Z]{2,4})?");
    private static final Pattern VERSIONED_ID = Pattern.compile(
            "(?:\\d{4}\\.\\d{4,5}|[a-zA-Z-]+(?:\\.[a-zA-Z-]+)?/\\d{7})(?:v\\d+)?");

    private final WebClient client;
    private final String baseUrl;
    private final Duration timeout;

    public ArxivClient(OutboundWebClientFactory factory,
                       @Value("${arxiv.api.base-url:https://export.arxiv.org/api/query}") String baseUrl,
                       @Value("${arxiv.api.timeout-seconds:10}") long timeoutSeconds) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.client = factory.builder(this.baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1_048_576))
                .build();
    }

    public SearchResult search(SearchRequest request) {
        String query = buildQuery(request);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("search_query", query)
                .queryParam("start", 0)
                .queryParam("max_results", MAX_RESULTS)
                .queryParam("sortBy", "relevance")
                .queryParam("sortOrder", "descending")
                .build()
                .encode()
                .toUri();
        String body;
        try {
            body = client.get()
                    .uri(uri)
                    .header("User-Agent", "PaperLoom/0.1 (+https://github.com/CHZarles/paperloom)")
                    .accept(MediaType.APPLICATION_XML)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(timeout);
        } catch (Exception error) {
            throw new ArxivClientException("arXiv is temporarily unavailable", error);
        }
        try {
            List<Paper> papers = parseFeed(body, MAX_RESULTS);
            return new SearchResult(papers, papers.size(), "bounded");
        } catch (Exception error) {
            throw new ArxivClientException("arXiv returned an invalid response", error);
        }
    }

    static List<Paper> parseFeed(String xml, int limit) throws Exception {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("empty arXiv response");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        NodeList entries = document.getElementsByTagNameNS(ATOM_NS, "entry");
        List<Paper> papers = new ArrayList<>();
        int max = Math.min(MAX_RESULTS, Math.max(0, limit));
        for (int index = 0; index < entries.getLength() && papers.size() < max; index++) {
            Element entry = (Element) entries.item(index);
            String arxivId = normalizeVersionedId(text(entry, "id"));
            if (arxivId == null) {
                continue;
            }
            List<String> authors = new ArrayList<>();
            NodeList authorNodes = entry.getElementsByTagNameNS(ATOM_NS, "author");
            for (int authorIndex = 0; authorIndex < authorNodes.getLength(); authorIndex++) {
                if (authors.size() >= 20) {
                    break;
                }
                String author = text((Element) authorNodes.item(authorIndex), "name");
                if (!author.isBlank()) {
                    authors.add(author);
                }
            }
            String pdfUrl = null;
            NodeList links = entry.getElementsByTagNameNS(ATOM_NS, "link");
            for (int linkIndex = 0; linkIndex < links.getLength(); linkIndex++) {
                Element link = (Element) links.item(linkIndex);
                String title = link.getAttribute("title");
                String type = link.getAttribute("type");
                if ("pdf".equalsIgnoreCase(title) || "application/pdf".equalsIgnoreCase(type)) {
                    pdfUrl = link.getAttribute("href");
                    break;
                }
            }
            if (pdfUrl == null || pdfUrl.isBlank()) {
                pdfUrl = "https://arxiv.org/pdf/" + arxivId;
            }
            papers.add(new Paper(
                    arxivId,
                    compact(text(entry, "title"), MAX_QUERY_CHARS),
                    List.copyOf(authors),
                    text(entry, "published"),
                    text(entry, "updated"),
                    compact(text(entry, "summary"), MAX_ABSTRACT_CHARS),
                    pdfUrl
            ));
        }
        return List.copyOf(papers);
    }

    static String normalizeVersionedId(String raw) {
        String value = raw == null ? "" : raw.trim();
        int absIndex = value.toLowerCase(Locale.ROOT).lastIndexOf("/abs/");
        if (absIndex >= 0) {
            value = value.substring(absIndex + 5);
        } else if (value.toLowerCase(Locale.ROOT).startsWith("arxiv:")) {
            value = value.substring(6);
        }
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        return VERSIONED_ID.matcher(value).matches() ? value : null;
    }

    private String buildQuery(SearchRequest request) {
        List<String> clauses = new ArrayList<>();
        String queryText = request.queryText() == null ? "" : request.queryText().trim();
        if (queryText.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("query_text is too long");
        }
        for (String term : TERM.split(queryText)) {
            if (!term.isBlank()) {
                clauses.add("all:" + term);
            }
        }
        for (String author : request.authors()) {
            String normalized = compact(author, 100).replaceAll("[^\\p{L}\\p{N} .'-]", "").trim();
            if (!normalized.isBlank()) {
                clauses.add("au:\"" + normalized.replace("\"", "") + "\"");
            }
        }
        for (String category : request.categories()) {
            String normalized = category.trim();
            if (!CATEGORY.matcher(normalized).matches()) {
                throw new IllegalArgumentException("category is invalid");
            }
            clauses.add("cat:" + normalized);
        }
        if (request.yearFrom() != null || request.yearTo() != null) {
            int from = request.yearFrom() == null ? 1900 : request.yearFrom();
            int to = request.yearTo() == null ? 2100 : request.yearTo();
            if (from > to) {
                throw new IllegalArgumentException("year_from must not exceed year_to");
            }
            clauses.add("submittedDate:[" + from + "01010000 TO " + to + "12312359]");
        }
        if (clauses.isEmpty()) {
            throw new IllegalArgumentException("at least one arXiv search condition is required");
        }
        return String.join(" AND ", clauses);
    }

    private static String text(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagNameNS(ATOM_NS, name);
        if (nodes.getLength() == 0) {
            return "";
        }
        Node node = nodes.item(0);
        return node.getTextContent() == null ? "" : node.getTextContent().trim();
    }

    private static String compact(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars).trim();
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank()
                ? "https://export.arxiv.org/api/query"
                : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record SearchRequest(String queryText,
                                List<String> authors,
                                List<String> categories,
                                Integer yearFrom,
                                Integer yearTo) {
        public SearchRequest {
            authors = authors == null ? List.of() : List.copyOf(authors);
            categories = categories == null ? List.of() : List.copyOf(categories);
        }
    }

    public record SearchResult(List<Paper> papers, int returnedCount, String coverage) {
    }

    public record Paper(String arxivId,
                        String title,
                        List<String> authors,
                        String published,
                        String updated,
                        String abstractPreview,
                        String pdfUrl) {
    }
}
