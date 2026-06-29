package com.yizhaoqi.smartpai.paper.parser;

import org.opendataloader.pdf.api.Config;

import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
interface OpenDataLoaderWorkerCommandFactory {

    List<String> buildCommand(Path inputPdf, Config config);
}
