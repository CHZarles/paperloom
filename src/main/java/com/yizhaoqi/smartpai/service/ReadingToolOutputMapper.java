package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
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

    public Map<String, Object> listedLocationCard(PaperLocation location, String paperHandle, int ordinal) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("ordinal", ordinal);
        card.put("paperHandle", paperHandle);
        card.put("locationRef", location.getLocationRef());
        card.put("locationType", location.getLocationType() == null ? "" : location.getLocationType().name());
        card.put("pageNumber", location.getPageNumber());
        card.put("pageEndNumber", location.getPageEndNumber());
        card.put("sectionTitle", location.getSectionTitle());
        card.put("label", locationLabel(location));
        return card;
    }

    private String locationLabel(PaperLocation location) {
        if (location.getLocationType() == PaperLocationType.PAGE && location.getPageNumber() != null) {
            return "Page " + location.getPageNumber();
        }
        if (!SearchText.isBlank(location.getSectionTitle())) {
            return location.getSectionTitle();
        }
        if (location.getLocationType() != null && location.getPageNumber() != null) {
            return location.getLocationType().name() + " on page " + location.getPageNumber();
        }
        return location.getLocationType() == null ? "Location" : location.getLocationType().name();
    }
}
