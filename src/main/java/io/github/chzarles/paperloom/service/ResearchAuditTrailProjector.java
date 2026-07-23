package io.github.chzarles.paperloom.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ResearchAuditTrailProjector {

    public ResearchAuditTrail project(String answerStatus,
                                      Map<String, Map<String, Object>> referenceMappings,
                                      List<Map<String, Object>> researchEvents) {
        Map<String, Map<String, Object>> safeReferences = referenceMappings == null ? Map.of() : referenceMappings;
        List<Map<String, Object>> safeEvents = researchEvents == null ? List.of() : researchEvents;

        List<ResearchAuditTrail.Step> steps = steps(safeEvents);
        LinkedHashMap<String, EvidenceDraft> evidence = new LinkedHashMap<>();
        for (Map<String, Object> event : safeEvents) {
            collectEventEvidence(event, evidence);
        }
        collectCitedEvidence(safeReferences, evidence);

        List<ResearchAuditTrail.Evidence> evidenceRows = evidence.values().stream()
                .map(EvidenceDraft::toEvidence)
                .filter(row -> !row.paperId().isBlank()
                        || !row.paperTitle().isBlank()
                        || !row.locationRef().isBlank()
                        || !row.evidenceRef().isBlank()
                        || !row.sourceQuoteRef().isBlank())
                .toList();
        ResearchAuditTrail trail = new ResearchAuditTrail(
                ResearchAuditTrail.SCHEMA_VERSION,
                new ResearchAuditTrail.Answer(clean(answerStatus), citationRefs(safeReferences)),
                steps,
                evidenceRows,
                diagnostics(evidenceRows)
        );
        return trail.hasContent() ? trail : null;
    }

    private List<ResearchAuditTrail.Step> steps(List<Map<String, Object>> events) {
        List<ResearchAuditTrail.Step> steps = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> event : events) {
            String eventType = firstNonBlank(event.get("eventType"), event.get("type"));
            String tool = stringValue(event.get("tool"));
            String kind = !tool.isBlank() ? tool : eventType;
            if (kind.isBlank()) {
                continue;
            }
            Map<String, Object> input = objectMap(event.get("input"));
            Map<String, Object> output = objectMap(event.get("output"));
            List<String> evidenceRefs = strings(event.get("evidenceIds"));
            evidenceRefs = merge(evidenceRefs, idsFromItems(output.get("evidence"), "evidenceId", "evidence_id"));
            steps.add(new ResearchAuditTrail.Step(
                    firstNonBlank(event.get("sequence"), event.get("id"), "step-" + (++index)),
                    kind,
                    stepStatus(eventType, event),
                    firstNonBlank(input.get("query"), input.get("query_text")),
                    paperIds(input, output),
                    locationRefs(input, output),
                    evidenceRefs,
                    integerValue(event.get("durationMs")),
                    stringValue(event.get("message"))
            ));
        }
        return List.copyOf(steps);
    }

    private String stepStatus(String eventType, Map<String, Object> event) {
        String status = stringValue(event.get("status"));
        if (!status.isBlank()) {
            return normalizeStatus(status);
        }
        if ("job_failed".equals(eventType)) {
            return "failed";
        }
        if ("tool_started".equals(eventType) || "model_call_started".equals(eventType)) {
            return "running";
        }
        return "completed";
    }

    private String normalizeStatus(String status) {
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if ("success".equals(normalized)) {
            return "completed";
        }
        if ("executing".equals(normalized)) {
            return "running";
        }
        return normalized;
    }

    private List<String> paperIds(Map<String, Object> input, Map<String, Object> output) {
        List<String> ids = new ArrayList<>(strings(input.get("paperIds")));
        ids = merge(ids, idsFromItems(output.get("papers"), "paperId", "paper_id"));
        ids = merge(ids, idsFromItems(output.get("locations"), "paperId", "paper_id"));
        ids = merge(ids, idsFromItems(output.get("evidence"), "paperId", "paper_id"));
        return ids;
    }

    private List<String> locationRefs(Map<String, Object> input, Map<String, Object> output) {
        List<String> refs = new ArrayList<>(strings(input.get("locationRefs")));
        refs = merge(refs, idsFromItems(output.get("locations"), "locationRef", "location_ref"));
        refs = merge(refs, idsFromItems(output.get("evidence"), "locationRef", "location_ref"));
        return refs;
    }

    private void collectEventEvidence(Map<String, Object> event, LinkedHashMap<String, EvidenceDraft> evidence) {
        Map<String, Object> output = objectMap(event.get("output"));
        String tool = stringValue(event.get("tool"));
        if ("search_paper_candidates".equals(tool)) {
            for (Map<String, Object> paper : mapList(output.get("papers"))) {
                EvidenceDraft draft = new EvidenceDraft();
                draft.status = "candidate";
                draft.paperId = firstNonBlank(paper.get("paperId"), paper.get("paper_id"));
                draft.paperTitle = firstNonBlank(paper.get("title"), paper.get("paperTitle"));
                mergeEvidence(evidence, draft);
            }
        }
        if ("find_reading_locations".equals(tool)) {
            for (Map<String, Object> location : mapList(output.get("locations"))) {
                EvidenceDraft draft = new EvidenceDraft();
                draft.status = "candidate";
                draft.paperId = firstNonBlank(location.get("paperId"), location.get("paper_id"));
                draft.paperTitle = firstNonBlank(location.get("title"), location.get("paperTitle"));
                draft.locationRef = firstNonBlank(location.get("locationRef"), location.get("location_ref"));
                draft.pageNumber = integerValue(firstNonBlank(location.get("page"), location.get("pageNumber")));
                draft.sectionTitle = firstNonBlank(location.get("section"), location.get("sectionTitle"));
                mergeEvidence(evidence, draft);
            }
        }
        if ("read_locations".equals(tool)) {
            for (Map<String, Object> item : mapList(output.get("evidence"))) {
                mergeEvidence(evidence, evidenceFromReadItem(item));
            }
        }
    }

    private EvidenceDraft evidenceFromReadItem(Map<String, Object> item) {
        EvidenceDraft draft = new EvidenceDraft();
        draft.status = "read";
        draft.evidenceRef = firstNonBlank(item.get("evidenceId"), item.get("evidence_id"), item.get("evidenceRef"));
        draft.sourceQuoteRef = firstNonBlank(item.get("sourceQuoteRef"), item.get("source_quote_ref"));
        draft.paperId = firstNonBlank(item.get("paperId"), item.get("paper_id"));
        draft.paperTitle = firstNonBlank(item.get("paperTitle"), item.get("title"));
        draft.originalFilename = firstNonBlank(item.get("originalFilename"), item.get("original_filename"));
        draft.locationRef = firstNonBlank(item.get("locationRef"), item.get("location_ref"), item.get("location"));
        draft.pageNumber = integerValue(firstNonBlank(item.get("pageNumber"), item.get("page")));
        draft.pageEndNumber = integerValue(firstNonBlank(item.get("pageEndNumber"), item.get("page_end")));
        draft.sectionTitle = firstNonBlank(item.get("sectionTitle"), item.get("section"));
        draft.sourceKind = sourceKind(firstNonBlank(item.get("sourceKind"), item.get("source_kind"), item.get("element_type")));
        draft.content = firstNonBlank(item.get("content"), item.get("quote"), item.get("span_text"));
        draft.bboxJson = firstNonBlank(item.get("bboxJson"), item.get("bbox_json"), item.get("bbox_or_cell_ref"));
        draft.pageScreenshotAvailable = booleanValue(firstNonBlank(
                item.get("pageScreenshotAvailable"), item.get("page_screenshot_available")));
        draft.tableId = firstNonBlank(item.get("tableId"), item.get("table_id"));
        draft.figureId = firstNonBlank(item.get("figureId"), item.get("figure_id"));
        draft.formulaId = firstNonBlank(item.get("formulaId"), item.get("formula_id"));
        draft.tableScreenshotAvailable = booleanValue(firstNonBlank(
                item.get("tableScreenshotAvailable"), item.get("table_screenshot_available")));
        draft.figureScreenshotAvailable = booleanValue(firstNonBlank(
                item.get("figureScreenshotAvailable"), item.get("figure_screenshot_available")));
        draft.sourceType = firstNonBlank(item.get("sourceType"), item.get("source_type"), "PDF");
        draft.evidenceAssetLevel = firstNonBlank(item.get("evidenceAssetLevel"), item.get("evidence_asset_level"));
        draft.pdfEvidenceAvailable = booleanValue(firstNonBlank(
                item.get("pdfEvidenceAvailable"), item.get("pdf_evidence_available")));
        draft.assetWarnings = strings(firstPresent(item.get("assetWarnings"), item.get("asset_warnings")));
        return draft;
    }

    private void collectCitedEvidence(Map<String, Map<String, Object>> references,
                                      LinkedHashMap<String, EvidenceDraft> evidence) {
        for (Map.Entry<String, Map<String, Object>> entry : references.entrySet()) {
            Map<String, Object> item = entry.getValue();
            EvidenceDraft draft = evidenceFromReference(item);
            Integer referenceNumber = integerValue(entry.getKey());
            if (referenceNumber == null) {
                referenceNumber = integerValue(item.get("referenceNumber"));
            }
            draft.referenceNumber = referenceNumber;
            draft.status = "cited";
            draft.citationRef = firstNonBlank(item.get("citationRef"),
                    referenceNumber == null ? "" : "[" + referenceNumber + "]");
            mergeEvidence(evidence, draft);
        }
    }

    private EvidenceDraft evidenceFromReference(Map<String, Object> item) {
        EvidenceDraft draft = evidenceFromReadItem(item);
        draft.evidenceRef = firstNonBlank(draft.evidenceRef, item.get("evidenceRef"));
        draft.content = firstNonBlank(
                item.get("content"),
                item.get("matchedChunkText"),
                item.get("evidenceSnippet"),
                item.get("anchorText"),
                draft.content
        );
        return draft;
    }

    private void mergeEvidence(LinkedHashMap<String, EvidenceDraft> evidence, EvidenceDraft incoming) {
        String key = incoming.key();
        if (key.isBlank()) {
            return;
        }
        evidence.compute(key, (ignored, existing) -> {
            if (existing == null) {
                incoming.auditEvidenceId = key;
                return incoming;
            }
            existing.merge(incoming);
            existing.auditEvidenceId = key;
            return existing;
        });
    }

    private List<String> citationRefs(Map<String, Map<String, Object>> references) {
        List<String> refs = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : references.entrySet()) {
            String ref = firstNonBlank(entry.getValue().get("citationRef"), "[" + entry.getKey() + "]");
            if (!ref.isBlank()) {
                refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }

    private ResearchAuditTrail.Diagnostics diagnostics(List<ResearchAuditTrail.Evidence> evidence) {
        Set<String> searchedPaperIds = new LinkedHashSet<>();
        Set<String> readLocationRefs = new LinkedHashSet<>();
        int readEvidenceCount = 0;
        int citedEvidenceCount = 0;
        int uncitedReadEvidenceCount = 0;
        int visualEvidenceAvailableCount = 0;
        for (ResearchAuditTrail.Evidence row : evidence) {
            if (!row.paperId().isBlank()) {
                searchedPaperIds.add(row.paperId());
            }
            if ("read".equals(row.status()) || "cited".equals(row.status())) {
                if (!row.locationRef().isBlank()) {
                    readLocationRefs.add(row.locationRef());
                }
                readEvidenceCount += 1;
            }
            if ("cited".equals(row.status())) {
                citedEvidenceCount += 1;
            }
            if ("read".equals(row.status())) {
                uncitedReadEvidenceCount += 1;
            }
            if (Boolean.TRUE.equals(row.pageScreenshotAvailable())
                    || Boolean.TRUE.equals(row.tableScreenshotAvailable())
                    || Boolean.TRUE.equals(row.figureScreenshotAvailable())
                    || Boolean.TRUE.equals(row.pdfEvidenceAvailable())) {
                visualEvidenceAvailableCount += 1;
            }
        }
        return new ResearchAuditTrail.Diagnostics(
                searchedPaperIds.size(),
                readLocationRefs.size(),
                readEvidenceCount,
                citedEvidenceCount,
                uncitedReadEvidenceCount,
                visualEvidenceAvailableCount
        );
    }

    private List<String> idsFromItems(Object value, String... keys) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> item : mapList(value)) {
            for (String key : keys) {
                String id = stringValue(item.get(key));
                if (!id.isBlank()) {
                    ids.add(id);
                    break;
                }
            }
        }
        return unique(ids);
    }

    private List<String> merge(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>(left);
        merged.addAll(right);
        return unique(merged);
    }

    private List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String text = stringValue(item);
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return unique(result);
        }
        String text = stringValue(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    private List<String> unique(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String text = clean(value);
            if (!text.isBlank()) {
                unique.add(text);
            }
        }
        return List.copyOf(unique);
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> mapped = objectMap(item);
            if (!mapped.isEmpty()) {
                result.add(mapped);
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, mapValue) -> {
            if (key != null) {
                result.put(String.valueOf(key), mapValue);
            }
        });
        return result;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return null;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            return value;
        }
        return null;
    }

    private String sourceKind(String value) {
        String normalized = stringValue(value).toUpperCase(Locale.ROOT);
        if ("TABLE".equals(normalized)) {
            return "TABLE";
        }
        if ("FIGURE".equals(normalized) || "CHART".equals(normalized)) {
            return "FIGURE";
        }
        if ("FORMULA".equals(normalized)) {
            return "FORMULA";
        }
        return "TEXT";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class EvidenceDraft {
        String auditEvidenceId = "";
        String status = "candidate";
        String sourceQuoteRef = "";
        String evidenceRef = "";
        String citationRef = "";
        Integer referenceNumber;
        String paperId = "";
        String paperTitle = "";
        String originalFilename = "";
        String locationRef = "";
        Integer pageNumber;
        Integer pageEndNumber;
        String sectionTitle = "";
        String sourceKind = "TEXT";
        String content = "";
        String bboxJson = "";
        Boolean pageScreenshotAvailable;
        String tableId = "";
        String figureId = "";
        String formulaId = "";
        Boolean tableScreenshotAvailable;
        Boolean figureScreenshotAvailable;
        String sourceType = "PDF";
        String evidenceAssetLevel = "";
        Boolean pdfEvidenceAvailable;
        List<String> assetWarnings = List.of();

        String key() {
            return firstNonBlankStatic(
                    sourceQuoteRef,
                    evidenceRef,
                    locationRef.isBlank() ? "" : "location:" + locationRef,
                    paperId.isBlank() ? "" : "paper:" + paperId
            );
        }

        void merge(EvidenceDraft incoming) {
            if (statusRank(incoming.status) >= statusRank(status)) {
                status = incoming.status;
            }
            sourceQuoteRef = firstNonBlankStatic(incoming.sourceQuoteRef, sourceQuoteRef);
            evidenceRef = firstNonBlankStatic(incoming.evidenceRef, evidenceRef);
            citationRef = firstNonBlankStatic(incoming.citationRef, citationRef);
            referenceNumber = incoming.referenceNumber != null ? incoming.referenceNumber : referenceNumber;
            paperId = firstNonBlankStatic(incoming.paperId, paperId);
            paperTitle = firstNonBlankStatic(incoming.paperTitle, paperTitle);
            originalFilename = firstNonBlankStatic(incoming.originalFilename, originalFilename);
            locationRef = firstNonBlankStatic(incoming.locationRef, locationRef);
            pageNumber = incoming.pageNumber != null ? incoming.pageNumber : pageNumber;
            pageEndNumber = incoming.pageEndNumber != null ? incoming.pageEndNumber : pageEndNumber;
            sectionTitle = firstNonBlankStatic(incoming.sectionTitle, sectionTitle);
            sourceKind = firstNonBlankStatic(incoming.sourceKind, sourceKind);
            content = firstNonBlankStatic(incoming.content, content);
            bboxJson = firstNonBlankStatic(incoming.bboxJson, bboxJson);
            pageScreenshotAvailable = incoming.pageScreenshotAvailable != null
                    ? incoming.pageScreenshotAvailable
                    : pageScreenshotAvailable;
            tableId = firstNonBlankStatic(incoming.tableId, tableId);
            figureId = firstNonBlankStatic(incoming.figureId, figureId);
            formulaId = firstNonBlankStatic(incoming.formulaId, formulaId);
            tableScreenshotAvailable = incoming.tableScreenshotAvailable != null
                    ? incoming.tableScreenshotAvailable
                    : tableScreenshotAvailable;
            figureScreenshotAvailable = incoming.figureScreenshotAvailable != null
                    ? incoming.figureScreenshotAvailable
                    : figureScreenshotAvailable;
            sourceType = firstNonBlankStatic(incoming.sourceType, sourceType);
            evidenceAssetLevel = firstNonBlankStatic(incoming.evidenceAssetLevel, evidenceAssetLevel);
            pdfEvidenceAvailable = incoming.pdfEvidenceAvailable != null
                    ? incoming.pdfEvidenceAvailable
                    : pdfEvidenceAvailable;
            if (!incoming.assetWarnings.isEmpty()) {
                assetWarnings = incoming.assetWarnings;
            }
        }

        ResearchAuditTrail.Evidence toEvidence() {
            return new ResearchAuditTrail.Evidence(
                    auditEvidenceId,
                    status,
                    sourceQuoteRef,
                    evidenceRef,
                    citationRef,
                    referenceNumber,
                    paperId,
                    paperTitle,
                    originalFilename,
                    locationRef,
                    pageNumber,
                    pageEndNumber,
                    sectionTitle,
                    sourceKind,
                    content,
                    bboxJson,
                    pageScreenshotAvailable,
                    tableId,
                    figureId,
                    formulaId,
                    tableScreenshotAvailable,
                    figureScreenshotAvailable,
                    sourceType,
                    evidenceAssetLevel,
                    pdfEvidenceAvailable,
                    assetWarnings
            );
        }

        private static int statusRank(String status) {
            if ("cited".equals(status)) {
                return 3;
            }
            if ("read".equals(status)) {
                return 2;
            }
            return 1;
        }

        private static String firstNonBlankStatic(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return "";
        }
    }
}
