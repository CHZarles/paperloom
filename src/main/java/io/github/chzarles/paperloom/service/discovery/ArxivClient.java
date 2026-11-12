package io.github.chzarles.paperloom.service.discovery;

import io.github.chzarles.paperloom.config.OutboundWebClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * arXiv Atom API 与 PDF 下载客户端。
 *
 * <p>网络失败、限流和内容异常都会抛出 {@link ArxivResponseException} 或
 * {@link ArxivUnavailableException}，由接口层转换成明确的错误响应。
 */
@Component
public class ArxivClient {

    private static final Logger logger = LoggerFactory.getLogger(ArxivClient.class);

    private static final int MAX_ATTEMPTS = 3;

    private final OutboundWebClientFactory webClientFactory;

    @Value("${paper.discovery.arxiv.api-base-url:https://export.arxiv.org}")
    private String apiBaseUrl;

    @Value("${paper.discovery.arxiv.pdf-base-url:https://arxiv.org}")
    private String pdfBaseUrl;

    @Value("${paper.discovery.arxiv.timeout-seconds:25}")
    private int timeoutSeconds;

    @Value("${paper.discovery.arxiv.max-pdf-bytes:52428800}")
    private long maxPdfBytes;

    public ArxivClient(OutboundWebClientFactory webClientFactory) {
        this.webClientFactory = webClientFactory;
    }

    public ArxivAtomParser.ArxivFeed search(String query, int start, int maxResults, ArxivSortOrder sortOrder) {
        String searchQuery = toApiQuery(query);
        String sortBy = sortOrder == ArxivSortOrder.SUBMITTED_DATE ? "submittedDate" : "relevance";
        String xml = fetch(
                apiBaseUrl,
                uriBuilder -> uriBuilder
                        .path("/api/query")
                        .queryParam("search_query", searchQuery)
                        .queryParam("start", Math.max(0, start))
                        .queryParam("max_results", maxResults)
                        .queryParam("sortBy", sortBy)
                        .queryParam("sortOrder", "descending")
                        .build(),
                "检索"
        );
        return ArxivAtomParser.parseFeed(xml);
    }

    public Optional<ArxivPaper> findById(String arxivId) {
        String xml = fetch(
                apiBaseUrl,
                uriBuilder -> uriBuilder
                        .path("/api/query")
                        .queryParam("id_list", arxivId)
                        .queryParam("max_results", 1)
                        .build(),
                "查询单篇"
        );
        return ArxivAtomParser.parse(xml).stream().findFirst();
    }

    public byte[] downloadPdf(String arxivId) {
        byte[] content = fetchBytes(pdfBaseUrl, "/pdf/" + arxivId);
        if (content.length == 0) {
            throw new ArxivResponseException("arXiv 返回了空 PDF: " + arxivId);
        }
        if (content.length < 5
                || content[0] != '%'
                || content[1] != 'P'
                || content[2] != 'D'
                || content[3] != 'F') {
            throw new ArxivResponseException("arXiv 返回的内容不是 PDF: " + arxivId);
        }
        return content;
    }

