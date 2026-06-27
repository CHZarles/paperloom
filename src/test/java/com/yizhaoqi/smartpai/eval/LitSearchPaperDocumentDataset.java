package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

public final class LitSearchPaperDocumentDataset {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LitSearchPaperDocumentDataset() {
    }

    public static List<LitSearchPaperDocument> load(Path path) throws IOException {
        List<LitSearchPaperDocument> papers = new ArrayList<>();
        forEach(path, papers::add);
        return papers;
    }

    public static void forEach(Path path, Visitor visitor) throws IOException {
        forEachUntil(path, paper -> {
            visitor.accept(paper);
            return true;
        });
    }

    public static void forEachUntil(Path path, StoppingVisitor visitor) throws IOException {
        forEachUntil(path, 0, 0, visitor);
    }

    public static void forEachUntil(Path path, int startOffset, int maxPapers, StoppingVisitor visitor) throws IOException {
        int effectiveStartOffset = Math.max(0, startOffset);
        int rowIndex = 0;
        int visited = 0;
        try (Stream<String> lines = Files.lines(path)) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next().trim();
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (rowIndex++ < effectiveStartOffset) {
                    continue;
                }
                if (maxPapers > 0 && visited >= maxPapers) {
                    break;
                }
                visited++;
                if (!visitor.accept(OBJECT_MAPPER.readValue(line, LitSearchPaperDocument.class))) {
                    break;
                }
            }
        }
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(LitSearchPaperDocument paper) throws IOException;
    }

    @FunctionalInterface
    public interface StoppingVisitor {
        boolean accept(LitSearchPaperDocument paper) throws IOException;
    }
}
