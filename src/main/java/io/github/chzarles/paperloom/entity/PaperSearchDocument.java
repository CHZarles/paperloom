package io.github.chzarles.paperloom.entity;

import io.github.chzarles.paperloom.model.Paper;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch paper-level metadata document for literature-search candidate retrieval.
 */
@Data
public class PaperSearchDocument {

    private String id;
    private String paperId;
    private String paperTitle;
    private String originalFilename;
    private String abstractText;
    private String authors;
    private String venue;
    private Integer year;
    private String doi;
    private String arxivId;
    private String searchText;
    private String userId;
    private String orgTag;
    private boolean isPublic;

    public static PaperSearchDocument from(Paper paper, String userId, String orgTag, boolean isPublic) {
        if (paper == null) {
            throw new IllegalArgumentException("paper is required");
        }
        PaperSearchDocument document = new PaperSearchDocument();
        document.setId(paper.getPaperId());
        document.setPaperId(paper.getPaperId());
        document.setPaperTitle(paper.getPaperTitle());
        document.setOriginalFilename(paper.getOriginalFilename());
        document.setAbstractText(paper.getAbstractText());
        document.setAuthors(paper.getAuthors());
        document.setVenue(paper.getVenue());
        document.setYear(paper.getPublicationYear());
        document.setDoi(paper.getDoi());
        document.setArxivId(paper.getArxivId());
        document.setSearchText(buildSearchText(paper));
        document.setUserId(userId);
        document.setOrgTag(orgTag);
        document.setPublic(isPublic);
        return document;
    }

    private static String buildSearchText(Paper paper) {
        List<String> parts = new ArrayList<>();
        appendPart(parts, "title", paper.getPaperTitle());
        appendPart(parts, "filename", paper.getOriginalFilename());
        appendPart(parts, "abstract", paper.getAbstractText());
        appendPart(parts, "authors", paper.getAuthors());
        appendPart(parts, "venue", paper.getVenue());
        appendPart(parts, "year", paper.getPublicationYear() == null ? null : paper.getPublicationYear().toString());
        appendPart(parts, "doi", paper.getDoi());
        appendPart(parts, "arxiv", paper.getArxivId());
        return String.join("\n", parts);
    }

    private static void appendPart(List<String> parts, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        parts.add(label + ": " + value.trim());
    }
}
