package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public record ResearchAuditTrail(
        String schemaVersion,
        Answer answer,
        List<Step> steps,
        List<Evidence> evidence,
        Diagnostics diagnostics
) {
    public static final String SCHEMA_VERSION = "research-audit-trail/v1";

    public ResearchAuditTrail {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        answer = answer == null ? new Answer("", List.of()) : answer;
        steps = steps == null ? List.of() : List.copyOf(steps);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        diagnostics = diagnostics == null ? Diagnostics.empty() : diagnostics;
    }

    @JsonIgnore
    public boolean hasContent() {
        return !steps.isEmpty() || !evidence.isEmpty() || !answer.citationRefs().isEmpty();
    }

    public record Answer(String status, List<String> citationRefs) {
        public Answer {
            status = status == null ? "" : status.trim();
            citationRefs = citationRefs == null ? List.of() : List.copyOf(citationRefs);
        }
    }

    public record Step(
            String stepId,
            String kind,
            String status,
            String query,
            List<String> paperIds,
            List<String> locationRefs,
            List<String> evidenceRefs,
            Integer durationMs,
            String message
    ) {
        public Step {
            stepId = stepId == null ? "" : stepId.trim();
            kind = kind == null ? "" : kind.trim();
            status = status == null ? "" : status.trim();
            query = query == null ? "" : query.trim();
            paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
            locationRefs = locationRefs == null ? List.of() : List.copyOf(locationRefs);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            message = message == null ? "" : message.trim();
        }
    }

    public record Evidence(
            String auditEvidenceId,
            String status,
            String sourceQuoteRef,
            String evidenceRef,
            String citationRef,
            Integer referenceNumber,
            String paperId,
            String paperTitle,
            String originalFilename,
            String locationRef,
            Integer pageNumber,
            Integer pageEndNumber,
            String sectionTitle,
            String sourceKind,
            String content,
            String bboxJson,
            Boolean pageScreenshotAvailable,
            String tableId,
            String figureId,
            String formulaId,
            Boolean tableScreenshotAvailable,
            Boolean figureScreenshotAvailable,
            String sourceType,
            String evidenceAssetLevel,
            Boolean pdfEvidenceAvailable,
            List<String> assetWarnings
    ) {
        public Evidence {
            auditEvidenceId = auditEvidenceId == null ? "" : auditEvidenceId.trim();
            status = status == null || status.isBlank() ? "candidate" : status.trim();
            sourceQuoteRef = clean(sourceQuoteRef);
            evidenceRef = clean(evidenceRef);
            citationRef = clean(citationRef);
            paperId = clean(paperId);
            paperTitle = clean(paperTitle);
            originalFilename = clean(originalFilename);
            locationRef = clean(locationRef);
            sectionTitle = clean(sectionTitle);
            sourceKind = sourceKind == null || sourceKind.isBlank() ? "TEXT" : sourceKind.trim();
            content = clean(content);
            bboxJson = clean(bboxJson);
            tableId = clean(tableId);
            figureId = clean(figureId);
            formulaId = clean(formulaId);
            sourceType = sourceType == null || sourceType.isBlank() ? "PDF" : sourceType.trim();
            evidenceAssetLevel = clean(evidenceAssetLevel);
            assetWarnings = assetWarnings == null ? List.of() : List.copyOf(assetWarnings);
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record Diagnostics(
            int searchedPaperCount,
            int readLocationCount,
            int readEvidenceCount,
            int citedEvidenceCount,
            int uncitedReadEvidenceCount,
            int visualEvidenceAvailableCount
    ) {
        static Diagnostics empty() {
            return new Diagnostics(0, 0, 0, 0, 0, 0);
        }
    }
}
