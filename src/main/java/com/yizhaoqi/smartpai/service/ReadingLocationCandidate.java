package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocationType;

import java.util.List;

public record ReadingLocationCandidate(
        String paperId,
        String modelVersion,
        String locationRef,
        PaperLocationType locationType,
        Integer pageNumber,
        Integer pageEndNumber,
        String sectionTitle,
        String readingElementId,
        String preview,
        String matchSource,
        String routingSource,
        List<String> matchedFields,
        List<String> matchedReadingElementIds
) {
    public ReadingLocationCandidate {
        matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
        matchedReadingElementIds = matchedReadingElementIds == null ? List.of() : List.copyOf(matchedReadingElementIds);
    }
}
