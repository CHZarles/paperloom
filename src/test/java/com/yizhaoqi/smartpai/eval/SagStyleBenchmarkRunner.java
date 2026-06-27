package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SagStyleBenchmarkRunner {

    private SagStyleBenchmarkRunner() {
    }

    public static void main(String[] args) throws Exception {
        run(Options.parse(args));
    }

    public static Path run(Options options) throws IOException {
        List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(options.casesPath());
        List<SearchResult> chunks = PaperPageChunkDataset.loadSearchResults(options.chunksPath());
        Map<String, List<SearchResult>> hitsByCase = new LinkedHashMap<>();
        List<RagBenchmarkActual> actuals = new java.util.ArrayList<>();
        List<RagBenchmarkVerdict> verdicts = new java.util.ArrayList<>();

        for (RagBenchmarkCase testCase : cases) {
            List<SearchResult> scopedChunks = scopedChunks(chunks, testCase);
            List<SagStyleExpansionHarness.Hit> hits = SagStyleExpansionHarness.retrieve(
                    testCase.query(),
                    scopedChunks,
                    options.topK()
            );
            List<SearchResult> hitChunks = hits.stream()
                    .map(SagStyleExpansionHarness.Hit::chunk)
                    .toList();
            hitsByCase.put(testCase.id(), hitChunks);
            String evidenceText = evidenceText(hitChunks);
            boolean passed = evidenceMatches(testCase.requiredEvidenceRegex(), evidenceText);
            actuals.add(new RagBenchmarkActual(
                    "SAG_STYLE_FAST_MODE",
                    evidenceText,
                    Map.of(),
                    diagnostics(hits)
            ));
            verdicts.add(new RagBenchmarkVerdict(
                    testCase.id(),
                    passed,
                    passed ? List.of() : List.of("REQUIRED_EVIDENCE_MISSING"),
                    passed ? List.of() : List.of("FALSE_NEGATIVE")
            ));
        }

        Map<String, Double> metrics = PaperEvidenceHitScorer.scoreChunkEvidence(
                cases,
                hitsByCase,
                1,
                options.topK()
        );
        return RagEvalRunWriter.write(
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId(),
                options.casesPath().toString(),
                new RagBenchmarkRun(cases, actuals, verdicts),
                metrics
        );
    }

    private static List<SearchResult> scopedChunks(List<SearchResult> chunks, RagBenchmarkCase testCase) {
        LinkedHashSet<String> paperIds = new LinkedHashSet<>();
        if (testCase.scope() != null) {
            paperIds.addAll(testCase.scope().paperIds());
        }
        paperIds.addAll(testCase.expectedPaperIds());
        if (paperIds.isEmpty()) {
            return chunks == null ? List.of() : chunks;
        }
        return (chunks == null ? List.<SearchResult>of() : chunks).stream()
                .filter(chunk -> chunk != null && paperIds.contains(chunk.getPaperId()))
                .toList();
    }

    private static Map<String, Object> diagnostics(List<SagStyleExpansionHarness.Hit> hits) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("acceptedEvidenceCount", hits == null ? 0 : hits.size());
        diagnostics.put("matchedEntities", (hits == null ? List.<SagStyleExpansionHarness.Hit>of() : hits).stream()
                .map(SagStyleExpansionHarness.Hit::matchedEntities)
                .toList());
        diagnostics.put("reasons", (hits == null ? List.<SagStyleExpansionHarness.Hit>of() : hits).stream()
                .map(SagStyleExpansionHarness.Hit::reasons)
                .toList());
        diagnostics.put("chunkIds", (hits == null ? List.<SagStyleExpansionHarness.Hit>of() : hits).stream()
                .map(hit -> hit.chunk().getChunkId())
                .toList());
        return diagnostics;
    }

    private static String evidenceText(List<SearchResult> chunks) {
        return (chunks == null ? List.<SearchResult>of() : chunks).stream()
                .map(SagStyleBenchmarkRunner::chunkText)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static String chunkText(SearchResult chunk) {
        if (chunk == null) {
            return "";
        }
        if (chunk.getMatchedChunkText() != null && !chunk.getMatchedChunkText().isBlank()) {
            return chunk.getMatchedChunkText();
        }
        return chunk.getTextContent() == null ? "" : chunk.getTextContent();
    }

    private static boolean evidenceMatches(List<String> patterns, String text) {
        List<String> usefulPatterns = (patterns == null ? List.<String>of() : patterns).stream()
                .map(pattern -> pattern == null ? "" : pattern.trim())
                .filter(pattern -> !pattern.isBlank())
                .filter(pattern -> !".".equals(pattern) && !".*".equals(pattern) && !".+".equals(pattern))
                .toList();
        if (usefulPatterns.isEmpty()) {
            return text != null && !text.isBlank();
        }
        for (String pattern : usefulPatterns) {
            if (!matches(pattern, text)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(String pattern, String text) {
        String safeText = text == null ? "" : text;
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
                    .matcher(safeText)
                    .find();
        } catch (PatternSyntaxException ignored) {
            return safeText.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        }
    }

    public record Options(
            Path casesPath,
            Path chunksPath,
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId,
            int topK
    ) {
        public Options {
            topK = Math.max(1, topK);
        }

        public static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < (args == null ? 0 : args.length); i++) {
                String key = args[i];
                if (!key.startsWith("--")) {
                    continue;
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    values.put(key.substring(2), "true");
                    continue;
                }
                values.put(key.substring(2), args[++i]);
            }
            return new Options(
                    Path.of(required(values, "cases")),
                    Path.of(required(values, "chunks")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/sag/generated/runs")),
                    required(values, "run-id"),
                    values.getOrDefault("started-at", java.time.Instant.now().toString()),
                    values.getOrDefault("harness-id", "sag-style-fast-mode"),
                    required(values, "dataset-id"),
                    intValue(values.get("top-k"), 3)
            );
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }

        private static int intValue(String value, int fallback) {
            try {
                return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }
}
