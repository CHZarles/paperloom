package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class QasperPageWindowDatasetWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QasperPageWindowDatasetWriter() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        write(
                options.qasperJson(),
                options.ragCasesOutput(),
                options.chunksOutput(),
                options.pageCasesOutput(),
                options.maxCases()
        );
    }

    public static void write(Path qasperJson,
                             Path ragCasesOutput,
                             Path chunksOutput,
                             Path pageCasesOutput,
                             int maxCases) throws Exception {
        List<RagBenchmarkCase> ragCases = new QasperBenchmarkConverter().convert(qasperJson, maxCases);
        writeJsonl(ragCasesOutput, ragCases.stream()
                .map(QasperPageWindowDatasetWriter::toJson)
                .toList());
        List<PaperPageChunk> chunks = chunksForCasePapers(qasperJson, ragCases);
        writeJsonl(chunksOutput, chunks.stream()
                .map(QasperPageWindowDatasetWriter::toJson)
                .toList());
        PaperPageLocationCaseGenerator.write(ragCasesOutput, chunksOutput, pageCasesOutput);
    }

    private static List<PaperPageChunk> chunksForCasePapers(Path qasperJson,
                                                            List<RagBenchmarkCase> ragCases) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(qasperJson.toFile());
        Set<String> paperIds = ragCases.stream()
                .flatMap(testCase -> testCase.expectedPaperIds().stream())
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PaperPageChunk> chunks = new ArrayList<>();
        for (String paperId : paperIds) {
            JsonNode paper = root.path(paperId);
            if (paper.isMissingNode()) {
                continue;
            }
            chunks.addAll(chunksForPaper(paperId, paper));
        }
        return chunks;
    }

    private static List<PaperPageChunk> chunksForPaper(String paperId, JsonNode paper) {
        List<PaperPageChunk> chunks = new ArrayList<>();
        String title = paper.path("title").asText(paperId);
        int pageNumber = 1;
        int chunkId = 1;
        for (JsonNode paragraph : paper.path("abstract")) {
            String text = clean(paragraph.asText(""));
            if (!text.isBlank()) {
                chunks.add(chunk(paperId, title, pageNumber++, chunkId++, "Abstract", text));
            }
        }
        for (JsonNode section : paper.path("full_text")) {
            String sectionTitle = section.path("section_name").asText("Full Text");
            for (JsonNode paragraph : section.path("paragraphs")) {
                String text = clean(paragraph.asText(""));
                if (!text.isBlank()) {
                    chunks.add(chunk(paperId, title, pageNumber++, chunkId++, sectionTitle, text));
                }
            }
        }
        return chunks;
    }

    private static PaperPageChunk chunk(String paperId,
                                        String title,
                                        int pageNumber,
                                        int chunkId,
                                        String sectionTitle,
                                        String text) {
        return new PaperPageChunk(
                paperId,
                title,
                "qasper:" + paperId + ".json",
                pageNumber,
                chunkId,
                sectionTitle,
                "TEXT",
                null,
                null,
                text
        );
    }

    private static void writeJsonl(Path output, List<String> lines) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, lines);
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    record Options(
            Path qasperJson,
            Path ragCasesOutput,
            Path chunksOutput,
            Path pageCasesOutput,
            int maxCases
    ) {
        private static Options parse(String[] args) {
            Map<String, String> values = new java.util.LinkedHashMap<>();
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
                    Path.of(required(values, "qasper-json")),
                    Path.of(required(values, "rag-cases-output")),
                    Path.of(required(values, "chunks-output")),
                    Path.of(required(values, "page-cases-output")),
                    Integer.parseInt(values.getOrDefault("max-cases", "0"))
            );
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }
    }
}
