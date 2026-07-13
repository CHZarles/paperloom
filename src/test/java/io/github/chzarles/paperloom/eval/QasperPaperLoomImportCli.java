package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.PaperLoomApplication;
import io.github.chzarles.paperloom.eval.repository.EvalChunkRepository;
import io.github.chzarles.paperloom.eval.repository.EvalPaperRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QasperPaperLoomImportCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QasperPaperLoomImportCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            runCommand(args);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static void runCommand(String[] args) throws Exception {
        Options options = Options.parse(args);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PaperLoomApplication.class)
                .web(WebApplicationType.NONE)
                .run(springStartupArgs())) {
            List<PaperPageChunk> chunks = loadChunks(options.chunksPath());
            chunks = limitPapers(chunks, options.limitPapers());
            QasperPaperLoomImporter importer = new QasperPaperLoomImporter(
                    context.getBean(EvalPaperRepository.class),
                    context.getBean(EvalChunkRepository.class),
                    context.getBean(EvalCorpusIndexService.class)
            );
            QasperPaperLoomImporter.Options importOptions = new QasperPaperLoomImporter.Options(
                    options.evalSplit()
            );
            QasperPaperLoomImporter.ImportSummary summary = importer.importChunks(chunks, importOptions);
            System.out.println("importedPapers=" + summary.paperCount());
            System.out.println("importedChunks=" + summary.chunkCount());
            if (options.ragCasesPath() != null && options.casesOutputPath() != null) {
                List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(options.ragCasesPath());
                List<RagBenchmarkCase> rewritten = QasperPaperLoomImporter.rewriteCasesToImportedPaperIds(
                        cases,
                        importOptions,
                        rawPaperIds(chunks)
                );
                writeCases(options.casesOutputPath(), rewritten);
                System.out.println("serviceCases=" + rewritten.size());
            }
        }
    }

    static String[] springStartupArgs() {
        return new String[]{
                "--elasticsearch.init.enabled=false",
                "--spring.kafka.listener.auto-startup=false",
                "--admin.bootstrap.enabled=false",
                "--paper.bootstrap.enabled=false"
        };
    }

    private static List<PaperPageChunk> loadChunks(Path chunksPath) throws Exception {
        List<PaperPageChunk> chunks = new ArrayList<>();
        for (String rawLine : Files.readAllLines(chunksPath)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            chunks.add(OBJECT_MAPPER.readValue(line, PaperPageChunk.class));
        }
        return chunks;
    }

    private static List<PaperPageChunk> limitPapers(List<PaperPageChunk> chunks, int limitPapers) {
        if (limitPapers <= 0) {
            return chunks;
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        List<PaperPageChunk> limited = new ArrayList<>();
        for (PaperPageChunk chunk : chunks) {
            if (selected.contains(chunk.paperId()) || selected.size() < limitPapers) {
                selected.add(chunk.paperId());
                limited.add(chunk);
            }
        }
        return limited;
    }

    private static Set<String> rawPaperIds(List<PaperPageChunk> chunks) {
        Set<String> paperIds = new LinkedHashSet<>();
        for (PaperPageChunk chunk : chunks == null ? List.<PaperPageChunk>of() : chunks) {
            if (chunk.paperId() != null && !chunk.paperId().isBlank()) {
                String paperId = chunk.paperId();
                paperIds.add(paperId.startsWith("qasper:") ? paperId.substring("qasper:".length()) : paperId);
            }
        }
        return paperIds;
    }

    private static void writeCases(Path output, List<RagBenchmarkCase> cases) throws Exception {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (RagBenchmarkCase testCase : cases) {
            lines.add(OBJECT_MAPPER.writeValueAsString(testCase));
        }
        Files.write(output, lines);
    }

    public record Options(
            Path chunksPath,
            Path ragCasesPath,
            Path casesOutputPath,
            RetrievalCorpus retrievalCorpus,
            int limitPapers,
            String evalSplit
    ) {
        static Options parse(String[] args) {
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
                    Path.of(required(values, "chunks")),
                    optionalPath(values.get("rag-cases")),
                    optionalPath(values.get("cases-output")),
                    requiredCorpus(values, RetrievalCorpus.EVAL_QASPER),
                    Integer.parseInt(values.getOrDefault("limit-papers", "0")),
                    values.getOrDefault("eval-split", "dev")
            );
        }

        private static Path optionalPath(String value) {
            return value == null || value.isBlank() ? null : Path.of(value);
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }

        private static RetrievalCorpus requiredCorpus(Map<String, String> values, RetrievalCorpus expected) {
            String value = required(values, "retrieval-corpus");
            RetrievalCorpus corpus;
            try {
                corpus = RetrievalCorpus.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("--retrieval-corpus must be " + expected);
            }
            if (corpus != expected) {
                throw new IllegalArgumentException("--retrieval-corpus must be " + expected);
            }
            return corpus;
        }
    }
}
