package io.github.chzarles.paperloom.service;

import java.util.List;
import java.util.Map;

public record AnswerEnvelope(
        AnswerType answerType,
        String answer,
        List<Map<String, Object>> evidenceBasedClaims,
        List<Map<String, Object>> stateClaims,
        List<String> limitations,
        List<String> nonEvidenceNotes,
        List<String> missingFields,
        String reason
) {
    public AnswerEnvelope {
        answerType = answerType == null ? AnswerType.NON_EVIDENCE : answerType;
        answer = answer == null ? "" : answer.trim();
        evidenceBasedClaims = evidenceBasedClaims == null ? List.of() : List.copyOf(evidenceBasedClaims);
        stateClaims = stateClaims == null ? List.of() : List.copyOf(stateClaims);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        nonEvidenceNotes = nonEvidenceNotes == null ? List.of() : List.copyOf(nonEvidenceNotes);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        reason = reason == null ? "" : reason.trim();
    }
}
