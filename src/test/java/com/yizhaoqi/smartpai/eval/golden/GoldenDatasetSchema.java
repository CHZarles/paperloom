package com.yizhaoqi.smartpai.eval.golden;

import java.util.List;
import java.util.Map;

public final class GoldenDatasetSchema {

    public static final String GOLDEN_SCHEMA_VERSION = "harness-golden-data/v1";
    public static final String RUN_TRACE_SCHEMA_VERSION = "harness-run-trace/v1";

    private GoldenDatasetSchema() {
    }

    public record DatasetManifest(
            String schema_version,
            String dataset_id,
            String title,
            String description,
            String created_at,
            List<Owner> owners,
            String source_strategy_doc,
            List<ManifestSplit> splits,
            List<ManifestRef> paper_packs,
            List<ManifestRef> case_files,
            String scoring_profile,
            List<CompatibilityExport> compatibility_exports
    ) {
        public DatasetManifest {
            owners = list(owners);
            splits = list(splits);
            paper_packs = list(paper_packs);
            case_files = list(case_files);
            compatibility_exports = list(compatibility_exports);
        }
    }

    public record Owner(String name) {
    }

    public record ManifestSplit(String id, String purpose) {
    }

    public record ManifestRef(String id, String path) {
    }

    public record CompatibilityExport(String format, String path) {
    }

    public record PaperPackFile(
            String id,
            String title,
            String purpose,
            List<String> capability_tags,
            List<PackPaper> papers,
            List<PaperRecord> paper_records,
            List<CitationEdge> citation_edges,
            List<EvidenceAnchor> evidence_anchors,
            List<String> known_traps
    ) {
        public PaperPackFile {
            capability_tags = list(capability_tags);
            papers = list(papers);
            paper_records = list(paper_records);
            citation_edges = list(citation_edges);
            evidence_anchors = list(evidence_anchors);
            known_traps = list(known_traps);
        }
    }

    public record PackPaper(String paper_id, String role) {
    }

    public record CitationEdge(String from_paper_id, String to_paper_id, String edge_type, String evidence_anchor_id) {
    }

    public record PaperRecord(
            String paper_id,
            PaperIdentity identity,
            Map<String, Object> source_assets,
            Map<String, Object> ingest_expectations,
            Map<String, Object> metadata_quality
    ) {
        public PaperRecord {
            source_assets = map(source_assets);
            ingest_expectations = map(ingest_expectations);
            metadata_quality = map(metadata_quality);
        }
    }

    public record PaperIdentity(
            String title,
            List<String> authors,
            Integer year,
            String venue,
            String doi,
            String arxiv_id,
            String version_label
    ) {
        public PaperIdentity {
            authors = list(authors);
        }
    }

    public record EvidenceAnchor(
            String anchor_id,
            String paper_id,
            String role,
            AnchorElement element,
            AnchorSelector selector,
            Map<String, String> normalized_facts,
            Map<String, String> asset_requirement,
            List<String> failure_if_missing
    ) {
        public EvidenceAnchor {
            normalized_facts = stringMap(normalized_facts);
            asset_requirement = stringMap(asset_requirement);
            failure_if_missing = list(failure_if_missing);
        }
    }

    public record AnchorElement(String type, String section, Integer page, String location_hint, String bbox) {
    }

    public record AnchorSelector(String exact_text, String regex) {
    }

    public record CaseFile(List<GoldenCase> cases) {
        public CaseFile {
            cases = list(cases);
        }
    }

    public record GoldenCase(
            String id,
            String schema_version,
            String split,
            Question question,
            List<String> capability_tags,
            String difficulty,
            List<String> paper_pack_ids,
            CorpusScope corpus_scope,
            ExpectedResult expected_result,
            Map<String, Object> expected_intent,
            Map<String, Object> expected_retrieval_plan,
            GoldEvidence gold_evidence,
            List<GoldClaim> gold_claims,
            Map<String, Object> answer_contract,
            RequiredTrace required_trace,
            List<FailureMode> failure_modes,
            CompatibilityProjection compatibility_projection
    ) {
        public GoldenCase {
            capability_tags = list(capability_tags);
            paper_pack_ids = list(paper_pack_ids);
            expected_intent = map(expected_intent);
            expected_retrieval_plan = map(expected_retrieval_plan);
            gold_claims = list(gold_claims);
            answer_contract = map(answer_contract);
            failure_modes = list(failure_modes);
        }
    }

    public record Question(String language, String text) {
    }

    public record CorpusScope(
            String retrieval_corpus,
            List<String> required_paper_ids,
            List<String> allowed_paper_ids,
            List<String> hard_negative_paper_ids
    ) {
        public CorpusScope {
            required_paper_ids = list(required_paper_ids);
            allowed_paper_ids = list(allowed_paper_ids);
            hard_negative_paper_ids = list(hard_negative_paper_ids);
        }
    }

    public record ExpectedResult(String kind, String answer_type) {
    }

