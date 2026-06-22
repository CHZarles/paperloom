package com.yizhaoqi.smartpai.paper.parser;

import java.io.InputStream;

public interface PaperPdfParser {

    ParsedPaper parse(InputStream pdfInputStream, String originalFilename);

    String providerName();

    String providerVersion();
}
