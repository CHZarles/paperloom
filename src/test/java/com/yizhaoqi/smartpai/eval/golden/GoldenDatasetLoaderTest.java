package com.yizhaoqi.smartpai.eval.golden;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoldenDatasetLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsManifestPacksAnchorsAndCases() throws Exception {
        Files.createDirectories(tempDir.resolve("paper-packs"));
        Files.createDirectories(tempDir.resolve("cases"));
        Files.writeString(tempDir.resolve("manifest.yaml"), """
                schema_version: harness-golden-data/v1
                dataset_id: seed_smoke
                splits:
                  - id: seed
                    purpose: Smoke coverage
                paper_packs:
                  - id: pack_a
                    path: paper-packs/pack-a.yaml
                case_files:
                  - path: cases/cases.yaml
                scoring_profile: trace_obligation_v1
                """);
        Files.writeString(tempDir.resolve("paper-packs/pack-a.yaml"), """
                id: pack_a
                title: Pack A
                purpose: Loader test
                papers:
                  - paper_id: paper_a
                    role: target
                paper_records:
                  - paper_id: paper_a
                    identity:
                      title: Paper A
                      year: 2024
                evidence_anchors:
                  - anchor_id: anchor_a
                    paper_id: paper_a
                    role: supports
                    element:
                      type: paragraph
                      page: 1
                    selector:
                      exact_text: "Alpha evidence."
                """);
        Files.writeString(tempDir.resolve("cases/cases.yaml"), """
                cases:
                  - id: case_a
                    schema_version: harness-golden-data/v1
                    split: seed
                    question:
                      language: en
                      text: What does Paper A say?
                    capability_tags:
                      - precision_fact_extraction
                    paper_pack_ids:
                      - pack_a
                    corpus_scope:
                      retrieval_corpus: EVAL_HARNESS_SEED
                      required_paper_ids:
                        - paper_a
                    expected_result:
                      kind: answered
                      answer_type: exact_fact
                    expected_intent: {}
                    gold_evidence:
                      required_anchor_ids:
                        - anchor_a
                    answer_contract:
                      type: exact_fact
                    required_trace:
                      obligations: []
                """);

        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader().load(tempDir.resolve("manifest.yaml"));

        assertEquals("seed_smoke", dataset.manifest().dataset_id());
        assertEquals(1, dataset.paperPacks().size());
        assertEquals(1, dataset.paperRecords().size());
        assertEquals(1, dataset.evidenceAnchors().size());
        assertEquals(1, dataset.cases().size());
        assertEquals("case_a", dataset.cases().get(0).id());
    }
}
