package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitSearchParquetFallbackTest {

    @Test
    void manifestTracksAllCorpusCleanFullShards() throws Exception {
        Path manifest = Path.of("eval/rag/litsearch/parquet_manifest.tsv");
        List<String> lines = Files.readAllLines(manifest).stream()
                .filter(line -> !line.isBlank())
                .toList();

        assertEquals("config\tsplit\tshard\tfilename\turl", lines.get(0));
        assertEquals(7, lines.size());

        Set<String> filenames = lines.stream().skip(1)
                .map(line -> line.split("\t"))
                .peek(columns -> assertEquals(5, columns.length))
                .peek(columns -> assertEquals("corpus_clean", columns[0]))
                .peek(columns -> assertEquals("full", columns[1]))
                .map(columns -> columns[3])
                .collect(Collectors.toSet());

        assertEquals(6, filenames.size());
        assertTrue(filenames.contains("full-00000-of-00006.parquet"));
        assertTrue(filenames.contains("full-00005-of-00006.parquet"));
        assertTrue(lines.stream().skip(1)
                .allMatch(line -> line.contains("https://huggingface.co/datasets/princeton-nlp/LitSearch/resolve/main/corpus_clean/")));
    }

    @Test
    void parquetConverterWritesLitSearchPaperDocumentShape() throws Exception {
        String script = Files.readString(Path.of("eval/rag/litsearch/parquet_to_jsonl.py"));

        assertTrue(script.contains("\"paperId\""));
        assertTrue(script.contains("\"title\""));
        assertTrue(script.contains("\"abstractText\""));
        assertTrue(script.contains("\"fullPaperText\""));
        assertTrue(script.contains("\"citationCorpusIds\""));
        assertTrue(script.contains("pyarrow"));
    }
}
