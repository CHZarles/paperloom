package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.entity.SearchResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class PaperPageLocationCaseGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PaperPageLocationCaseGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        write(options.ragCasesPath(), options.chunksPath(), options.outputPath());
    }

    public static List<PaperPageLocatorCase> write(Path ragCasesPath,
                                                   Path chunksPath,
                                                   Path outputPath) throws Exception {
        List<PaperPageLocatorCase> generated = generate(
                RagBenchmarkDataset.load(ragCasesPath),
                PaperPageChunkDataset.loadSearchResults(chunksPath)
        );
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (PaperPageLocatorCase testCase : generated) {
            lines.add(OBJECT_MAPPER.writeValueAsString(testCase));
        }
        Files.write(outputPath, lines);
        return generated;
    }

    public static List<PaperPageLocatorCase> generate(List<RagBenchmarkCase> cases,
                                                      List<SearchResult> chunks) {
        List<PaperPageLocatorCase> generated = new ArrayList<>();
        for (RagBenchmarkCase testCase : cases == null ? List.<RagBenchmarkCase>of() : cases) {
            Set<String> allowedPaperIds = allowedPaperIds(testCase);
            List<Pattern> evidencePatterns = usefulEvidencePatterns(testCase.requiredEvidenceRegex());
            if (allowedPaperIds.isEmpty() || evidencePatterns.isEmpty()) {
                continue;
            }
            LinkedHashSet<String> goldPageKeys = new LinkedHashSet<>();
            for (SearchResult chunk : chunks == null ? List.<SearchResult>of() : chunks) {
                if (chunk == null
                        || chunk.getPaperId() == null
                        || chunk.getPageNumber() == null
                        || !allowedPaperIds.contains(chunk.getPaperId())) {
                    continue;
                }
                String text = firstNonBlank(chunk.getMatchedChunkText(), chunk.getTextContent());
                if (matchesAny(evidencePatterns, text)) {
                    goldPageKeys.add(chunk.getPaperId() + ":" + chunk.getPageNumber());
                }
            }
            if (!goldPageKeys.isEmpty()) {
                generated.add(new PaperPageLocatorCase(
                        testCase.id(),
                        testCase.query(),
                        List.copyOf(goldPageKeys)
                ));
            }
        }
        return generated;
    }

    private static Set<String> allowedPaperIds(RagBenchmarkCase testCase) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (testCase != null) {
            ids.addAll(testCase.expectedPaperIds());
            ids.addAll(testCase.scope().paperIds());
        }
        ids.removeIf(value -> value == null || value.isBlank());
        return ids;
    }

    private static List<Pattern> usefulEvidencePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns == null ? List.<String>of() : patterns) {
            String trimmed = pattern == null ? "" : pattern.trim();
            if (!isUsefulPattern(trimmed)) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException ignored) {
                // Bad benchmark regex should not prevent other useful evidence rules from generating page labels.
            }
        }
        return compiled;
    }

    private static boolean isUsefulPattern(String pattern) {
        return !pattern.isBlank()
                && !".".equals(pattern)
                && !".*".equals(pattern)
                && !".+".equals(pattern);
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    public record Options(
            Path ragCasesPath,
            Path chunksPath,
            Path outputPath
    ) {
        private static Options parse(String[] args) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }
            return new Options(
                    Path.of(required(values, "rag-cases")),
                    Path.of(required(values, "chunks")),
                    Path.of(required(values, "output"))
            );
        }

        private static String required(java.util.Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }
    }
}
