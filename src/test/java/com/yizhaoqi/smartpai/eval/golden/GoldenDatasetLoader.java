package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GoldenDatasetLoader {

    private final YAMLMapper yamlMapper;

    public GoldenDatasetLoader() {
        this(YAMLMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build());
    }

    GoldenDatasetLoader(YAMLMapper yamlMapper) {
        this.yamlMapper = yamlMapper;
    }

    public GoldenDatasetSchema.GoldenDataset load(Path manifestPath) throws IOException {
        Path absoluteManifest = manifestPath.toAbsolutePath().normalize();
        Path root = absoluteManifest.getParent();
        GoldenDatasetSchema.DatasetManifest manifest = yamlMapper.readValue(
                absoluteManifest.toFile(),
                GoldenDatasetSchema.DatasetManifest.class
        );

        List<GoldenDatasetSchema.PaperPackFile> packs = new ArrayList<>();
        List<GoldenDatasetSchema.PaperRecord> paperRecords = new ArrayList<>();
        List<GoldenDatasetSchema.EvidenceAnchor> anchors = new ArrayList<>();
        for (GoldenDatasetSchema.ManifestRef ref : manifest.paper_packs()) {
            GoldenDatasetSchema.PaperPackFile pack = yamlMapper.readValue(
                    root.resolve(ref.path()).normalize().toFile(),
                    GoldenDatasetSchema.PaperPackFile.class
            );
            packs.add(pack);
            paperRecords.addAll(pack.paper_records());
            anchors.addAll(pack.evidence_anchors());
        }

        List<GoldenDatasetSchema.GoldenCase> cases = new ArrayList<>();
        for (GoldenDatasetSchema.ManifestRef ref : manifest.case_files()) {
            GoldenDatasetSchema.CaseFile caseFile = yamlMapper.readValue(
                    root.resolve(ref.path()).normalize().toFile(),
                    GoldenDatasetSchema.CaseFile.class
            );
            cases.addAll(caseFile.cases());
        }

        return new GoldenDatasetSchema.GoldenDataset(manifest, packs, paperRecords, anchors, cases);
    }
}