    /** Isolated discovery read: fixed public HTTPS origin, no library writes or configurable URL. */
    public byte[] downloadExternalPdf(String rawId) {
        String id = requireExternalId(rawId);
        try {
            byte[] body = webClientFactory.builder("https://arxiv.org")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build()
                    .get().uri("/pdf/" + id).exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.createException().flatMap(reactor.core.publisher.Mono::error);
                        }
                        return response.bodyToMono(byte[].class);
                    }).block(Duration.ofSeconds(25));
            if (body == null || body.length < 5 || body[0] != '%' || body[1] != 'P'
                    || body[2] != 'D' || body[3] != 'F') {
                throw new ArxivResponseException("arXiv 返回的内容不是 PDF: " + id);
            }
            return body;
        } catch (ArxivResponseException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isPdfTooLarge(e)) throw new ArxivPdfTooLargeException("arXiv PDF 超过允许的 16 MB");
            throw new ArxivUnavailableException("arXiv PDF 下载失败: " + rootMessage(e), e);
        }
    }

    static String requireExternalId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("(?:[0-9]{4}\\.[0-9]{4,5}|[a-z][a-z-]*(?:\\.[A-Za-z]{2})?/[0-9]{7})(?:v[1-9][0-9]*)?")) {
            throw new IllegalArgumentException("arxivId 必须是有效标识（可带版本），不是 URL");
        }
        return value;
    }

    private String fetch(String baseUrl, java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri, String operation) {
        ArxivUnavailableException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return fetchOnce(baseUrl, uri, operation);
            } catch (ArxivUnavailableException e) {
                if (!isTransient(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                last = e;
                logger.warn("arXiv {}第 {} 次失败，准备重试: {}", operation, attempt, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
        throw last == null ? new ArxivUnavailableException("arXiv " + operation + "失败", null) : last;
    }

    /**
     * export.arxiv.org 偶尔 SSL 握手失败或被代理提前断开，这类连接层错误重试就能过；超时和 HTTP 错误不重试。
     */
    private boolean isTransient(ArxivUnavailableException e) {
        Throwable current = e.getCause();
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return false;
            }
            if (current instanceof org.springframework.web.reactive.function.client.WebClientRequestException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException
                    || current instanceof java.io.IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String fetchOnce(String baseUrl, java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri, String operation) {
        try {
            String body = client(baseUrl).get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));
            if (body == null || body.isBlank()) {
                throw new ArxivResponseException("arXiv " + operation + "返回空响应");
            }
            return body;
        } catch (ArxivResponseException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw new ArxivUnavailableException(describe(operation, e), e);
        } catch (RuntimeException e) {
            throw new ArxivUnavailableException("arXiv " + operation + "失败: " + rootMessage(e), e);
        }
    }

    private byte[] fetchBytes(String baseUrl, String path) {
        try {
            byte[] body = client(baseUrl).get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(timeoutSeconds));
            return body == null ? new byte[0] : body;
        } catch (RuntimeException e) {
            if (isPdfTooLarge(e)) {
                throw new ArxivPdfTooLargeException("arXiv PDF 超过允许的 " + pdfSizeLimitText());
            }
            if (e instanceof WebClientResponseException responseException) {
                throw new ArxivUnavailableException(describe("PDF 下载", responseException), e);
            }
            throw new ArxivUnavailableException("arXiv PDF 下载失败: " + rootMessage(e), e);
        }
    }

    private boolean isPdfTooLarge(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DataBufferLimitException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    private String pdfSizeLimitText() {
        return maxPdfBytes >= 1024 * 1024
                ? (maxPdfBytes / (1024 * 1024)) + " MB"
                : maxPdfBytes + " bytes";
    }

    private WebClient client(String baseUrl) {
        return webClientFactory.builder(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize((int) Math.min(Integer.MAX_VALUE, maxPdfBytes)))
                .build();
    }

    /**
     * 用户输入自由文本时按全字段检索；已经写成 arXiv 查询语法时原样传递。
     */
    static String toApiQuery(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("检索词不能为空");
        }
        if (trimmed.contains(":")) {
            return trimmed;
        }
        return "all:" + trimmed;
    }

    private String describe(String operation, WebClientResponseException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503) {
            return "arXiv " + operation + "被限流，请稍后重试";
        }
        logger.warn("arXiv {} 返回 {}: {}", operation, e.getStatusCode(), e.getResponseBodyAsString());
        return "arXiv " + operation + "失败: HTTP " + e.getStatusCode()
                + (status == null ? "" : " " + status.getReasonPhrase());
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public enum ArxivSortOrder {
        RELEVANCE,
        SUBMITTED_DATE;

        public static ArxivSortOrder fromRequest(String value) {
            if (value == null || value.isBlank() || "relevance".equalsIgnoreCase(value)) {
                return RELEVANCE;
            }
            if ("submitted".equalsIgnoreCase(value) || "submittedDate".equalsIgnoreCase(value)) {
                return SUBMITTED_DATE;
            }
            throw new IllegalArgumentException("不支持的排序方式: " + value);
        }
    }
}
