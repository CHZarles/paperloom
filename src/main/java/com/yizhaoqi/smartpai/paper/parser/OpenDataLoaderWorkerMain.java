package com.yizhaoqi.smartpai.paper.parser;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

public final class OpenDataLoaderWorkerMain {

    private OpenDataLoaderWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected inputPdf, outputFolder, readingOrder, hybrid, imageOutput");
        }

        Config config = new Config();
        config.setOutputFolder(args[1]);
        config.setGenerateJSON(true);
        config.setGenerateMarkdown(false);
        config.setGenerateHtml(false);
        config.setGenerateText(false);
        config.setGeneratePDF(false);
        config.setImageOutput(args[4]);
        config.setHybrid(args[3]);
        config.setReadingOrder(args[2]);

        try {
            OpenDataLoaderPDF.processFile(args[0], config);
        } finally {
            OpenDataLoaderPDF.shutdown();
        }
    }
}
