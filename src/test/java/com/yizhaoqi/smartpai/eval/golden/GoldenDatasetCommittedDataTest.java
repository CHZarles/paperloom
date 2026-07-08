package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetCommittedDataTest {

    @Test
    void committedGoldenSmokeDatasetLoadsAndValidates() throws Exception {
        Path manifest = Path.of("research/golden-data/manifest.yaml");

        assertTrue(Files.exists(manifest), "golden manifest must exist");

        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader().load(manifest);
        new GoldenDatasetValidator().requireValid(dataset);

        assertEquals("harness_golden_seed_smoke", dataset.manifest().dataset_id());
        assertEquals(1, dataset.paperPacks().size());
        assertEquals(7, dataset.cases().size());
        assertTrue(dataset.evidenceAnchors().stream()
                .anyMatch(anchor -> "transformer_adam_training_params_span".equals(anchor.anchor_id())));
        assertTrue(dataset.cases().stream()
                .anyMatch(testCase -> "bert_vs_transformer_comparison_001".equals(testCase.id())));
        assertTrue(dataset.cases().stream()
                .anyMatch(testCase -> "transformer_to_bert_genealogy_001".equals(testCase.id())));
        assertTrue(dataset.cases().stream()
                .anyMatch(testCase -> "adam_beta2_conflict_001".equals(testCase.id())));
    }

    @Test
    void committedArtifactContractsNameAllHarnessV0Artifacts() throws Exception {
        JsonNode root = new YAMLMapper().readTree(Path.of("research/golden-data/artifact-contracts.yaml").toFile());

        assertEquals("research-harness-artifacts/v1", root.path("schema_version").asText());
        Set<String> artifactIds = new HashSet<>();
        for (JsonNode artifact : root.path("artifacts")) {
            artifactIds.add(artifact.path("id").asText());
            assertTrue(artifact.path("required_fields").size() > 0,
                    () -> artifact.path("id").asText() + " must declare required fields");
            assertTrue(artifact.path("invariants").size() > 0,
                    () -> artifact.path("id").asText() + " must declare invariants");
        }
        assertEquals(Set.of(
                "IntentFrame",
                "RetrievalPlan",
                "EvidenceLedger",
                "ClaimGraph",
                "ReasoningArtifact",
                "VerificationPass",
                "ResearchAnswer"
        ), artifactIds);
    }

    @Test
    void committedSeed60SelectionHasExpectedCoverageCounts() throws Exception {
        JsonNode root = new YAMLMapper().readTree(Path.of("research/golden-data/seed-60-selection.yaml").toFile());
        JsonNode selected = root.path("selected_questions");

        assertEquals("harness-seed-selection/v1", root.path("schema_version").asText());
        assertEquals(60, selected.size());
        assertEquals(44, countBySplit(selected, "seed_by_paradigm"));
        assertEquals(16, countBySplit(selected, "stress"));
        assertEquals(22, distinctParadigmsInSeed(selected).size());
    }

    @Test
    void committedPaperPackManifestNamesRequiredAndDistractorPapers() throws Exception {
        JsonNode root = new YAMLMapper().readTree(
                Path.of("research/golden-data/paper-packs/seed-60-paper-pack-manifest.yaml").toFile()
        );
        JsonNode packs = root.path("packs");

        assertEquals("harness-paper-pack-manifest/v1", root.path("schema_version").asText());
        assertTrue(packs.size() >= 10);
        int requiredPaperCount = 0;
        int distractorPaperCount = 0;
        for (JsonNode pack : packs) {
            assertTrue(pack.path("required_papers").size() > 0,
                    () -> pack.path("id").asText() + " must name required papers");
            assertTrue(pack.path("distractor_papers").size() > 0,
                    () -> pack.path("id").asText() + " must name distractor papers");
            requiredPaperCount += pack.path("required_papers").size();
            distractorPaperCount += pack.path("distractor_papers").size();
        }
        assertTrue(requiredPaperCount >= 60, "seed paper-pack manifest should start near the 60-100 paper target");
        assertTrue(distractorPaperCount >= 20, "seed paper-pack manifest should include hard distractors");
    }

    private long countBySplit(JsonNode selected, String split) {
        long count = 0;
        for (JsonNode entry : selected) {
            if (split.equals(entry.path("split").asText())) {
                count++;
            }
        }
        return count;
    }

    private Set<Integer> distinctParadigmsInSeed(JsonNode selected) {
        Set<Integer> paradigms = new HashSet<>();
        for (JsonNode entry : selected) {
            if ("seed_by_paradigm".equals(entry.path("split").asText())) {
                paradigms.add(entry.path("source_paradigm").asInt());
            }
        }
        return paradigms;
    }
}
