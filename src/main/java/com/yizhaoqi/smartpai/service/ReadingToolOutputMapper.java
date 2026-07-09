package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperSection;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReadingToolOutputMapper {

    public Map<String, Object> paperCard(PaperCandidate candidate, String paperHandle, int ordinal) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("ordinal", ordinal);
        card.put("paperId", candidate.paperId());
        card.put("paperHandle", paperHandle);
        card.put("title", candidate.title());
        card.put("originalFilename", "");
        card.put("authors", candidate.authors());
        card.put("year", candidate.publicationYear());
        card.put("venue", candidate.venue());
        card.put("preview", candidate.abstractPreview());
        card.put("matchReasons", candidate.matchReason() == null || candidate.matchReason().isBlank()
                ? List.of()
                : List.of(candidate.matchReason()));
        return card;
    }

    public Map<String, Object> searchPaperCard(Paper paper,
                                               String paperHandle,
                                               int ordinal,
                                               List<String> authors,
                                               List<String> matchReasons,
                                               String preview) {
        Map<String, Object> card = browsedPaperCard(paper, paperHandle, ordinal, authors, matchReasons);
        card.put("preview", preview == null ? "" : preview);
        return card;
    }

    public Map<String, Object> sessionSearchScope(ScopeMode scopeMode,
                                                  String label,
                                                  int readablePaperCount) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("scopeMode", scopeMode == null ? ScopeMode.AUTO_SOURCE.name() : scopeMode.name());
        scope.put("label", label == null ? "" : label);
        scope.put("readablePaperCountKnown", true);
        scope.put("readablePaperCount", Math.max(0, readablePaperCount));
        scope.put("immutable", true);
        return scope;
    }

    public Map<String, Object> browsedPaperCard(Paper paper,
                                                String paperHandle,
                                                int ordinal,
                                                List<String> authors) {
        return browsedPaperCard(paper, paperHandle, ordinal, authors, List.of());
    }

    public Map<String, Object> browsedPaperCard(Paper paper,
                                                String paperHandle,
                                                int ordinal,
                                                List<String> authors,
                                                List<String> matchReasons) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("ordinal", ordinal);
        card.put("paperId", paper == null ? "" : paper.getPaperId());
        card.put("paperHandle", paperHandle);
        card.put("title", paper == null ? "" : paper.getPaperTitle());
        card.put("originalFilename", paper == null ? "" : paper.getOriginalFilename());
        card.put("authors", authors == null ? List.of() : authors);
        card.put("year", paper == null ? null : paper.getPublicationYear());
        card.put("venue", paper == null ? "" : paper.getVenue());
        card.put("matchReasons", matchReasons == null ? List.of() : matchReasons);
        card.put("catalogTopics", List.of());
        card.put("paperTypes", List.of());
        return card;
    }

    public Map<String, Object> identityPaperCard(Paper paper,
                                                 String paperHandle,
                                                 int ordinal,
                                                 List<String> authors,
                                                 List<String> matchReasons) {
        Map<String, Object> card = browsedPaperCard(paper, paperHandle, ordinal, authors);
        card.put("matchReasons", matchReasons == null ? List.of() : matchReasons);
        return card;
    }

    public Map<String, Object> paperBrowseFacets(Map<String, List<Map<String, Object>>> facets) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("years", facets == null ? List.of() : facets.getOrDefault("years", List.of()));
        output.put("authors", facets == null ? List.of() : facets.getOrDefault("authors", List.of()));
        output.put("venues", facets == null ? List.of() : facets.getOrDefault("venues", List.of()));
        output.put("catalogTopics", facets == null ? List.of() : facets.getOrDefault("catalogTopics", List.of()));
        output.put("paperTypes", facets == null ? List.of() : facets.getOrDefault("paperTypes", List.of()));
        return output;
    }

    public Map<String, Object> facetBucket(Object value, int count) {
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("value", value);
        bucket.put("count", Math.max(0, count));
        return bucket;
    }

    public Map<String, Object> locationCard(ReadingLocationCandidate candidate, String paperHandle, int ordinal) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("ordinal", ordinal);
        card.put("paperId", candidate.paperId());
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
        card.put("paperId", location.getPaperId());
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
        card.put("paperId", paper == null ? "" : paper.getPaperId());
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
}
