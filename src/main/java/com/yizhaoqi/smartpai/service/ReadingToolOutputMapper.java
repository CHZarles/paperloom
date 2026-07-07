package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperSection;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    public Map<String, Object> paperOutline(String paperHandle,
                                            Paper paper,
                                            PaperReadingModel model,
                                            List<String> supportedLocationTypes,
                                            Map<String, Object> parserQuality,
                                            List<Map<String, Object>> sections) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("paperHandle", paperHandle);
        card.put("title", paper == null ? "" : paper.getPaperTitle());
        card.put("originalFilename", paper == null ? "" : paper.getOriginalFilename());
        card.put("supportedLocationTypes", supportedLocationTypes == null ? List.of() : supportedLocationTypes);
        card.put("parserQuality", parserQuality == null ? Map.of() : parserQuality);
        card.put("sections", sections == null ? List.of() : sections);
        return card;
    }

    public Map<String, Object> sectionOutlineCard(PaperSection section, PaperLocation sectionLocation) {
        Map<String, Object> card = new LinkedHashMap<>();
        String heading = section == null ? "" : section.getSectionTitle();
        card.put("sectionRef", sectionLocation == null ? "" : sectionLocation.getLocationRef());
        card.put("heading", heading);
        card.put("sectionRole", roleFromHeading(heading));
        card.put("level", section == null ? null : section.getSectionLevel());
        card.put("pageStart", section == null ? null : section.getPageNumberFrom());
        card.put("pageEnd", section == null ? null : section.getPageNumberTo());
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

    private String roleFromHeading(String heading) {
        String normalized = heading == null ? "" : heading.toLowerCase(Locale.ROOT);
        if (normalized.contains("abstract")) {
            return "ABSTRACT";
        }
        if (normalized.contains("related work") || normalized.contains("background")) {
            return "RELATED_WORK";
        }
        if (normalized.contains("introduction")) {
            return "INTRODUCTION";
        }
        if (normalized.contains("method")
                || normalized.contains("approach")
                || normalized.contains("model")
                || normalized.contains("algorithm")
                || normalized.contains("implementation")) {
            return "METHODS";
        }
        if (normalized.contains("result")
                || normalized.contains("experiment")
                || normalized.contains("evaluation")
                || normalized.contains("benchmark")) {
            return "RESULTS";
        }
        if (normalized.contains("discussion") || normalized.contains("analysis")) {
            return "DISCUSSION";
        }
        if (normalized.contains("limitation") || normalized.contains("failure")) {
            return "LIMITATIONS";
        }
        if (normalized.contains("conclusion") || normalized.contains("future")) {
            return "CONCLUSION";
        }
        if (normalized.contains("appendix") || normalized.contains("supplementary")) {
            return "APPENDIX";
        }
        return "OTHER";
    }
}
