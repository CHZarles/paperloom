package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LitSearchCorpusCoverageVerifier {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LitSearchCorpusCoverageVerifier() {
    }

    public static void main(String[] args) throws Exception {
        CoverageReport report = verify(Options.parse(args));
        System.out.println("complete=" + report.complete());
        System.out.println("contiguousRows=" + report.contiguousRows());
        System.out.println("nextResumeOffset=" + report.nextResumeOffset());
        System.out.println("validPages=" + report.validPages());
        for (String problem : report.problems()) {
            System.out.println("problem=" + problem);
        }
    }

    public static CoverageReport verify(Options options) throws Exception {
        List<String> problems = new ArrayList<>();
        int validPages = 0;
        int contiguousRows = 0;
        int endExclusive = options.startOffset() + options.totalRows();

        for (int offset = options.startOffset(); offset < endExclusive; offset += options.pageSize()) {
            int expectedRows = Math.min(options.pageSize(), endExclusive - offset);
            Path page = options.inputDir().resolve(String.format(Locale.ROOT, options.filenameFormat(), offset));
            if (!Files.exists(page) || Files.size(page) == 0) {
                problems.add("missing page at offset " + offset + ": " + page);
                break;
            }

            JsonNode rows = OBJECT_MAPPER.readTree(page.toFile()).path("rows");
            if (!rows.isArray()) {
                problems.add("page at offset " + offset + " has no rows array: " + page);
                break;
            }
            if (rows.size() != expectedRows) {
                problems.add("page at offset " + offset + " has " + rows.size()
                        + " rows; expected " + expectedRows + ": " + page);
                break;
            }
            String rowIndexProblem = firstRowIndexProblem(rows, offset);
            if (rowIndexProblem != null) {
                problems.add(rowIndexProblem + ": " + page);
                break;
            }

            validPages++;
            contiguousRows += expectedRows;
        }

        return new CoverageReport(
                contiguousRows == options.totalRows(),
                contiguousRows,
                options.startOffset() + contiguousRows,
                validPages,
                problems
        );
    }

    private static String firstRowIndexProblem(JsonNode rows, int offset) {
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            int expectedRowIndex = offset + index;
            if (!row.has("row_idx")) {
                return "row_idx missing at offset " + offset + ", position " + index
                        + "; expected " + expectedRowIndex;
            }
            int actualRowIndex = row.path("row_idx").asInt(Integer.MIN_VALUE);
            if (actualRowIndex != expectedRowIndex) {
                return "row_idx mismatch at offset " + offset + ", position " + index
                        + "; expected " + expectedRowIndex + ", got " + actualRowIndex;
            }
        }
        return null;
    }

    public record Options(
            Path inputDir,
            String filenameFormat,
            int startOffset,
            int totalRows,
            int pageSize
    ) {
        private static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
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
                    Path.of(values.getOrDefault("input-dir", "eval/rag/litsearch/raw")),
                    values.getOrDefault("filename-format", "litsearch-corpus-clean-page-%05d.json"),
                    Integer.parseInt(values.getOrDefault("start-offset", "0")),
                    Integer.parseInt(values.getOrDefault("total-rows", "64183")),
                    Integer.parseInt(values.getOrDefault("page-size", "100"))
            );
        }
    }

    public record CoverageReport(
            boolean complete,
            int contiguousRows,
            int nextResumeOffset,
            int validPages,
            List<String> problems
    ) {
        public CoverageReport {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }
}
