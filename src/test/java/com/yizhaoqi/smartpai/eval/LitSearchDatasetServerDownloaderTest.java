package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitSearchDatasetServerDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsMissingPagesAndSkipsExistingPages() throws Exception {
        Path outputDir = tempDir.resolve("raw");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("litsearch-corpus-clean-page-00000.json"), rowsJson(100));
        List<Integer> fetchedOffsets = new ArrayList<>();

        LitSearchDatasetServerDownloader.DownloadSummary summary = LitSearchDatasetServerDownloader.download(
                new LitSearchDatasetServerDownloader.Options(
                        "princeton-nlp/LitSearch",
                        "corpus_clean",
                        "full",
                        outputDir,
                        "litsearch-corpus-clean-page-%05d.json",
                        0,
                        250,
                        100,
                        3,
                        0,
                        0,
                        false
                ),
                (url, offset, length) -> {
                    fetchedOffsets.add(offset);
                    return "{\"offset\":" + offset + ",\"length\":" + length + ",\"rows\":[]}";
                }
        );

        assertEquals(List.of(100, 200), fetchedOffsets);
        assertEquals(2, summary.downloaded());
        assertEquals(1, summary.skipped());
        assertEquals(3, summary.totalPages());
        assertTrue(Files.readString(outputDir.resolve("litsearch-corpus-clean-page-00100.json")).contains("\"offset\":100"));
        assertTrue(Files.readString(outputDir.resolve("litsearch-corpus-clean-page-00200.json")).contains("\"length\":100"));
    }

    @Test
    void retriesTransientFetcherFailures() throws Exception {
        Path outputDir = tempDir.resolve("retry-raw");
        AtomicInteger attempts = new AtomicInteger();

        LitSearchDatasetServerDownloader.DownloadSummary summary = LitSearchDatasetServerDownloader.download(
                new LitSearchDatasetServerDownloader.Options(
                        "princeton-nlp/LitSearch",
                        "corpus_clean",
                        "full",
                        outputDir,
                        "page-%05d.json",
                        0,
                        10,
                        10,
                        2,
                        0,
                        0,
                        false
                ),
                (url, offset, length) -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary timeout");
                    }
                    return "{\"rows\":[]}";
                }
        );

        assertEquals(2, attempts.get());
        assertEquals(1, summary.downloaded());
        assertEquals("{\"rows\":[]}", Files.readString(outputDir.resolve("page-00000.json")));
    }

    @Test
    void waitsBetweenDownloadedPagesWhenPageDelayIsConfigured() throws Exception {
        Path outputDir = tempDir.resolve("paced-raw");
        List<Long> sleeps = new ArrayList<>();

        LitSearchDatasetServerDownloader.DownloadSummary summary = LitSearchDatasetServerDownloader.download(
                new LitSearchDatasetServerDownloader.Options(
                        "princeton-nlp/LitSearch",
                        "corpus_clean",
                        "full",
                        outputDir,
                        "page-%05d.json",
                        0,
                        20,
                        10,
                        1,
                        0,
                        250,
                        false
                ),
                (url, offset, length) -> "{\"rows\":[{\"row_idx\":" + offset + "}]}",
                sleeps::add
        );

        assertEquals(2, summary.downloaded());
        assertEquals(List.of(250L), sleeps);
    }

    @Test
    void redownloadsExistingPageWhenRowCountDoesNotMatchExpectedPageSize() throws Exception {
        Path outputDir = tempDir.resolve("mismatch-raw");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("page-00000.json"), "{\"rows\":[{\"row_idx\":0}]}");
        AtomicInteger attempts = new AtomicInteger();

        LitSearchDatasetServerDownloader.DownloadSummary summary = LitSearchDatasetServerDownloader.download(
                new LitSearchDatasetServerDownloader.Options(
                        "princeton-nlp/LitSearch",
                        "corpus_clean",
                        "full",
                        outputDir,
                        "page-%05d.json",
                        0,
                        2,
                        2,
                        1,
                        0,
                        0,
                        false
                ),
                (url, offset, length) -> {
                    attempts.incrementAndGet();
                    return "{\"rows\":[{\"row_idx\":0},{\"row_idx\":1}]}";
                }
        );

        assertEquals(1, attempts.get());
        assertEquals(1, summary.downloaded());
        assertEquals(0, summary.skipped());
        assertTrue(Files.readString(outputDir.resolve("page-00000.json")).contains("\"row_idx\":1"));
    }

    @Test
    void redownloadsExistingPageWhenRowIndexesDoNotMatchOffset() throws Exception {
        Path outputDir = tempDir.resolve("wrong-index-raw");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("page-00100.json"), "{\"rows\":[{\"row_idx\":0},{\"row_idx\":1}]}");
        AtomicInteger attempts = new AtomicInteger();

        LitSearchDatasetServerDownloader.DownloadSummary summary = LitSearchDatasetServerDownloader.download(
                new LitSearchDatasetServerDownloader.Options(
                        "princeton-nlp/LitSearch",
                        "corpus_clean",
                        "full",
                        outputDir,
                        "page-%05d.json",
                        100,
                        2,
                        2,
                        1,
                        0,
                        0,
                        false
                ),
                (url, offset, length) -> {
                    attempts.incrementAndGet();
                    return "{\"rows\":[{\"row_idx\":100},{\"row_idx\":101}]}";
                }
        );

        assertEquals(1, attempts.get());
        assertEquals(1, summary.downloaded());
        assertEquals(0, summary.skipped());
        assertTrue(Files.readString(outputDir.resolve("page-00100.json")).contains("\"row_idx\":101"));
    }

    @Test
    void parsesHttpProxyEnvironmentValue() {
        InetSocketAddress address = LitSearchDatasetServerDownloader.proxyAddress("http://127.0.0.1:10808");

        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(10808, address.getPort());
    }

    private static String rowsJson(int count) {
        StringBuilder builder = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append("{\"row_idx\":").append(i).append("}");
        }
        return builder.append("]}").toString();
    }
}
