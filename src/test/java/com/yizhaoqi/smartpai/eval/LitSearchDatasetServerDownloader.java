package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LitSearchDatasetServerDownloader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LitSearchDatasetServerDownloader() {
    }

    public static void main(String[] args) throws Exception {
        DownloadSummary summary = download(Options.parse(args), httpFetcher());
        System.out.printf(
                "downloaded=%d skipped=%d totalPages=%d%n",
                summary.downloaded(),
                summary.skipped(),
                summary.totalPages()
        );
    }

    public static DownloadSummary download(Options options, PageFetcher fetcher) throws Exception {
        return download(options, fetcher, Thread::sleep);
    }

    public static DownloadSummary download(Options options, PageFetcher fetcher, Sleeper sleeper) throws Exception {
        Files.createDirectories(options.outputDir());
        int downloaded = 0;
        int skipped = 0;
        int totalPages = 0;
        int endExclusive = options.startOffset() + options.totalRows();
        for (int offset = options.startOffset(); offset < endExclusive; offset += options.pageSize()) {
            totalPages++;
            Path output = options.outputDir().resolve(String.format(java.util.Locale.ROOT, options.filenameFormat(), offset));
            int expectedRows = Math.min(options.pageSize(), endExclusive - offset);
            if (!options.overwrite() && existingPageMatches(output, expectedRows, offset)) {
                skipped++;
                continue;
            }
            String url = rowsUrl(options, offset, options.pageSize());
            String body = fetchWithRetries(fetcher, url, offset, options.pageSize(), options.retries(), options.retryDelayMillis());
            Files.writeString(output, body);
            downloaded++;
            if (options.pageDelayMillis() > 0 && offset + options.pageSize() < endExclusive) {
                sleeper.sleep(options.pageDelayMillis());
            }
        }
        return new DownloadSummary(downloaded, skipped, totalPages);
    }

    private static boolean existingPageMatches(Path output, int expectedRows, int offset) throws Exception {
        if (!Files.exists(output) || Files.size(output) == 0) {
            return false;
        }
        JsonNode root = OBJECT_MAPPER.readTree(output.toFile());
        JsonNode rows = root.path("rows");
        return rows.isArray() && rows.size() == expectedRows && rowIndexesMatch(rows, offset);
    }

    private static boolean rowIndexesMatch(JsonNode rows, int offset) {
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            if (!row.has("row_idx")) {
                return false;
            }
            if (row.path("row_idx").asInt(Integer.MIN_VALUE) != offset + index) {
                return false;
            }
        }
        return true;
    }

    private static String fetchWithRetries(PageFetcher fetcher,
                                           String url,
                                           int offset,
                                           int length,
                                           int retries,
                                           long retryDelayMillis) throws Exception {
        int maxAttempts = Math.max(retries, 1);
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetcher.fetch(url, offset, length);
            } catch (Exception failure) {
                lastFailure = failure;
                if (attempt == maxAttempts) {
                    break;
                }
                if (retryDelayMillis > 0) {
                    Thread.sleep(retryDelayMillis);
                }
            }
        }
        throw lastFailure;
    }

    private static PageFetcher httpFetcher() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        InetSocketAddress proxyAddress = proxyAddress(proxyEnvironmentValue());
        if (proxyAddress != null) {
            builder.proxy(ProxySelector.of(proxyAddress));
        }
        HttpClient httpClient = builder.build();
        return (url, offset, length) -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("download failed: status=" + response.statusCode()
                        + ", offset=" + offset + ", body=" + response.body());
            }
            return response.body();
        };
    }

    static InetSocketAddress proxyAddress(String proxyValue) {
        if (proxyValue == null || proxyValue.isBlank()) {
            return null;
        }
        URI uri = URI.create(proxyValue);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || port <= 0) {
            return null;
        }
        return InetSocketAddress.createUnresolved(host, port);
    }

    private static String proxyEnvironmentValue() {
        String httpsProxy = System.getenv("HTTPS_PROXY");
        if (httpsProxy == null || httpsProxy.isBlank()) {
            httpsProxy = System.getenv("https_proxy");
        }
        if (httpsProxy == null || httpsProxy.isBlank()) {
            httpsProxy = System.getenv("HTTP_PROXY");
        }
        if (httpsProxy == null || httpsProxy.isBlank()) {
            httpsProxy = System.getenv("http_proxy");
        }
        return httpsProxy;
    }

    private static String rowsUrl(Options options, int offset, int length) {
        return "https://datasets-server.huggingface.co/rows"
                + "?dataset=" + encode(options.dataset())
                + "&config=" + encode(options.config())
                + "&split=" + encode(options.split())
                + "&offset=" + offset
                + "&length=" + length;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public interface PageFetcher {
        String fetch(String url, int offset, int length) throws Exception;
    }

    public interface Sleeper {
        void sleep(long millis) throws Exception;
    }

    public record DownloadSummary(
            int downloaded,
            int skipped,
            int totalPages
    ) {
    }

    public record Options(
            String dataset,
            String config,
            String split,
            Path outputDir,
            String filenameFormat,
            int startOffset,
            int totalRows,
            int pageSize,
            int retries,
            long retryDelayMillis,
            long pageDelayMillis,
            boolean overwrite
    ) {
        private static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if ("--overwrite".equals(arg)) {
                    values.put("overwrite", "true");
                    continue;
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }
            return new Options(
                    values.getOrDefault("dataset", "princeton-nlp/LitSearch"),
                    values.getOrDefault("config", "corpus_clean"),
                    values.getOrDefault("split", "full"),
                    Path.of(values.getOrDefault("output-dir", "eval/rag/litsearch/raw")),
                    values.getOrDefault("filename-format", "litsearch-corpus-clean-page-%05d.json"),
                    Integer.parseInt(values.getOrDefault("start-offset", "0")),
                    Integer.parseInt(values.getOrDefault("total-rows", "64183")),
                    Integer.parseInt(values.getOrDefault("page-size", "100")),
                    Integer.parseInt(values.getOrDefault("retries", "3")),
                    Long.parseLong(values.getOrDefault("retry-delay-millis", "1000")),
                    Long.parseLong(values.getOrDefault("page-delay-millis", "0")),
                    Boolean.parseBoolean(values.getOrDefault("overwrite", "false"))
            );
        }
    }
}
