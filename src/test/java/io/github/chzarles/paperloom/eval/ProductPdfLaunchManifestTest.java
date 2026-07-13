package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPdfLaunchManifestTest {

    @Test
    void launchManifestContainsThirtyUniqueExistingRealPdfs() throws Exception {
        Path manifest = Path.of("eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl");

        List<ProductPdfParserSmokeRunner.ManifestCase> cases = ProductPdfParserSmokeRunner.loadManifest(manifest);

        assertEquals(30, cases.size());
        Set<String> paths = new HashSet<>();
        for (ProductPdfParserSmokeRunner.ManifestCase testCase : cases) {
            assertTrue(paths.add(testCase.path()), "duplicate PDF path: " + testCase.path());
            assertTrue(testCase.path().startsWith("data/"), "launch PDFs must come from data/: " + testCase.path());
            assertTrue(testCase.path().endsWith(".pdf"), "launch case must point to a PDF: " + testCase.path());
            assertTrue(Files.isRegularFile(Path.of(testCase.path())), "missing launch PDF: " + testCase.path());
            assertTrue(testCase.minChunks() >= 1, "launch PDF should require chunks: " + testCase.id());
            assertTrue(testCase.minPages() >= 1, "launch PDF should require page-aware chunks: " + testCase.id());
            assertTrue(testCase.isParserArtifactRequired(), "launch PDF should require parser artifacts: " + testCase.id());
        }
    }
}
