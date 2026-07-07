package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperVisualAsset;
import com.yizhaoqi.smartpai.repository.PaperParserArtifactRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.repository.PaperVisualAssetRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class ProductPdfParserSmokeRunner {

    static final String ROUTE = "PDF_PARSER_SMOKE";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PaperRepository paperRepository;
    private final PaperTextChunkRepository chunkRepository;
    private final PaperParserArtifactRepository parserArtifactRepository;
    private final PaperVisualAssetRepository visualAssetRepository;
    private final PaperReadingModelRepository readingModelRepository;
    private final PaperReadingElementRepository readingElementRepository;

    public ProductPdfParserSmokeRunner(PaperRepository paperRepository,
                                       PaperTextChunkRepository chunkRepository,
                                       PaperParserArtifactRepository parserArtifactRepository,
                                       PaperVisualAssetRepository visualAssetRepository,
                                       PaperReadingModelRepository readingModelRepository,
                                       PaperReadingElementRepository readingElementRepository) {
        this.paperRepository = paperRepository;
        this.chunkRepository = chunkRepository;
        this.parserArtifactRepository = parserArtifactRepository;
        this.visualAssetRepository = visualAssetRepository;
        this.readingModelRepository = readingModelRepository;
        this.readingElementRepository = readingElementRepository;
    }

    public Path run(Options options) throws IOException {
        Options safeOptions = options == null ? Options.defaults() : options;
        List<ManifestCase> manifestCases = loadManifest(safeOptions.manifestPath());
        List<CaseResult> results = new ArrayList<>();
        for (ManifestCase manifestCase : manifestCases) {
            results.add(evaluateCase(manifestCase, safeOptions.manifestPath()));
        }
        return RagEvalRunWriter.write(
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId(),
                safeOptions.manifestPath().toString(),
                new RagBenchmarkRun(
                        results.stream().map(ProductPdfParserSmokeRunner::benchmarkCase).toList(),
                        results.stream().map(ProductPdfParserSmokeRunner::actual).toList(),
                        results.stream().map(ProductPdfParserSmokeRunner::verdict).toList()
                ),
                metrics(results)
        );
    }

    public static Path runStartupFailure(Options options, String message) throws IOException {
        Options safeOptions = options == null ? Options.defaults() : options;
        String safeMessage = blankToDefault(message, "startup unavailable");
        List<CaseResult> results = loadManifest(safeOptions.manifestPath()).stream()
                .map(manifestCase -> startupFailureResult(manifestCase, safeMessage))
                .toList();
        return RagEvalRunWriter.write(
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId(),
                safeOptions.manifestPath().toString(),
                new RagBenchmarkRun(
                        results.stream().map(ProductPdfParserSmokeRunner::benchmarkCase).toList(),
                        results.stream().map(ProductPdfParserSmokeRunner::actual).toList(),
                        results.stream().map(ProductPdfParserSmokeRunner::verdict).toList()
                ),
                metrics(results)
        );
    }

    private static CaseResult startupFailureResult(ManifestCase manifestCase, String message) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("manifestPath", manifestCase.path());
        diagnostics.put("manifestPaperId", manifestCase.paperId());
        diagnostics.put("manifestOriginalFilename", manifestCase.originalFilename());
        diagnostics.put("expectedParser", manifestCase.expectedParser());
        diagnostics.put("expectedMinChunks", manifestCase.minChunks());
        diagnostics.put("expectedMinPages", manifestCase.minPages());
        diagnostics.put("requiresParserArtifact", manifestCase.isParserArtifactRequired());
        diagnostics.put("requiresPageScreenshot", manifestCase.isPageScreenshotRequired());
        diagnostics.put("requiresTableOrFigure", manifestCase.isTableOrFigureRequired());
        diagnostics.put("startupFailure", message);
        return new CaseResult(
                manifestCase.id(),
                null,
                false,
                List.of("pdf_parser_smoke_startup_failed(" + message + ")"),
                List.of("RUNTIME_UNAVAILABLE"),
                diagnostics
        );
    }

    CaseResult evaluateCase(ManifestCase manifestCase, Path manifestPath) {
        List<String> failures = new ArrayList<>();
        LinkedHashSet<String> failureClass = new LinkedHashSet<>();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("manifestPath", manifestCase.path());
        diagnostics.put("manifestPaperId", manifestCase.paperId());
        diagnostics.put("manifestOriginalFilename", manifestCase.originalFilename());
        diagnostics.put("expectedParser", manifestCase.expectedParser());
        diagnostics.put("expectedMinChunks", manifestCase.minChunks());
        diagnostics.put("expectedMinPages", manifestCase.minPages());
        diagnostics.put("requiresParserArtifact", manifestCase.isParserArtifactRequired());
        diagnostics.put("requiresPageScreenshot", manifestCase.isPageScreenshotRequired());
        diagnostics.put("requiresTableOrFigure", manifestCase.isTableOrFigureRequired());

        Optional<LocatedPaper> locatedPaper;
        try {
            locatedPaper = locatePaper(manifestCase, manifestPath);
        } catch (IOException exception) {
            addFailure(failures, failureClass, "paper_hash_unreadable: " + exception.getMessage(), "PAPER_ROW");
            locatedPaper = Optional.empty();
        }

        if (locatedPaper.isEmpty()) {
            addFailure(failures, failureClass, "paper_row_missing", "PAPER_ROW");
            return new CaseResult(manifestCase.id(), null, false, failures, List.copyOf(failureClass), diagnostics);
        }

        Paper paper = locatedPaper.get().paper();
        String paperId = blankToNull(paper.getPaperId());
        diagnostics.put("locatedBy", locatedPaper.get().locatedBy());
        diagnostics.put("paperId", paperId);
        diagnostics.put("originalFilename", paper.getOriginalFilename());
        diagnostics.put("paperTitle", paper.getPaperTitle());
        diagnostics.put("uploadStatus", paper.getStatus());
        diagnostics.put("vectorizationStatus", paper.getVectorizationStatus());

        if (paperId == null) {
            addFailure(failures, failureClass, "paper_id_missing", "PDF_PROVENANCE");
        }
        if (isJsonRow(paper, manifestCase)) {
            addFailure(failures, failureClass, "json_row_not_pdf", "PDF_PROVENANCE");
        }
        if (!isPdfFilename(paper.getOriginalFilename())) {
            addFailure(failures, failureClass, "original_filename_not_pdf", "PDF_PROVENANCE");
        }
        if (isFailedProcessingStatus(paper.getVectorizationStatus())) {
            addFailure(failures, failureClass, "processing_failed", "PROCESSING_STATUS");
        }

        if (paperId != null) {
            addCountChecks(manifestCase, paperId, failures, failureClass, diagnostics);
        }

        return new CaseResult(
                manifestCase.id(),
                paperId,
                failures.isEmpty(),
                List.copyOf(failures),
                List.copyOf(failureClass),
                diagnostics
        );
    }

    static List<ManifestCase> loadManifest(Path manifestPath) throws IOException {
        List<ManifestCase> cases = new ArrayList<>();
        for (String rawLine : Files.readAllLines(manifestPath)) {
            String line = rawLine.strip();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            cases.add(OBJECT_MAPPER.readValue(line, ManifestCase.class));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Manifest contains no parser smoke cases: " + manifestPath);
        }
        return List.copyOf(cases);
    }

    private void addCountChecks(ManifestCase manifestCase,
                                String paperId,
                                List<String> failures,
                                LinkedHashSet<String> failureClass,
                                Map<String, Object> diagnostics) {
        long chunkCount = chunkRepository.countByPaperId(paperId);
        long pageAwareChunkCount = chunkRepository.countByPaperIdAndPageNumberIsNotNull(paperId);
        long parserArtifactCount = parserArtifactRepository.countByPaperId(paperId);
        long pageScreenshotCount = visualAssetRepository.countByPaperIdAndAssetType(
                paperId,
                PaperVisualAsset.TYPE_PAGE_SCREENSHOT
        );
        long tableCropCount = visualAssetRepository.countByPaperIdAndAssetType(
                paperId,
                PaperVisualAsset.TYPE_TABLE_CROP
        );
        long figureCropCount = visualAssetRepository.countByPaperIdAndAssetType(
                paperId,
                PaperVisualAsset.TYPE_FIGURE_CROP
        );
        long chartCropCount = visualAssetRepository.countByPaperIdAndAssetType(
                paperId,
                PaperVisualAsset.TYPE_CHART_CROP
        );
        String currentModelVersion = currentReadingModelVersion(paperId);
        long tableCount = currentReadingElementCount(paperId, currentModelVersion, "TABLE");
        long figureCount = currentReadingElementCount(paperId, currentModelVersion, "IMAGE")
                + currentReadingElementCount(paperId, currentModelVersion, "CHART");
        long formulaCount = currentReadingElementCount(paperId, currentModelVersion, "FORMULA");

        diagnostics.put("chunkCount", chunkCount);
        diagnostics.put("pageAwareChunkCount", pageAwareChunkCount);
        diagnostics.put("parserArtifactCount", parserArtifactCount);
        diagnostics.put("readingModelVersion", currentModelVersion);
        diagnostics.put("structuredContentSource", "paper_reading_elements");
        diagnostics.put("pageScreenshotCount", pageScreenshotCount);
        diagnostics.put("tableCount", tableCount);
        diagnostics.put("figureCount", figureCount);
        diagnostics.put("formulaCount", formulaCount);
        diagnostics.put("tableCropCount", tableCropCount);
        diagnostics.put("figureCropCount", figureCropCount);
        diagnostics.put("chartCropCount", chartCropCount);

        if (chunkCount < manifestCase.minChunks()) {
            addFailure(
                    failures,
                    failureClass,
                    "chunk_count_below_min(expected>=" + manifestCase.minChunks() + ", actual=" + chunkCount + ")",
                    "CHUNK_COVERAGE"
            );
        }
        if (pageAwareChunkCount < manifestCase.minPages()) {
            addFailure(
                    failures,
                    failureClass,
                    "page_aware_chunk_count_below_min(expected>=" + manifestCase.minPages()
                            + ", actual=" + pageAwareChunkCount + ")",
                    "PAGE_COVERAGE"
            );
        }
        if (manifestCase.isParserArtifactRequired() && parserArtifactCount <= 0) {
            addFailure(failures, failureClass, "parser_artifact_missing", "PARSER_ARTIFACT");
        }
        if (manifestCase.isPageScreenshotRequired()) {
            long minimumScreenshots = Math.max(1, manifestCase.minPages());
            if (pageScreenshotCount < minimumScreenshots) {
                addFailure(
                        failures,
                        failureClass,
                        "page_screenshot_count_below_min(expected>=" + minimumScreenshots
                                + ", actual=" + pageScreenshotCount + ")",
                        "VISUAL_ASSET"
                );
            }
        }
        if (manifestCase.isTableOrFigureRequired() && tableCount + figureCount <= 0) {
            addFailure(failures, failureClass, "table_or_figure_missing", "STRUCTURED_CONTENT");
        }
        addMinimumCountFailure(failures, failureClass, "table_count", tableCount,
                manifestCase.minTables(), "STRUCTURED_CONTENT");
        addMinimumCountFailure(failures, failureClass, "figure_count", figureCount,
                manifestCase.minFigures(), "STRUCTURED_CONTENT");
        addMinimumCountFailure(failures, failureClass, "formula_count", formulaCount,
                manifestCase.minFormulas(), "STRUCTURED_CONTENT");
        addMinimumCountFailure(failures, failureClass, "table_crop_count", tableCropCount,
                manifestCase.minTableCrops(), "VISUAL_ASSET");
        addMinimumCountFailure(failures, failureClass, "figure_crop_count", figureCropCount,
                manifestCase.minFigureCrops(), "VISUAL_ASSET");
        addMinimumCountFailure(failures, failureClass, "chart_crop_count", chartCropCount,
                manifestCase.minChartCrops(), "VISUAL_ASSET");
    }

    private String currentReadingModelVersion(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return null;
        }
        Optional<PaperReadingModel> current = readingModelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId);
        return current == null ? null : current.map(PaperReadingModel::getModelVersion).orElse(null);
    }

    private long currentReadingElementCount(String paperId, String modelVersion, String elementType) {
        if (paperId == null || paperId.isBlank()
                || modelVersion == null || modelVersion.isBlank()
                || elementType == null || elementType.isBlank()) {
            return 0;
        }
        return readingElementRepository.countByPaperIdAndModelVersionAndElementType(paperId, modelVersion, elementType);
    }

    private Optional<LocatedPaper> locatePaper(ManifestCase manifestCase, Path manifestPath) throws IOException {
        String explicitPaperId = blankToNull(manifestCase.paperId());
        if (explicitPaperId != null) {
            Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(explicitPaperId);
            if (paper.isPresent()) {
                return paper.map(value -> new LocatedPaper(value, "manifest.paperId"));
            }
        }

        Path resolvedPath = resolvePath(manifestCase.path(), manifestPath);
        if (resolvedPath != null && Files.exists(resolvedPath) && Files.isRegularFile(resolvedPath)) {
            String fileMd5 = md5Hex(resolvedPath);
            Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(fileMd5);
            if (paper.isPresent()) {
                return paper.map(value -> new LocatedPaper(value, "md5(path)"));
            }
        }

        String filename = firstNonBlank(manifestCase.originalFilename(), filename(manifestCase.path()));
        if (filename == null) {
            return Optional.empty();
        }
        List<Paper> papers = paperRepository.findAll();
        if (papers == null || papers.isEmpty()) {
            return Optional.empty();
        }
        return papers.stream()
                .filter(paper -> filename.equalsIgnoreCase(blankToDefault(paper.getOriginalFilename(), "")))
                .max(Comparator.comparing(Paper::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(paper -> new LocatedPaper(paper, "originalFilename"));
    }

    private static RagBenchmarkCase benchmarkCase(CaseResult result) {
        List<String> expectedPaperIds = result.paperId() == null ? List.of() : List.of(result.paperId());
        return new RagBenchmarkCase(
                result.caseId(),
                "PDF parser smoke " + result.caseId(),
                "zh",
                "PDF_PARSER_SMOKE",
                "MANIFEST",
                new RagBenchmarkCase.Scope(expectedPaperIds, List.of()),
                ROUTE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                expectedPaperIds,
                false
        );
    }

    private static RagBenchmarkActual actual(CaseResult result) {
        String markdown = result.passed()
                ? "PDF parser smoke passed for " + result.caseId()
                : String.join("; ", result.failures());
        return new RagBenchmarkActual(ROUTE, markdown, Map.of(), result.diagnostics());
    }

    private static RagBenchmarkVerdict verdict(CaseResult result) {
        return new RagBenchmarkVerdict(
                result.caseId(),
                result.passed(),
                result.failures(),
                result.failureClass()
        );
    }

    private static Map<String, Double> metrics(List<CaseResult> results) {
        int caseCount = results.size();
        long passed = results.stream().filter(CaseResult::passed).count();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("pdfParserSmokePassRate", fraction(passed, caseCount));
        metrics.put("avgChunkCount", averageDiagnostic(results, "chunkCount"));
        metrics.put("avgPageAwareChunkCount", averageDiagnostic(results, "pageAwareChunkCount"));
        metrics.put("avgParserArtifactCount", averageDiagnostic(results, "parserArtifactCount"));
        metrics.put("avgPageScreenshotCount", averageDiagnostic(results, "pageScreenshotCount"));
        metrics.put("jsonRowRejectedCount", (double) results.stream()
                .filter(result -> result.failures().contains("json_row_not_pdf"))
                .count());
        metrics.put("parserArtifactMissingRate", failureRate(results, "PARSER_ARTIFACT"));
        metrics.put("visualAssetMissingRate", failureRate(results, "VISUAL_ASSET"));
        return metrics;
    }

    private static double averageDiagnostic(List<CaseResult> results, String key) {
        return results.stream()
                .map(CaseResult::diagnostics)
                .map(map -> map.get(key))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToDouble(Number::doubleValue)
                .average()
                .orElse(0.0d);
    }

    private static double failureRate(List<CaseResult> results, String failureClass) {
        if (results.isEmpty()) {
            return 0.0d;
        }
        long count = results.stream()
                .filter(result -> result.failureClass().contains(failureClass))
                .count();
        return fraction(count, results.size());
    }

    private boolean isJsonRow(Paper paper, ManifestCase manifestCase) {
        return hasJsonExtension(paper.getOriginalFilename())
                || hasJsonExtension(manifestCase.path())
                || hasJsonExtension(manifestCase.originalFilename());
    }

    private boolean isFailedProcessingStatus(String status) {
        return status != null && Paper.VECTORIZATION_STATUS_FAILED.equalsIgnoreCase(status.trim());
    }

    private boolean isPdfFilename(String filename) {
        return filename != null && filename.trim().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private boolean hasJsonExtension(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private void addMinimumCountFailure(List<String> failures,
                                        LinkedHashSet<String> failureClass,
                                        String metricName,
                                        long actual,
                                        int minimum,
                                        String failureClassName) {
        if (minimum > 0 && actual < minimum) {
            addFailure(
                    failures,
                    failureClass,
                    metricName + "_below_min(expected>=" + minimum + ", actual=" + actual + ")",
                    failureClassName
            );
        }
    }

    private void addFailure(List<String> failures,
                            LinkedHashSet<String> failureClass,
                            String failure,
                            String failureClassName) {
        failures.add(failure);
        failureClass.add(failureClassName);
    }

    private Path resolvePath(String rawPath, Path manifestPath) {
        String normalizedPath = blankToNull(rawPath);
        if (normalizedPath == null) {
            return null;
        }
        Path direct = Path.of(normalizedPath);
        if (direct.isAbsolute() || Files.exists(direct)) {
            return direct;
        }
        Path parent = manifestPath == null ? null : manifestPath.toAbsolutePath().getParent();
        if (parent == null) {
            return direct;
        }
        Path relativeToManifest = parent.resolve(direct).normalize();
        if (Files.exists(relativeToManifest)) {
            return relativeToManifest;
        }
        return direct;
    }

    private String md5Hex(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            try (InputStream inputStream = Files.newInputStream(path)) {
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 digest is unavailable", exception);
        }
    }

    private String filename(String rawPath) {
        String value = blankToNull(rawPath);
        if (value == null) {
            return null;
        }
        Path path = Path.of(value);
        Path filename = path.getFileName();
        return filename == null ? null : filename.toString();
    }

    private static String firstNonBlank(String first, String second) {
        String value = blankToNull(first);
        return value == null ? blankToNull(second) : value;
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

    private static double fraction(double numerator, double denominator) {
        return denominator == 0.0d ? 0.0d : numerator / denominator;
    }

    private record LocatedPaper(Paper paper, String locatedBy) {
    }

    public record ManifestCase(
            String id,
            String path,
            String paperId,
            String originalFilename,
            Integer expectedMinChunks,
            Integer expectedMinPages,
            String expectedParser,
            Boolean requiresPageScreenshot,
            Boolean requiresParserArtifact,
            Boolean requiresTableOrFigure,
            Integer expectedMinTables,
            Integer expectedMinFigures,
            Integer expectedMinFormulas,
            Integer expectedMinTableCrops,
            Integer expectedMinFigureCrops,
            Integer expectedMinChartCrops
    ) {
        public ManifestCase {
            id = blankToDefault(id, defaultId(path, paperId, originalFilename));
            path = blankToNull(path);
            paperId = blankToNull(paperId);
            originalFilename = blankToNull(originalFilename);
            expectedParser = blankToNull(expectedParser);
        }

        int minChunks() {
            return Math.max(0, expectedMinChunks == null ? 1 : expectedMinChunks);
        }

        int minPages() {
            return Math.max(0, expectedMinPages == null ? 0 : expectedMinPages);
        }

        int minTables() {
            return Math.max(0, expectedMinTables == null ? 0 : expectedMinTables);
        }

        int minFigures() {
            return Math.max(0, expectedMinFigures == null ? 0 : expectedMinFigures);
        }

        int minFormulas() {
            return Math.max(0, expectedMinFormulas == null ? 0 : expectedMinFormulas);
        }

        int minTableCrops() {
            return Math.max(0, expectedMinTableCrops == null ? 0 : expectedMinTableCrops);
        }

        int minFigureCrops() {
            return Math.max(0, expectedMinFigureCrops == null ? 0 : expectedMinFigureCrops);
        }

        int minChartCrops() {
            return Math.max(0, expectedMinChartCrops == null ? 0 : expectedMinChartCrops);
        }

        boolean isPageScreenshotRequired() {
            return Boolean.TRUE.equals(requiresPageScreenshot);
        }

        boolean isParserArtifactRequired() {
            return requiresParserArtifact == null || Boolean.TRUE.equals(requiresParserArtifact);
        }

        boolean isTableOrFigureRequired() {
            return Boolean.TRUE.equals(requiresTableOrFigure);
        }

        private static String defaultId(String path, String paperId, String originalFilename) {
            String value = firstNonBlank(paperId, firstNonBlank(originalFilename, path));
            if (value == null) {
                return "pdf_parser_smoke";
            }
            return value.replaceAll("[^A-Za-z0-9._-]+", "_");
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
            String datasetId
    ) {
        public Options {
            manifestPath = manifestPath == null
                    ? Path.of("eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl")
                    : manifestPath;
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            harnessId = blankToDefault(harnessId, "product-pdf-parser-smoke");
            datasetId = blankToDefault(datasetId, "product-pdf-parser-smoke");
            runId = blankToDefault(runId, defaultRunId(startedAt, harnessId, datasetId));
        }

        static Options defaults() {
            return new Options(
                    Path.of("eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl"),
                    Path.of("eval/rag/runs"),
                    null,
                    Instant.now().toString(),
                    "product-pdf-parser-smoke",
                    "product-pdf-parser-smoke"
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
}