    public record GoldEvidence(
            List<String> required_anchor_ids,
            List<String> optional_anchor_ids,
            List<String> forbidden_anchor_ids
    ) {
        public GoldEvidence {
            required_anchor_ids = list(required_anchor_ids);
            optional_anchor_ids = list(optional_anchor_ids);
            forbidden_anchor_ids = list(forbidden_anchor_ids);
        }
    }

    public record GoldClaim(
            String claim_id,
            Boolean required,
            String canonical_text,
            String expected_status,
            List<String> support_anchor_ids,
            List<String> refute_anchor_ids,
            String exact_value,
            String missing_evidence_reason
    ) {
        public GoldClaim {
            support_anchor_ids = list(support_anchor_ids);
            refute_anchor_ids = list(refute_anchor_ids);
        }
    }

    public record RequiredTrace(List<TraceObligation> obligations) {
        public RequiredTrace {
            obligations = list(obligations);
        }
    }

    public record TraceObligation(
            String id,
            String phase,
            String severity,
            List<String> must_include,
            List<String> must_include_strategy,
            List<String> must_include_anchor_ids,
            Map<String, Object> scoring
    ) {
        public TraceObligation {
            must_include = list(must_include);
            must_include_strategy = list(must_include_strategy);
            must_include_anchor_ids = list(must_include_anchor_ids);
            scoring = map(scoring);
        }
    }

    public record FailureMode(String id, String description) {
    }

    public record CompatibilityProjection(
            String taskType,
            String expectedRoute,
            List<String> requiredEvidenceRegex,
            List<String> requiredAnswerRegex,
            List<String> forbiddenAnswerRegex,
            List<String> forbiddenEvidenceRegex,
            List<String> expectedPaperIds,
            Boolean requiresCitation
    ) {
        public CompatibilityProjection {
            requiredEvidenceRegex = list(requiredEvidenceRegex);
            requiredAnswerRegex = list(requiredAnswerRegex);
            forbiddenAnswerRegex = list(forbiddenAnswerRegex);
            forbiddenEvidenceRegex = list(forbiddenEvidenceRegex);
            expectedPaperIds = list(expectedPaperIds);
        }
    }

    public record GoldenDataset(
            DatasetManifest manifest,
            List<PaperPackFile> paperPacks,
            List<PaperRecord> paperRecords,
            List<EvidenceAnchor> evidenceAnchors,
            List<GoldenCase> cases
    ) {
        public GoldenDataset {
            paperPacks = list(paperPacks);
            paperRecords = list(paperRecords);
            evidenceAnchors = list(evidenceAnchors);
            cases = list(cases);
        }
    }

    public record RunTrace(
            String schema_version,
            String case_id,
            String harness_id,
            String started_at,
            String completed_at,
            String result_status,
            Map<String, Object> intent_frame,
            Map<String, Object> retrieval_plan,
            RunEvidenceLedger evidence_ledger,
            Map<String, Object> claim_graph,
            List<Map<String, Object>> reasoning_artifacts,
            VerificationPass verification_pass,
            Map<String, Object> final_answer,
            Map<String, Object> diagnostics
    ) {
        public RunTrace {
            intent_frame = map(intent_frame);
            retrieval_plan = map(retrieval_plan);
            claim_graph = map(claim_graph);
            reasoning_artifacts = list(reasoning_artifacts);
            final_answer = map(final_answer);
            diagnostics = map(diagnostics);
        }
    }

    public record RunEvidenceLedger(
            List<RunEvidenceItem> items,
            List<RunEvidenceItem> rejected_items,
            List<String> missing_evidence
    ) {
        public RunEvidenceLedger {
            items = list(items);
            rejected_items = list(rejected_items);
            missing_evidence = list(missing_evidence);
        }
    }

    public record RunEvidenceItem(
            String evidence_id,
            String matched_anchor_id,
            String paper_id,
            String title,
            String section,
            Integer page,
            String element_type,
            String span_text,
            String bbox_or_cell_ref,
            String retrieval_strategy,
            Double relevance_score,
            String confidence_label,
            List<String> supports_claim_ids,
            List<String> refutes_claim_ids
    ) {
        public RunEvidenceItem {
            supports_claim_ids = list(supports_claim_ids);
            refutes_claim_ids = list(refutes_claim_ids);
        }
    }

    public record VerificationPass(
            Integer unsupported_claim_count,
            Integer contradicted_claim_count,
            List<String> missing_required_anchor_ids,
            List<String> satisfied_trace_obligation_ids,
            List<String> failed_trace_obligation_ids,
            Boolean abstention_required
    ) {
        public VerificationPass {
            missing_required_anchor_ids = list(missing_required_anchor_ids);
            satisfied_trace_obligation_ids = list(satisfied_trace_obligation_ids);
            failed_trace_obligation_ids = list(failed_trace_obligation_ids);
        }
    }

    public record CaseScore(
            String case_id,
            boolean passed,
            Map<String, Object> layer_scores,
            List<String> failures
    ) {
        public CaseScore {
            layer_scores = map(layer_scores);
            failures = list(failures);
        }
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, Object> map(Map<String, Object> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    private static Map<String, String> stringMap(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
