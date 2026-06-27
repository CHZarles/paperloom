package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PaperPageChunkDataset {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PaperPageChunkDataset() {
    }

    public static List<SearchResult> loadSearchResults(Path path) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            results.add(toSearchResult(OBJECT_MAPPER.readValue(line, PaperPageChunk.class)));
        }
        return results;
    }

    private static SearchResult toSearchResult(PaperPageChunk chunk) {
        SearchResult result = new SearchResult(
                chunk.paperId(),
                chunk.chunkId(),
                chunk.text(),
                1.0d
        );
        result.setPaperTitle(chunk.paperTitle());
        result.setOriginalFilename(chunk.originalFilename());
        result.setPageNumber(chunk.pageNumber());
        result.setSectionTitle(chunk.sectionTitle());
        result.setSourceKind(chunk.sourceKind());
        result.setTableId(chunk.tableId());
        result.setFigureId(chunk.figureId());
        result.setMatchedChunkText(chunk.text());
        return result;
    }
}
