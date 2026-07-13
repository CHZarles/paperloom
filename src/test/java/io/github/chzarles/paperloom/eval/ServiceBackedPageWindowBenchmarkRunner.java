package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.service.PaperRetrievalService;
import io.github.chzarles.paperloom.service.RetrievalBudget;
import io.github.chzarles.paperloom.entity.SearchResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ServiceBackedPageWindowBenchmarkRunner {

    private static final String ROUTE = "PAGE_WINDOW_LEDGER";

    private final ServiceBackedPageWindowHarness harness;

    public ServiceBackedPageWindowBenchmarkRunner(ServiceBackedPageWindowHarness harness) {
        this.harness = harness;
    }

    public Path run(Options options) throws IOException {
        List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(options.casesPath());
        List<RagBenchmarkActual> actuals = new ArrayList<>();
        List<RagBenchmarkVerdict> verdicts = new ArrayList<>();
        Map<String, List<PaperPageInspection>> inspectionsByCase = new LinkedHashMap<>();
        Map<String, List<SearchResult>> candidateHitsByCase = new LinkedHashMap<>();

        for (RagBenchmarkCase testCase : cases) {
            ServiceBackedPageWindowHarness.HarnessResult result = harness.run(
                    testCase.query(),
                    options.userId(),
                    options.budget(),
                    scopePaperIds(testCase),
                    options.harnessOptions()
            );
            inspectionsByCase.put(testCase.id(), result.inspections());
            candidateHitsByCase.put(testCase.id(), result.candidateHits());
            String evidenceText = evidenceText(result.inspections());
            boolean passed = evidenceMatches(testCase.requiredEvidenceRegex(), evidenceText);
            actuals.add(new RagBenchmarkActual(
                    ROUTE,
                    evidenceText,
                    Map.of(),
                    diagnostics(testCase, result)
            ));
            verdicts.add(new RagBenchmarkVerdict(
                    testCase.id(),
                    passed,
                    passed ? List.of() : List.of("REQUIRED_EVIDENCE_MISSING"),
                    passed ? List.of() : List.of("FALSE_NEGATIVE")
            ));
        }

        Map<String, Double> metrics = PaperEvidenceHitScorer.scoreWindowEvidence(
                cases,
                inspectionsByCase,
                1,
                options.harnessOptions().topK()
        );
        metrics = new LinkedHashMap<>(metrics);
        metrics.putAll(PaperEvidenceHitScorer.scoreAllChunkEvidence(
                cases,
                candidateHitsByCase,
                "candidate"
        ));
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

    private List<String> scopePaperIds(RagBenchmarkCase testCase) {
        LinkedHashSet<String> paperIds = new LinkedHashSet<>();
        if (testCase.scope() != null) {
            paperIds.addAll(testCase.scope().paperIds());
        }
        paperIds.addAll(testCase.expectedPaperIds());
        return paperIds.stream()
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .toList();
    }

    private Map<String, Object> diagnostics(RagBenchmarkCase testCase,
                                            ServiceBackedPageWindowHarness.HarnessResult result) {
        PaperRetrievalService.RetrievalDiagnostics retrievalDiagnostics = result.firstStage() == null
                ? null
                : result.firstStage().diagnostics();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("scannedCount", retrievalDiagnostics == null ? 0 : retrievalDiagnostics.scannedCount());
        diagnostics.put("acceptedEvidenceCount", result.ledger().evidence().size());
        diagnostics.put("sourceCount", result.ledger().sourceSet().size());
        diagnostics.put("candidateChunkCount", result.candidateHits().size());
        diagnostics.put("candidateEvidenceHit", evidenceMatches(
                testCase.requiredEvidenceRegex(),
                candidateText(result.candidateHits())
        ));
        diagnostics.put("locatorQuery", result.locatorQuery());
        diagnostics.put("queryExpansions", result.queryExpansions());
        diagnostics.put("candidateSource", result.candidateSource());
        diagnostics.put("windowPageKeys", result.windows().stream()
                .map(PaperPageWindow::pageKeys)
                .toList());
        diagnostics.put("inspectedChunkCount", result.inspections().stream()
                .mapToInt(inspection -> inspection.chunks().size())
                .sum());
        return diagnostics;
    }

    private String candidateText(List<SearchResult> candidateHits) {
        return (candidateHits == null ? List.<SearchResult>of() : candidateHits).stream()
                .map(this::chunkText)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private String chunkText(SearchResult chunk) {
        if (chunk == null) {
            return "";
        }
        String text = chunk.getMatchedChunkText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        return chunk.getTextContent() == null ? "" : chunk.getTextContent();
    }

    private String evidenceText(List<PaperPageInspection> inspections) {
        return (inspections == null ? List.<PaperPageInspection>of() : inspections).stream()
                .map(PaperPageInspection::text)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private boolean evidenceMatches(List<String> patterns, String text) {
        List<String> usefulPatterns = usefulPatterns(patterns);
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

    private List<String> usefulPatterns(List<String> patterns) {
        return (patterns == null ? List.<String>of() : patterns).stream()
                .map(pattern -> pattern == null ? "" : pattern.trim())
                .filter(pattern -> !pattern.isBlank())
                .filter(pattern -> !".".equals(pattern) && !".*".equals(pattern) && !".+".equals(pattern))
                .toList();
    }

    private boolean matches(String pattern, String text) {
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
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId,
            String userId,
            RetrievalBudget budget,
            ServiceBackedPageWindowHarness.Options harnessOptions
    ) {
        public Options {
            budget = budget == null ? RetrievalBudget.forQa() : budget;
            harnessOptions = harnessOptions == null ? ServiceBackedPageWindowHarness.Options.defaults() : harnessOptions;
        }
    }
}
