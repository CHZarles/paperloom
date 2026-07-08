package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Path;

public final class GoldenRunTraceLoader {

    private final YAMLMapper yamlMapper = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public GoldenDatasetSchema.RunTrace load(Path path) throws IOException {
        return yamlMapper.readValue(path.toFile(), GoldenDatasetSchema.RunTrace.class);
    }
}
