package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ReadingToolOutputMapper {

    public Map<String, Object> paperCard(PaperCandidate candidate, String paperHandle, int ordinal) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("ordinal", ordinal);
        card.put("paperHandle", paperHandle);
        card.put("title", candidate.title());
        card.put("authors", candidate.authors());
        card.put("year", candidate.publicationYear());
        card.put("venue", candidate.venue());
        card.put("preview", candidate.abstractPreview());
        return card;
    }

    public Map<String, Object> locationCard(ReadingLocationCandidate candidate, String paperHandle, int ordinal) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("ordinal", ordinal);
        card.put("paperHandle", paperHandle);
        card.put("locationRef", candidate.locationRef());
        card.put("locationType", candidate.locationType() == null ? "" : candidate.locationType().name());
        card.put("pageNumber", candidate.pageNumber());
        card.put("pageEndNumber", candidate.pageEndNumber());
        card.put("sectionTitle", candidate.sectionTitle());
        card.put("preview", candidate.preview());
        return card;
    }
}
