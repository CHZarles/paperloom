package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductLaunchReadinessCliTest {

    @Test
    void parsesDefaultLaunchReadinessOptions() {
        ProductLaunchReadinessCli.Options options = ProductLaunchReadinessCli.Options.parse(new String[]{
                "--started-at", "2026-07-07T14:05:00Z"
        });

        assertEquals(Path.of("eval/rag/runs"), options.runsRoot());
        assertEquals("product-launch-readiness", options.harnessId());
        assertEquals("product-launch-readiness", options.datasetId());
        assertEquals("2026-07-07T140500Z-product-launch-readiness-product-launch-readiness", options.runId());
        assertEquals(Path.of(".env"), options.envPath());
        assertEquals(Path.of("eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl"), options.manifestPath());
        assertEquals(Path.of("eval/rag/product-reading-live-launch-smoke-cases.jsonl"), options.liveSmokeCasesPath());
        assertEquals(Path.of("data/traces/product-react"), options.traceRoot());
        assertEquals(Path.of("eval/rag/product-reading-launch-trace-cases.jsonl"), options.traceCasesPath());
    }
}
