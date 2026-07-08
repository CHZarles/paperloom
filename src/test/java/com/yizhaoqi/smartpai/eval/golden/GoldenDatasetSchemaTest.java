package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetSchemaTest {

    @Test
    void mapsManifestYamlIntoSchemaRecord() throws Exception {
        String yaml = """
                schema_version: harness-golden-data/v1
                dataset_id: seed_smoke
                title: Golden Smoke
                splits:
                  - id: seed
                    purpose: Smoke coverage
                paper_packs:
                  - id: transformer_bert_gpt
                    path: paper-packs/transformer-bert-gpt.yaml
                case_files:
                  - path: cases/seed-smoke.yaml
                scoring_profile: trace_obligation_v1
                """;

        GoldenDatasetSchema.DatasetManifest manifest = new YAMLMapper()
                .readValue(yaml, GoldenDatasetSchema.DatasetManifest.class);

        assertEquals("harness-golden-data/v1", manifest.schema_version());
        assertEquals("seed_smoke", manifest.dataset_id());
        assertEquals("seed", manifest.splits().get(0).id());
        assertEquals("transformer_bert_gpt", manifest.paper_packs().get(0).id());
        assertTrue(manifest.compatibility_exports().isEmpty());
    }
}
