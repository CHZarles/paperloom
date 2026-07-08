package com.yizhaoqi.smartpai.eval.golden;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GoldenDatasetValidator {

    public List<String> validate(GoldenDatasetSchema.GoldenDataset dataset) {
        List<String> failures = new ArrayList<>();
        if (dataset == null) {
            return List.of("DATASET_NULL");
        }
        validateManifest(dataset, failures);
        Set<String> anchorIds = collectAnchorIds(dataset, failures);
        Set<String> paperIds = collectPaperIds(dataset, failures);
        Set<String> packIds = collectPackIds(dataset, failures);
        if (dataset.cases().isEmpty()) {
            failures.add("CASES_MISSING");
        }
        validateCases(dataset, anchorIds, paperIds, packIds, failures);
        return List.copyOf(failures);
    }

    public void requireValid(GoldenDatasetSchema.GoldenDataset dataset) {
        List<String> failures = validate(dataset);
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException("GOLDEN_DATASET_INVALID:" + String.join("|", failures));
        }
    }

    private void validateManifest(GoldenDatasetSchema.GoldenDataset dataset, List<String> failures) {
        GoldenDatasetSchema.DatasetManifest manifest = dataset.manifest();
        if (manifest == null) {
            failures.add("MANIFEST_MISSING");
            return;
        }
        if (!GoldenDatasetSchema.GOLDEN_SCHEMA_VERSION.equals(manifest.schema_version())) {
            failures.add("MANIFEST_SCHEMA_VERSION_INVALID:" + manifest.schema_version());
        }
        if (blank(manifest.dataset_id())) {
            failures.add("MANIFEST_DATASET_ID_MISSING");
        }
        if (manifest.splits().isEmpty()) {
            failures.add("MANIFEST_SPLITS_MISSING");
        }
        if (manifest.paper_packs().isEmpty()) {
            failures.add("MANIFEST_PAPER_PACKS_MISSING");
        }
        if (manifest.case_files().isEmpty()) {
            failures.add("MANIFEST_CASE_FILES_MISSING");
        }
        if (blank(manifest.scoring_profile())) {
            failures.add("MANIFEST_SCORING_PROFILE_MISSING");
        }
    }

    private Set<String> collectAnchorIds(GoldenDatasetSchema.GoldenDataset dataset, List<String> failures) {
        Set<String> ids = new HashSet<>();
        for (GoldenDatasetSchema.EvidenceAnchor anchor : dataset.evidenceAnchors()) {
            if (anchor == null || blank(anchor.anchor_id())) {
                failures.add("ANCHOR_ID_MISSING");
                continue;
            }
            if (!ids.add(anchor.anchor_id())) {
                failures.add("ANCHOR_ID_DUPLICATE:" + anchor.anchor_id());
            }
            if (blank(anchor.paper_id())) {
                failures.add("ANCHOR_PAPER_ID_MISSING:" + anchor.anchor_id());
            }
            if (anchor.element() == null || blank(anchor.element().type())) {
                failures.add("ANCHOR_ELEMENT_TYPE_MISSING:" + anchor.anchor_id());
            }
            boolean hasSelector = anchor.selector() != null
                    && (!blank(anchor.selector().exact_text()) || !blank(anchor.selector().regex()));
            if (!hasSelector) {
                failures.add("ANCHOR_SELECTOR_MISSING:" + anchor.anchor_id());
            }
        }
        return ids;
    }

    private Set<String> collectPaperIds(GoldenDatasetSchema.GoldenDataset dataset, List<String> failures) {
        Set<String> ids = new HashSet<>();
        for (GoldenDatasetSchema.PaperRecord paper : dataset.paperRecords()) {
            if (paper == null || blank(paper.paper_id())) {
                failures.add("PAPER_ID_MISSING");
                continue;
            }
            if (!ids.add(paper.paper_id())) {
                failures.add("PAPER_ID_DUPLICATE:" + paper.paper_id());
            }
            if (paper.identity() == null || blank(paper.identity().title())) {
                failures.add("PAPER_TITLE_MISSING:" + paper.paper_id());
            }
            if (paper.identity() == null || paper.identity().year() == null) {
                failures.add("PAPER_YEAR_MISSING:" + paper.paper_id());
            }
        }
        return ids;
    }

    private Set<String> collectPackIds(GoldenDatasetSchema.GoldenDataset dataset, List<String> failures) {
        Set<String> ids = new HashSet<>();
        for (GoldenDatasetSchema.PaperPackFile pack : dataset.paperPacks()) {
            if (pack == null || blank(pack.id())) {
                failures.add("PACK_ID_MISSING");
                continue;
            }
            if (!ids.add(pack.id())) {
                failures.add("PACK_ID_DUPLICATE:" + pack.id());
            }
            if (blank(pack.title())) {
                failures.add("PACK_TITLE_MISSING:" + pack.id());
            }
            if (pack.papers().isEmpty()) {
                failures.add("PACK_PAPERS_MISSING:" + pack.id());
            }
        }
        return ids;
    }

    private void validateCases(GoldenDatasetSchema.GoldenDataset dataset,
                               Set<String> anchorIds,
                               Set<String> paperIds,
                               Set<String> packIds,
                               List<String> failures) {
        Set<String> caseIds = new HashSet<>();
        for (GoldenDatasetSchema.GoldenCase testCase : dataset.cases()) {
            if (testCase == null) {
                failures.add("CASE_NULL");
                continue;
            }
            if (blank(testCase.id())) {
                failures.add("CASE_ID_MISSING");
                continue;
            }
            if (!caseIds.add(testCase.id())) {
                failures.add("CASE_ID_DUPLICATE:" + testCase.id());
            }
            if (!GoldenDatasetSchema.GOLDEN_SCHEMA_VERSION.equals(testCase.schema_version())) {
                failures.add("CASE_SCHEMA_VERSION_INVALID:" + testCase.id() + ":" + testCase.schema_version());
            }
            if (testCase.question() == null || blank(testCase.question().text())) {
                failures.add("CASE_QUESTION_MISSING:" + testCase.id());
            }
            if (testCase.capability_tags().isEmpty()) {
                failures.add("CASE_CAPABILITY_TAGS_MISSING:" + testCase.id());
            }
            if (testCase.answer_contract().isEmpty() || !testCase.answer_contract().containsKey("type")) {
                failures.add("CASE_ANSWER_CONTRACT_TYPE_MISSING:" + testCase.id());
            }
            for (String packId : testCase.paper_pack_ids()) {
                if (!packIds.contains(packId)) {
                    failures.add("CASE_UNKNOWN_PACK:" + testCase.id() + ":" + packId);
                }
            }
            validateCasePapers(testCase, paperIds, failures);
            validateCaseEvidence(testCase, anchorIds, failures);
            validateCaseClaims(testCase, anchorIds, failures);
        }
    }

    private void validateCasePapers(GoldenDatasetSchema.GoldenCase testCase, Set<String> paperIds, List<String> failures) {
        if (testCase.corpus_scope() == null) {
            failures.add("CASE_CORPUS_SCOPE_MISSING:" + testCase.id());
            return;
        }
        for (String paperId : testCase.corpus_scope().required_paper_ids()) {
            if (!paperIds.isEmpty() && !paperIds.contains(paperId)) {
                failures.add("CASE_UNKNOWN_REQUIRED_PAPER:" + testCase.id() + ":" + paperId);
            }
        }
    }

    private void validateCaseEvidence(GoldenDatasetSchema.GoldenCase testCase, Set<String> anchorIds, List<String> failures) {
        GoldenDatasetSchema.GoldEvidence evidence = testCase.gold_evidence();
        if (evidence == null) {
            failures.add("CASE_GOLD_EVIDENCE_MISSING:" + testCase.id());
            return;
        }
        boolean answered = testCase.expected_result() != null
                && "answered".equals(testCase.expected_result().kind());
        if (answered && evidence.required_anchor_ids().isEmpty()) {
            failures.add("ANSWERED_CASE_REQUIRES_ANCHOR:" + testCase.id());
        }
        for (String anchorId : evidence.required_anchor_ids()) {
            if (!anchorIds.contains(anchorId)) {
                failures.add("CASE_UNKNOWN_REQUIRED_ANCHOR:" + testCase.id() + ":" + anchorId);
            }
        }
        for (String anchorId : evidence.forbidden_anchor_ids()) {
            if (!anchorIds.contains(anchorId)) {
                failures.add("CASE_UNKNOWN_FORBIDDEN_ANCHOR:" + testCase.id() + ":" + anchorId);
            }
        }
    }

    private void validateCaseClaims(GoldenDatasetSchema.GoldenCase testCase, Set<String> anchorIds, List<String> failures) {
        for (GoldenDatasetSchema.GoldClaim claim : testCase.gold_claims()) {
            if (blank(claim.claim_id())) {
                failures.add("CLAIM_ID_MISSING:" + testCase.id());
            }
            boolean hasSupport = !claim.support_anchor_ids().isEmpty();
            boolean hasRefute = !claim.refute_anchor_ids().isEmpty();
            boolean hasMissingReason = !blank(claim.missing_evidence_reason());
            if (!hasSupport && !hasRefute && !hasMissingReason) {
                failures.add("CLAIM_REQUIRES_SUPPORT_REFUTE_OR_MISSING_REASON:" + testCase.id() + ":" + claim.claim_id());
            }
            for (String anchorId : claim.support_anchor_ids()) {
                if (!anchorIds.contains(anchorId)) {
                    failures.add("UNKNOWN_CLAIM_SUPPORT_ANCHOR:" + testCase.id() + ":" + claim.claim_id() + ":" + anchorId);
                }
            }
            for (String anchorId : claim.refute_anchor_ids()) {
                if (!anchorIds.contains(anchorId)) {
                    failures.add("UNKNOWN_CLAIM_REFUTE_ANCHOR:" + testCase.id() + ":" + claim.claim_id() + ":" + anchorId);
                }
            }
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
