package com.yizhaoqi.smartpai.paper.parser;

import org.opendataloader.pdf.api.Config;

import java.nio.file.Path;
import java.util.List;

final class DefaultOpenDataLoaderWorkerCommandFactory implements OpenDataLoaderWorkerCommandFactory {

    @Override
    public List<String> buildCommand(Path inputPdf, Config config) {
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) {
            throw new PaperParsingException("Cannot start OpenDataLoader worker because java.class.path is empty");
        }
        return List.of(
                javaBinary(),
                "-cp",
                classpath,
                OpenDataLoaderWorkerMain.class.getName(),
                inputPdf.toString(),
                config.getOutputFolder(),
                safe(config.getReadingOrder(), Config.READING_ORDER_XYCUT),
                safe(config.getHybrid(), Config.HYBRID_OFF),
                safe(config.getImageOutput(), Config.IMAGE_OUTPUT_OFF)
        );
    }

    private String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
