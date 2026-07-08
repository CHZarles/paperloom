package com.yizhaoqi.smartpai.eval.golden;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetValidatorTest {

    @Test
    void rejectsAnsweredCaseWithoutRequiredAnchor() {
        GoldenDatasetSchema.GoldenDataset dataset = datasetWithCase(new GoldenDatasetSchema.GoldenCase(
                "case_a",
                GoldenDatasetSchema.GOLDEN_SCHEMA_VERSION,
                "seed",
                new GoldenDatasetSchema.Question("en", "What is the value?"),
                List.of("precision_fact_extraction"),
                "easy",
                List.of(),
                new GoldenDatasetSchema.CorpusScope("EVAL_HARNESS_SEED", List.of("paper_a"), List.of(), List.of()),
                new GoldenDatasetSchema.ExpectedResult("answered", "exact_fact"),
                Map.of(),
                Map.of(),
                new GoldenDatasetSchema.GoldEvidence(List.of(), List.of(), List.of()),
                List.of(),
                Map.of("type", "exact_fact"),
                new GoldenDatasetSchema.RequiredTrace(List.of()),
                List.of(),
                new GoldenDatasetSchema.CompatibilityProjection(
                        "METHODOLOGY_REPRODUCTION",
                        "MANUAL_SOURCE_QA",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("paper_a"),
                        true
                )
        ));

        List<String> failures = new GoldenDatasetValidator().validate(dataset);

        assertTrue(failures.stream().anyMatch(failure -> failure.contains("ANSWERED_CASE_REQUIRES_ANCHOR")));
    }

    @Test
    void rejectsClaimSupportThatReferencesMissingAnchor() {
        GoldenDatasetSchema.GoldenCase testCase = new GoldenDatasetSchema.GoldenCase(
                "case_a",
                GoldenDatasetSchema.GOLDEN_SCHEMA_VERSION,
                "seed",
                new GoldenDatasetSchema.Question("en", "What is the value?"),
                List.of("precision_fact_extraction"),
                "easy",
                List.of(),
                new GoldenDatasetSchema.CorpusScope("EVAL_HARNESS_SEED", List.of("paper_a"), List.of(), List.of()),
                new GoldenDatasetSchema.ExpectedResult("answered", "exact_fact"),
                Map.of(),
                Map.of(),
                new GoldenDatasetSchema.GoldEvidence(List.of("anchor_a"), List.of(), List.of()),
                List.of(new GoldenDatasetSchema.GoldClaim(
                        "claim_a",
                        true,
                        "Claim A",
                        "supported",
                        List.of("missing_anchor"),
                        List.of(),
                        "1",
                        ""
                )),
                Map.of("type", "exact_fact"),
                new GoldenDatasetSchema.RequiredTrace(List.of()),
                List.of(),
                null
        );

        GoldenDatasetSchema.GoldenDataset dataset = datasetWithCase(testCase);

        List<String> failures = new GoldenDatasetValidator().validate(dataset);

        assertTrue(failures.stream().anyMatch(failure -> failure.contains("UNKNOWN_CLAIM_SUPPORT_ANCHOR")));
    }

    @Test
    void requireValidThrowsWithReadableMessage() {
        GoldenDatasetSchema.GoldenDataset dataset = datasetWithCase(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new GoldenDatasetValidator().requireValid(dataset)
        );

        assertTrue(error.getMessage().contains("GOLDEN_DATASET_INVALID"));
    }

    private GoldenDatasetSchema.GoldenDataset datasetWithCase(GoldenDatasetSchema.GoldenCase testCase) {
        GoldenDatasetSchema.DatasetManifest manifest = new GoldenDatasetSchema.DatasetManifest(
                GoldenDatasetSchema.GOLDEN_SCHEMA_VERSION,
                "seed_smoke",
                "Seed Smoke",
                "",
                "2026-07-08",
                List.of(),
                "research/harness-golden-data-strategy.md",
                List.of(new GoldenDatasetSchema.ManifestSplit("seed", "Smoke coverage")),
                List.of(new GoldenDatasetSchema.ManifestRef("pack_a", "paper-packs/pack-a.yaml")),
                List.of(new GoldenDatasetSchema.ManifestRef(null, "cases/seed-smoke.yaml")),
                "trace_obligation_v1",
                List.of()
        );
        GoldenDatasetSchema.EvidenceAnchor anchor = new GoldenDatasetSchema.EvidenceAnchor(
                "anchor_a",
                "paper_a",
                "supports",
                new GoldenDatasetSchema.AnchorElement("paragraph", "Training", 1, "value", null),
                new GoldenDatasetSchema.AnchorSelector("Alpha evidence.", "Alpha"),
                Map.of("value", "1"),
                Map.of("text", "required"),
                List.of()
        );
        List<GoldenDatasetSchema.GoldenCase> cases = testCase == null ? List.of() : List.of(testCase);
        return new GoldenDatasetSchema.GoldenDataset(
                manifest,
                List.of(),
                List.of(),
                List.of(anchor),
                cases
        );
    }
}
