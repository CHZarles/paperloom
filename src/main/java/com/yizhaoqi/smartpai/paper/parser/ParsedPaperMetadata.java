package com.yizhaoqi.smartpai.paper.parser;

public record ParsedPaperMetadata(
        String originalFilename,
        String title,
        String authors,
        Integer pageCount,
        String creationDate,
        String modificationDate
) {
}
