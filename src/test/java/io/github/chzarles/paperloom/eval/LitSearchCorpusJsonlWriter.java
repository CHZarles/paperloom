package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LitSearchCorpusJsonlWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LitSearchCorpusJsonlWriter() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        write(options.inputs(), options.output(), options.maxPapers());
    }

    public static void write(List<Path> inputs, Path output, int maxPapers) throws Exception {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        LitSearchBenchmarkConverter converter = new LitSearchBenchmarkConverter();
        int written = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            for (Path input : inputs == null ? List.<Path>of() : inputs) {
                int remaining = maxPapers <= 0 ? 0 : maxPapers - written;
                if (maxPapers > 0 && remaining <= 0) {
                    break;
                }
                for (LitSearchPaperDocument paper : converter.convertCorpus(input, remaining)) {
                    writer.write(OBJECT_MAPPER.writeValueAsString(paper));
                    writer.newLine();
                    written++;
                    if (maxPapers > 0 && written >= maxPapers) {
                        break;
                    }
                }
            }
        }
    }

    private record Options(
            List<Path> inputs,
            Path output,
            int maxPapers
    ) {
        private static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            List<Path> inputs = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    inputs.add(Path.of(arg));
                    continue;
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }
            String output = values.get("output");
            if (output == null || output.isBlank()) {
                throw new IllegalArgumentException("Missing --output");
            }
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("At least one input page is required");
            }
            return new Options(
                    List.copyOf(inputs),
                    Path.of(output),
                    Integer.parseInt(values.getOrDefault("max-papers", "0"))
            );
        }
    }
}
