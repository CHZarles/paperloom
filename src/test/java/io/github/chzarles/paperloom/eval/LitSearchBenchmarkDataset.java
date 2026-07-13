package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LitSearchBenchmarkDataset {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LitSearchBenchmarkDataset() {
    }

    public static List<LitSearchBenchmarkCase> load(Path path) throws IOException {
        List<LitSearchBenchmarkCase> cases = new ArrayList<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            cases.add(OBJECT_MAPPER.readValue(line, LitSearchBenchmarkCase.class));
        }
        return cases;
    }
}
