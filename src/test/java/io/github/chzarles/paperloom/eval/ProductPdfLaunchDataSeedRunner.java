package io.github.chzarles.paperloom.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ProductPdfLaunchDataSeedRunner {

    private static final String ROUTE = "PRODUCT_PDF_LAUNCH_DATA_SEED";
    private static final int DEFAULT_CHUNK_SIZE_BYTES = 5 * 1024 * 1024;

    private final LaunchDataSeedClient client;

    public ProductPdfLaunchDataSeedRunner(LaunchDataSeedClient client) {
        this.client = client;
    }

    public Path run(Options options) throws IOException {
        if (client == null) {
            throw new IllegalStateException("LaunchDataSeedClient is required");
        }
        Options safeOptions = options == null ? Options.defaults() : options;
        List<ProductPdfParserSmokeRunner.ManifestCase> manifestCases =
                ProductPdfParserSmokeRunner.loadManifest(safeOptions.manifestPath());
        List<CaseAccumulator> accumulators = new ArrayList<>();
        ExistingStatusLookup existingStatusLookup = new ExistingStatusLookup(client);
        for (ProductPdfParserSmokeRunner.ManifestCase manifestCase : manifestCases) {
            accumulators.add(seedCase(manifestCase, safeOptions, existingStatusLookup));
        }
        Map<String, PaperStatus> statuses = safeOptions.waitForSearchable()
                ? pollStatuses(accumulators, safeOptions)
                : Map.of();
        List<CaseResult> results = accumulators.stream()
                .map(accumulator -> accumulator.toResult(
                        accumulator.resultStatus(statuses),
                        safeOptions.waitForSearchable()))
                .toList();
        return RagEvalRunWriter.write(
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId(),
                safeOptions.manifestPath().toString(),
                new RagBenchmarkRun(
                        results.stream().map(this::benchmarkCase).toList(),
                        results.stream().map(this::actual).toList(),
                        results.stream().map(this::verdict).toList()
                ),
                metrics(results)
        );
    }

    private CaseAccumulator seedCase(ProductPdfParserSmokeRunner.ManifestCase manifestCase,
                                     Options options,
                                     ExistingStatusLookup existingStatusLookup) {
        CaseAccumulator accumulator = new CaseAccumulator(manifestCase);
        Path pdfPath = resolvePath(manifestCase.path(), options.manifestPath());
        accumulator.diagnostics.put("manifestPath", manifestCase.path());
        accumulator.diagnostics.put("resolvedPath", pdfPath.toString());
        accumulator.diagnostics.put("manifestPaperId", manifestCase.paperId());
        if (!Files.isRegularFile(pdfPath)) {
            accumulator.fail("local_pdf_missing(path=" + pdfPath + ")", "LOCAL_PDF_MISSING");
            return accumulator;
        }
        try {
            byte[] bytes = Files.readAllBytes(pdfPath);
            String paperId = md5(bytes);
            String originalFilename = firstNonBlank(manifestCase.originalFilename(), filename(pdfPath));
            accumulator.paperId = paperId;
            accumulator.originalFilename = originalFilename;
            accumulator.diagnostics.put("computedPaperId", paperId);
            accumulator.diagnostics.put("originalFilename", originalFilename);
            accumulator.diagnostics.put("totalSizeBytes", bytes.length);
            if (options.waitForSearchable()) {
                PaperStatus existingStatus;
                try {
                    existingStatus = existingStatusLookup.find(paperId);
                } catch (Exception exception) {
                    if (exception instanceof RuntimeUnavailableException runtimeUnavailableException) {
                        throw runtimeUnavailableException;
                    }
                    accumulator.fail("status_poll_failed("
                            + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")",
                            "FRONT_SEARCHABLE_MISSING");
                    return accumulator;
                }
                if (isFrontendSearchable(existingStatus)) {
                    accumulator.alreadySeeded = true;
                    accumulator.existingStatus = existingStatus;
                    accumulator.diagnostics.put("alreadySeeded", true);
                    return accumulator;
                }
            }
            uploadChunks(paperId, originalFilename, bytes, options.chunkSizeBytes());
            try {
                client.merge(new MergeRequest(paperId, originalFilename));
            } catch (Exception exception) {
                if (exception instanceof RuntimeUnavailableException runtimeUnavailableException) {
                    throw runtimeUnavailableException;
                }
                throw new MergeException(exception.getClass().getSimpleName() + ": " + exception.getMessage(), exception);
            }
            accumulator.merged = true;
        } catch (RuntimeUnavailableException exception) {
            accumulator.fail("login_failed(" + exception.getMessage() + ")", "RUNTIME_UNAVAILABLE");
        } catch (UploadException exception) {
            accumulator.fail("upload_failed(" + exception.getMessage() + ")", "UPLOAD_FAILED");
        } catch (MergeException exception) {
            accumulator.fail("merge_failed(" + exception.getMessage() + ")", "MERGE_FAILED");
        } catch (Exception exception) {
            accumulator.fail("launch_data_seed_failed("
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")", "UPLOAD_FAILED");
        }
        return accumulator;
    }

    private void uploadChunks(String paperId,
                              String originalFilename,
                              byte[] bytes,
                              int chunkSizeBytes) {
        int totalChunks = totalChunks(bytes.length, chunkSizeBytes);
        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            int start = chunkIndex * chunkSizeBytes;
            int end = Math.min(start + chunkSizeBytes, bytes.length);
            try {
                client.uploadChunk(new UploadChunkRequest(
                        paperId,
                        chunkIndex,
                        bytes.length,
                        originalFilename,
                        totalChunks,
                        false,
                        null,
                        Arrays.copyOfRange(bytes, start, end)
                ));
            } catch (Exception exception) {
                if (exception instanceof RuntimeUnavailableException runtimeUnavailableException) {
                    throw runtimeUnavailableException;
                }
                throw new UploadException(exception.getClass().getSimpleName() + ": " + exception.getMessage(), exception);
            }
        }
    }

    private Map<String, PaperStatus> pollStatuses(List<CaseAccumulator> accumulators, Options options) {
        Set<String> paperIds = accumulators.stream()
                .filter(accumulator -> accumulator.failures.isEmpty())
                .filter(accumulator -> !accumulator.alreadySeeded)
                .map(accumulator -> accumulator.paperId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (paperIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PaperStatus> latest = new LinkedHashMap<>();
        for (int attempt = 1; attempt <= options.pollAttempts(); attempt++) {
            try {
                for (PaperStatus status : client.listUploadedPapers()) {
                    if (status != null && paperIds.contains(status.paperId())) {
                        latest.put(status.paperId(), status);
                    }
                }
            } catch (Exception exception) {
                for (CaseAccumulator accumulator : accumulators) {
                    if (accumulator.paperId != null && accumulator.failures.isEmpty()) {
                        accumulator.fail("status_poll_failed("
                                + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")",
                                "FRONT_SEARCHABLE_MISSING");
                    }
                }
                return Map.copyOf(latest);
            }
            if (paperIds.stream().allMatch(paperId -> isFrontendSearchable(latest.get(paperId)))) {
                return Map.copyOf(latest);
            }
            sleep(options.pollIntervalMillis());
        }
        return Map.copyOf(latest);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static int totalChunks(int totalSizeBytes, int chunkSizeBytes) {
        int safeChunkSize = Math.max(1, chunkSizeBytes);
        return Math.max(1, (int) Math.ceil(totalSizeBytes / (double) safeChunkSize));
    }

    static boolean isFrontendSearchable(PaperStatus status) {
        if (status == null) {
            return false;
        }
        return uploadCompleted(status.uploadStatus())
                && "COMPLETED".equalsIgnoreCase(blankToDefault(status.processingStatus(), ""))
                && status.retrievalIndexedTokenCount() != null
                && status.retrievalIndexedLocationCount() != null
                && status.retrievalIndexedLocationCount() > 0;
    }

    private static boolean uploadCompleted(Object uploadStatus) {
        if (uploadStatus instanceof Number number) {
            return number.intValue() == 1;
        }
        String value = uploadStatus == null ? "" : String.valueOf(uploadStatus).trim();
        return "1".equals(value) || "COMPLETED".equalsIgnoreCase(value);
    }

    private RagBenchmarkCase benchmarkCase(CaseResult result) {
        return new RagBenchmarkCase(
                result.caseId(),
                "Product PDF launch data seed: " + result.caseId(),
                "zh",
                "PRODUCT_PDF_LAUNCH_DATA_SEED",
                "PRODUCT_UPLOAD",
                new RagBenchmarkCase.Scope(result.paperId() == null ? List.of() : List.of(result.paperId()), List.of()),
                ROUTE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                result.paperId() == null ? List.of() : List.of(result.paperId()),
                false
        );
    }

    private RagBenchmarkActual actual(CaseResult result) {
        return new RagBenchmarkActual(
                ROUTE,
                result.passed() ? "seeded" : "not seeded",
                Map.of(),
                result.diagnostics()
        );
    }

    private RagBenchmarkVerdict verdict(CaseResult result) {
        return new RagBenchmarkVerdict(
                result.caseId(),
                result.passed(),
                result.failures(),
                result.failureClass()
        );
    }

    private Map<String, Double> metrics(List<CaseResult> results) {
        long passed = results.stream().filter(CaseResult::passed).count();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("launchDataSeedPassRate", fraction(passed, results.size()));
        metrics.put("launchDataSeedCaseCount", (double) results.size());
        metrics.put("launchDataSeedPassedCount", (double) passed);
        return metrics;
    }

    private Path resolvePath(String rawPath, Path manifestPath) {
        String normalized = blankToNull(rawPath);
        if (normalized == null) {
            return Path.of("");
        }
        Path path = Path.of(normalized);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path manifestParent = manifestPath == null ? null : manifestPath.toAbsolutePath().getParent();
        if (manifestParent != null) {
            Path relativeToManifest = manifestParent.resolve(path).normalize();
            if (Files.exists(relativeToManifest)) {
                return relativeToManifest;
            }
        }
        return path.toAbsolutePath().normalize();
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    private static String filename(Path path) {
        return path == null || path.getFileName() == null ? "paper.pdf" : path.getFileName().toString();
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = blankToNull(first);
        return normalizedFirst == null ? blankToDefault(second, "") : normalizedFirst;
    }

    private static String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private double fraction(double numerator, double denominator) {
        return denominator == 0.0d ? 0.0d : numerator / denominator;
    }

    private static final class CaseAccumulator {
        private final ProductPdfParserSmokeRunner.ManifestCase manifestCase;
        private final List<String> failures = new ArrayList<>();
        private final List<String> failureClass = new ArrayList<>();
        private final Map<String, Object> diagnostics = new LinkedHashMap<>();
        private String paperId;
        private String originalFilename;
        private boolean merged;
        private boolean alreadySeeded;
        private PaperStatus existingStatus;

        private CaseAccumulator(ProductPdfParserSmokeRunner.ManifestCase manifestCase) {
            this.manifestCase = manifestCase;
        }

        private void fail(String failure, String failureClassName) {
            failures.add(failure);
            failureClass.add(failureClassName);
        }

        private CaseResult toResult(PaperStatus status, boolean waitForSearchable) {
            Map<String, Object> finalDiagnostics = new LinkedHashMap<>(diagnostics);
            finalDiagnostics.put("alreadySeeded", alreadySeeded);
            finalDiagnostics.put("merged", merged);
            finalDiagnostics.put("frontendSearchable", isFrontendSearchable(status));
            if (status != null) {
                finalDiagnostics.put("frontStatus", status.raw().isEmpty() ? statusMap(status) : status.raw());
            }
            List<String> finalFailures = new ArrayList<>(failures);
            List<String> finalFailureClass = new ArrayList<>(failureClass);
            if (finalFailures.isEmpty() && waitForSearchable && !isFrontendSearchable(status)) {
                finalFailures.add("front_searchable_missing(paperId=" + paperId + ")");
                finalFailureClass.add("FRONT_SEARCHABLE_MISSING");
            }
            return new CaseResult(
                    manifestCase.id(),
                    paperId,
                    finalFailures.isEmpty(),
                    finalFailures,
                    finalFailureClass,
                    finalDiagnostics
            );
        }

        private PaperStatus resultStatus(Map<String, PaperStatus> statuses) {
            if (existingStatus != null) {
                return existingStatus;
            }
            return paperId == null ? null : statuses.get(paperId);
        }

        private Map<String, Object> statusMap(PaperStatus status) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("paperId", status.paperId());
            map.put("originalFilename", status.originalFilename());
            map.put("uploadStatus", status.uploadStatus());
            map.put("processingStatus", status.processingStatus());
            map.put("retrievalIndexedTokenCount", status.retrievalIndexedTokenCount());
            map.put("retrievalIndexedLocationCount", status.retrievalIndexedLocationCount());
            return map;
        }
    }

    public interface LaunchDataSeedClient {
        void uploadChunk(UploadChunkRequest request);

        void merge(MergeRequest request);

        List<PaperStatus> listUploadedPapers();
    }

    public record UploadChunkRequest(
            String paperId,
            int chunkIndex,
            long totalSize,
            String paperTitle,
            int totalChunks,
            boolean isPublic,
            String orgTag,
            byte[] bytes
    ) {
        public UploadChunkRequest {
            paperId = blankToDefault(paperId, "");
            paperTitle = blankToDefault(paperTitle, "paper.pdf");
            bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    public record MergeRequest(
            String paperId,
            String paperTitle
    ) {
        public MergeRequest {
            paperId = blankToDefault(paperId, "");
            paperTitle = blankToDefault(paperTitle, "paper.pdf");
        }
    }

    public record PaperStatus(
            String paperId,
            String originalFilename,
            Object uploadStatus,
            String processingStatus,
            Long retrievalIndexedTokenCount,
            Integer retrievalIndexedLocationCount,
            Map<String, Object> raw
    ) {
        public PaperStatus {
            paperId = blankToDefault(paperId, "");
            originalFilename = blankToDefault(originalFilename, "");
            processingStatus = blankToDefault(processingStatus, "");
            raw = raw == null ? Map.of() : new LinkedHashMap<>(raw);
        }
    }

    private static final class ExistingStatusLookup {
        private final LaunchDataSeedClient client;
        private Map<String, PaperStatus> statuses;

        private ExistingStatusLookup(LaunchDataSeedClient client) {
            this.client = client;
        }

        private PaperStatus find(String paperId) {
            if (statuses == null) {
                statuses = client.listUploadedPapers().stream()
                        .filter(status -> status != null && status.paperId() != null && !status.paperId().isBlank())
                        .collect(Collectors.toMap(
                                PaperStatus::paperId,
                                status -> status,
                                (first, second) -> first,
                                LinkedHashMap::new
                        ));
            }
            return statuses.get(paperId);
        }
    }

    public record CaseResult(
            String caseId,
            String paperId,
            boolean passed,
            List<String> failures,
            List<String> failureClass,
            Map<String, Object> diagnostics
    ) {
        public CaseResult {
            caseId = blankToDefault(caseId, "pdf_launch_seed");
            failures = failures == null ? List.of() : List.copyOf(failures);
            failureClass = failureClass == null ? List.of() : List.copyOf(failureClass);
            diagnostics = diagnostics == null ? Map.of() : new LinkedHashMap<>(diagnostics);
        }
    }

    public record Options(
            Path manifestPath,
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId,
            Integer chunkSizeBytes,
            Integer pollAttempts,
            Long pollIntervalMillis,
            Boolean waitForSearchable
    ) {
        public Options {
            manifestPath = manifestPath == null
                    ? Path.of("eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl")
                    : manifestPath;
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            harnessId = blankToDefault(harnessId, "product-pdf-launch-data-seed");
            datasetId = blankToDefault(datasetId, "product-pdf-launch-30");
            runId = blankToDefault(runId, defaultRunId(startedAt, harnessId, datasetId));
            chunkSizeBytes = Math.max(1, chunkSizeBytes == null ? DEFAULT_CHUNK_SIZE_BYTES : chunkSizeBytes);
            pollAttempts = Math.max(0, pollAttempts == null ? 180 : pollAttempts);
            pollIntervalMillis = Math.max(0L, pollIntervalMillis == null ? 10_000L : pollIntervalMillis);
            waitForSearchable = waitForSearchable == null || waitForSearchable;
        }

        static Options defaults() {
            return new Options(
                    Path.of("eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl"),
                    Path.of("eval/rag/runs"),
                    null,
                    Instant.now().toString(),
                    "product-pdf-launch-data-seed",
                    "product-pdf-launch-30",
                    DEFAULT_CHUNK_SIZE_BYTES,
                    180,
                    10_000L,
                    true
            );
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            return startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z")
                    + "-" + harnessId + "-" + datasetId;
        }
    }

    private static final class UploadException extends RuntimeException {
        private UploadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class MergeException extends RuntimeException {
        private MergeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class RuntimeUnavailableException extends RuntimeException {
        public RuntimeUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
