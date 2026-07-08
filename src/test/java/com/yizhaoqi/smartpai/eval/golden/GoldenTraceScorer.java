package com.yizhaoqi.smartpai.eval.golden;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class GoldenTraceScorer {

    public GoldenDatasetSchema.CaseScore score(GoldenDatasetSchema.GoldenCase testCase,
                                               GoldenDatasetSchema.GoldenDataset dataset,
                                               GoldenDatasetSchema.RunTrace trace) {
        List<String> failures = new ArrayList<>();
        if (testCase == null) {
            return failed("", "CASE_MISSING");
        }
        if (trace == null) {
            return failed(testCase.id(), "TRACE_MISSING");
        }
        if (!GoldenDatasetSchema.RUN_TRACE_SCHEMA_VERSION.equals(trace.schema_version())) {
            failures.add("TRACE_SCHEMA_VERSION_INVALID:" + trace.schema_version());
        }
        if (!testCase.id().equals(trace.case_id())) {
            failures.add("TRACE_CASE_ID_MISMATCH:expected=" + testCase.id() + ",actual=" + trace.case_id());
        }

        Set<String> actualAnchorIds = actualAnchorIds(trace);
        verifyRequiredAnchors(testCase, actualAnchorIds, failures);
        verifyForbiddenAnchors(testCase, actualAnchorIds, failures);
        verifyClaims(testCase, trace, failures);
        verifyAnswerFields(testCase, trace, failures);
        verifyTraceObligations(testCase, trace, actualAnchorIds, failures);
        verifyVerificationPass(testCase, trace, failures);

        Map<String, Object> layerScores = layerScores(testCase, trace, failures);
        return new GoldenDatasetSchema.CaseScore(testCase.id(), failures.isEmpty(), layerScores, failures);
    }

    private GoldenDatasetSchema.CaseScore failed(String caseId, String failure) {
        return new GoldenDatasetSchema.CaseScore(caseId, false, Map.of(), List.of(failure));
    }

    private Set<String> actualAnchorIds(GoldenDatasetSchema.RunTrace trace) {
        if (trace.evidence_ledger() == null) {
            return Set.of();
        }
        return trace.evidence_ledger().items().stream()
                .map(GoldenDatasetSchema.RunEvidenceItem::matched_anchor_id)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
    }

    private void verifyRequiredAnchors(GoldenDatasetSchema.GoldenCase testCase, Set<String> actualAnchorIds, List<String> failures) {
        if (testCase.gold_evidence() == null) {
            failures.add("GOLD_EVIDENCE_MISSING");
            return;
        }
        for (String anchorId : testCase.gold_evidence().required_anchor_ids()) {
            if (!actualAnchorIds.contains(anchorId)) {
                failures.add("REQUIRED_ANCHOR_MISSING:" + anchorId);
            }
        }
    }

    private void verifyForbiddenAnchors(GoldenDatasetSchema.GoldenCase testCase, Set<String> actualAnchorIds, List<String> failures) {
        if (testCase.gold_evidence() == null) {
            return;
        }
        for (String anchorId : testCase.gold_evidence().forbidden_anchor_ids()) {
            if (actualAnchorIds.contains(anchorId)) {
                failures.add("FORBIDDEN_ANCHOR_USED:" + anchorId);
            }
        }
    }

    private void verifyClaims(GoldenDatasetSchema.GoldenCase testCase, GoldenDatasetSchema.RunTrace trace, List<String> failures) {
        String claimGraphText = String.valueOf(trace.claim_graph());
        for (GoldenDatasetSchema.GoldClaim claim : testCase.gold_claims()) {
            if (Boolean.TRUE.equals(claim.required()) && !claimGraphText.contains(claim.claim_id())) {
                failures.add("REQUIRED_CLAIM_MISSING:" + claim.claim_id());
            }
            if (claim.exact_value() != null && !claim.exact_value().isBlank()) {
                String finalAnswerText = String.valueOf(trace.final_answer());
                if (!finalAnswerText.contains(claim.exact_value())) {
                    failures.add("CLAIM_EXACT_VALUE_MISSING:" + claim.claim_id() + ":" + claim.exact_value());
                }
            }
        }
    }

    private void verifyAnswerFields(GoldenDatasetSchema.GoldenCase testCase,
                                    GoldenDatasetSchema.RunTrace trace,
                                    List<String> failures) {
        Object requiredFields = testCase.answer_contract().get("required_fields");
        if (!(requiredFields instanceof Map<?, ?> required)) {
            return;
        }
        Object actualFields = trace.final_answer().get("fields");
        if (!(actualFields instanceof Map<?, ?> actual)) {
            failures.add("FINAL_ANSWER_FIELDS_MISSING");
            return;
        }
        for (Map.Entry<?, ?> entry : required.entrySet()) {
            Object actualValue = actual.get(entry.getKey());
            if (!String.valueOf(entry.getValue()).equals(String.valueOf(actualValue))) {
                failures.add("ANSWER_FIELD_MISMATCH:" + entry.getKey() + ":expected=" + entry.getValue() + ",actual=" + actualValue);
            }
        }
    }

    private void verifyTraceObligations(GoldenDatasetSchema.GoldenCase testCase,
                                        GoldenDatasetSchema.RunTrace trace,
                                        Set<String> actualAnchorIds,
                                        List<String> failures) {
        List<String> satisfied = trace.verification_pass() == null
                ? List.of()
                : trace.verification_pass().satisfied_trace_obligation_ids();
        List<String> failed = trace.verification_pass() == null
                ? List.of()
                : trace.verification_pass().failed_trace_obligation_ids();
        String traceText = String.valueOf(trace);
        for (GoldenDatasetSchema.TraceObligation obligation : obligations(testCase)) {
            if (failed.contains(obligation.id())) {
                failures.add("TRACE_OBLIGATION_FAILED:" + obligation.id());
            }
            if (satisfied.contains(obligation.id())) {
                continue;
            }
            for (String anchorId : obligation.must_include_anchor_ids()) {
                if (!actualAnchorIds.contains(anchorId)) {
                    failures.add("TRACE_OBLIGATION_ANCHOR_MISSING:" + obligation.id() + ":" + anchorId);
                }
            }
            for (String requiredText : obligation.must_include()) {
                if (!traceText.toLowerCase().contains(requiredText.toLowerCase())) {
                    failures.add("TRACE_OBLIGATION_TEXT_MISSING:" + obligation.id() + ":" + requiredText);
                }
            }
            if (!obligation.id().isBlank()
                    && obligation.must_include().isEmpty()
                    && obligation.must_include_anchor_ids().isEmpty()) {
                failures.add("TRACE_OBLIGATION_NOT_SATISFIED:" + obligation.id());
            }
        }
    }

    private List<GoldenDatasetSchema.TraceObligation> obligations(GoldenDatasetSchema.GoldenCase testCase) {
        return testCase.required_trace() == null ? List.of() : testCase.required_trace().obligations();
    }

    private void verifyVerificationPass(GoldenDatasetSchema.GoldenCase testCase,
                                        GoldenDatasetSchema.RunTrace trace,
                                        List<String> failures) {
        GoldenDatasetSchema.VerificationPass pass = trace.verification_pass();
        if (pass == null) {
            failures.add("VERIFICATION_PASS_MISSING");
            return;
        }
        boolean expectedAnswered = testCase.expected_result() != null
                && "answered".equals(testCase.expected_result().kind());
        if (expectedAnswered && pass.unsupported_claim_count() != null && pass.unsupported_claim_count() > 0) {
            failures.add("UNSUPPORTED_CLAIMS_PRESENT:" + pass.unsupported_claim_count());
        }
        if (expectedAnswered && !pass.missing_required_anchor_ids().isEmpty()) {
            failures.add("VERIFICATION_REPORTS_MISSING_ANCHORS:" + String.join(",", pass.missing_required_anchor_ids()));
        }
    }

    private Map<String, Object> layerScores(GoldenDatasetSchema.GoldenCase testCase,
                                            GoldenDatasetSchema.RunTrace trace,
                                            List<String> failures) {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("retrieval", Map.of(
                "required_anchor_count", testCase.gold_evidence() == null ? 0 : testCase.gold_evidence().required_anchor_ids().size(),
                "actual_anchor_count", actualAnchorIds(trace).size()
        ));
        scores.put("claim", Map.of(
                "required_claim_count", testCase.gold_claims().stream().filter(claim -> Boolean.TRUE.equals(claim.required())).count(),
                "unsupported_claim_count", trace.verification_pass() == null ? 0 : trace.verification_pass().unsupported_claim_count()
        ));
        scores.put("trace", Map.of(
                "required_obligation_count", obligations(testCase).size(),
                "failure_count", failures.size()
        ));
        return scores;
    }
}
