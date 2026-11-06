package io.github.chzarles.paperloom.service.discovery;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 解析 arXiv Atom API 响应。
 *
 * <p>只读取检索需要的元数据字段，遇到无法解析的条目时保留其余条目；XML 结构错误会让整次检索
 * 明确失败，不返回空结果冒充成功。
 */
public final class ArxivAtomParser {

    private ArxivAtomParser() {
    }

    public static List<ArxivPaper> parse(String xml) {
        return parseFeed(xml).papers();
    }

    /**
     * 同时读取条目和 opensearch:totalResults，分页界面需要知道总命中数。
     *
     * <p>arXiv 不返回 totalResults 时记为 -1，调用方不得把它当成 0 条命中。
     */
    public static ArxivFeed parseFeed(String xml) {
        Document document = parseDocument(xml);
        List<ArxivPaper> papers = new ArrayList<>();
        for (Element entry : childElements(document.getDocumentElement(), "entry")) {
            toPaper(entry).ifPresent(papers::add);
        }
        return new ArxivFeed(List.copyOf(papers), totalResults(document));
    }

    private static int totalResults(Document document) {
        for (Element child : childElements(document.getDocumentElement(), "totalResults")) {
            String value = child.getTextContent();
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    public record ArxivFeed(List<ArxivPaper> papers, int totalResults) {
    }

    private static Optional<ArxivPaper> toPaper(Element entry) {
        String rawId = text(entry, "id");
        Optional<String> arxivId = ArxivIdentifier.normalize(rawId);
        if (arxivId.isEmpty()) {
            return Optional.empty();
        }
        String id = arxivId.get();
        String title = collapse(text(entry, "title"));
        if (title.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ArxivPaper(
                id,
                title,
                collapse(text(entry, "summary")),
                authors(entry),
                categories(entry),
                publishedYear(text(entry, "published")),
                absUrl(rawId, id),
                pdfUrl(entry, id)
        ));
    }

    private static List<String> authors(Element entry) {
        List<String> authors = new ArrayList<>();
        for (Element author : childElements(entry, "author")) {
            String name = collapse(text(author, "name"));
            if (!name.isEmpty()) {
                authors.add(name);
            }
        }
        return authors;
    }

    private static List<String> categories(Element entry) {
        Set<String> categories = new LinkedHashSet<>();
        for (Element child : childElements(entry, null)) {
            if ("primary_category".equals(localName(child))) {
                String term = child.getAttribute("term");
                if (!term.isBlank()) {
                    categories.add(term.trim());
                }
            }
        }
        for (Element category : childElements(entry, "category")) {
            String term = category.getAttribute("term");
            if (!term.isBlank()) {
                categories.add(term.trim());
            }
        }
        return List.copyOf(categories);
    }

    private static String absUrl(String rawId, String fallbackId) {
        String trimmed = rawId == null ? "" : rawId.trim();
        if (trimmed.startsWith("http")) {
            int versionIndex = trimmed.lastIndexOf('v');
            return versionIndex > trimmed.lastIndexOf('/') ? trimmed.substring(0, versionIndex) : trimmed;
        }
        return "https://arxiv.org/abs/" + fallbackId;
    }

    private static String pdfUrl(Element entry, String arxivId) {
        for (Element link : childElements(entry, "link")) {
            String title = link.getAttribute("title");
            String type = link.getAttribute("type");
            if ("pdf".equalsIgnoreCase(title) || "application/pdf".equalsIgnoreCase(type)) {
                String href = link.getAttribute("href");
                if (!href.isBlank()) {
                    return href.trim();
                }
            }
        }
        return "https://arxiv.org/pdf/" + arxivId;
    }

    private static Integer publishedYear(String published) {
        if (published == null || published.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(published.trim()).getYear();
        } catch (RuntimeException ignored) {
            String trimmed = published.trim();
            if (trimmed.length() >= 4) {
                try {
                    return Integer.valueOf(trimmed.substring(0, 4));
                } catch (NumberFormatException ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
    }

    private static Document parseDocument(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new ArxivResponseException("arXiv 返回了空响应");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new ArxivResponseException("arXiv 响应不是可解析的 Atom XML: " + e.getMessage(), e);
        }
    }

    private static List<Element> childElements(Element parent, String localNameOrNull) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            if (localNameOrNull == null || localNameOrNull.equals(localName(element))) {
                result.add(element);
            }
        }
        return result;
    }

    private static String localName(Element element) {
        String name = element.getLocalName();
        return name != null ? name : element.getTagName();
    }

    private static String text(Element parent, String localName) {
        if (parent == null) {
            return "";
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName((Element) node))) {
                return node.getTextContent() == null ? "" : node.getTextContent();
            }
        }
        return "";
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
