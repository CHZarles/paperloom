# Harness Golden Data Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `research/harness-golden-data-schema.md` into runnable offline eval code plus committed smoke data that can validate Golden Cases, score Harness Run Traces, and export compatibility `RagBenchmarkCase` JSONL.

**Architecture:** Build the first runtime under the existing test-scoped eval package so it cannot alter product chat behavior or eval database tables. YAML under `research/golden-data` is the authored source of truth; Java loader, validator, projector, scorer, and CLI code make it executable; existing `RagBenchmarkCase` JSONL remains a derived compatibility export.

**Tech Stack:** Java 17, Maven, Jackson Databind, Jackson YAML, JUnit 5, existing PaperLoom test eval utilities.

## Global Constraints

- Canonical schema version is exactly `harness-golden-data/v1`.
- Harness run trace schema version is exactly `harness-run-trace/v1`.
- Golden Case YAML is canonical; generated `RagBenchmarkCase` JSONL is compatibility output only.
- Keep implementation test-scoped under `src/test/java/com/yizhaoqi/smartpai/eval/golden`.
- Do not change product chat behavior, Product Reading tools, product database tables, or eval database tables in this slice.
- Do not import benchmark data into product tables.
- Do not require live backend, MySQL, Elasticsearch, Redis, or model credentials for the golden runtime smoke.
- Add only one Maven dependency: `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` with `test` scope.
- Commit runnable smoke data under `research/golden-data`.
- Export generated compatibility JSONL under ignored or generated eval paths only when the CLI is run; do not hand-author the generated JSONL as canonical data.

---

## Grill Outcomes

### 1. Where should the first runnable runtime live?

Recommended answer: test-scoped eval code.

Adopted. Put the implementation under `src/test/java/com/yizhaoqi/smartpai/eval/golden`. This follows the existing QASPER, LitSearch, parser smoke, and live benchmark pattern while keeping the production backend untouched.

### 2. Should the first runtime implement the full Seed-60 dataset?

Recommended answer: no.

Adopted. The first implementation proves the schema with a committed smoke dataset, then leaves the Seed-60 expansion as data authoring on top of working code. The smoke data must still exercise answered, clarification, abstention, and failed-trace scoring.

### 3. Should YAML or JSON be canonical?

Recommended answer: YAML.

Adopted. The schema document is YAML-shaped, and authoring nested Golden Cases is more readable in YAML. JSONL remains the compatibility projection for existing eval runners.

### 4. Should the first scorer judge natural-language reasoning quality?

Recommended answer: only through declared contracts and trace obligations.

Adopted. The first scorer is deterministic: it checks evidence anchors, required claims, answer fields, trace obligations, contradiction/abstention flags, and compatibility output. LLM-as-judge is out of scope for this slice.

### 5. Should live Product Reading traces be connected immediately?

Recommended answer: no.

Adopted. Score committed Harness Run Trace fixtures first. Live trace adapters can be added once the schema/runtime contract is stable.

## File Structure

- Modify `pom.xml`: add Jackson YAML as a test dependency.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetSchema.java`: compact schema records used by loader, validator, projector, scorer, and tests.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetLoader.java`: load `manifest.yaml`, referenced paper packs, and referenced case files.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetValidator.java`: enforce authoring rules from the schema.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRagBenchmarkProjector.java`: derive `RagBenchmarkCase` rows and JSONL.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRunTraceLoader.java`: load one Harness Run Trace fixture.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenTraceScorer.java`: score a `GoldenCase` against one loaded run trace.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCli.java`: provide `validate`, `export-rag`, and `score-trace` commands.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/*Test.java`: focused tests for each component.
- Create `research/golden-data/manifest.yaml`: canonical smoke manifest.
- Create `research/golden-data/paper-packs/transformer-bert-gpt.yaml`: one real paper pack with paper records and anchors.
- Create `research/golden-data/cases/seed-smoke.yaml`: smoke Golden Cases.
- Create `research/golden-data/run-traces/transformer-adam-pass.yaml`: passing Harness Run Trace fixture.
- Create `research/golden-data/run-traces/transformer-adam-fail.yaml`: failing Harness Run Trace fixture.
- Create `research/golden-data/README.md`: authoring and command guide.
- Modify `eval/rag/harnesses.yaml`: register the golden smoke benchmark and fixture harness.
- Modify `eval/rag/README.md`: document the golden runtime.
- Modify `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java`: assert registry rows for the golden smoke benchmark.

## Task 1: Add YAML Dependency And Schema Records

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetSchema.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetSchemaTest.java`

**Interfaces:**
- Consumes: YAML field names from `research/harness-golden-data-schema.md`.
- Produces: `GoldenDatasetSchema` nested records used by loader, validator, projector, and scorer.

- [ ] **Step 1: Write the failing schema mapping test**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetSchemaTest.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetSchemaTest {

    @Test
    void mapsManifestYamlIntoSchemaRecord() throws Exception {
        String yaml = """
                schema_version: harness-golden-data/v1
                dataset_id: seed_smoke
                title: Golden Smoke
                splits:
                  - id: seed
                    purpose: Smoke coverage
                paper_packs:
                  - id: transformer_bert_gpt
                    path: paper-packs/transformer-bert-gpt.yaml
                case_files:
                  - path: cases/seed-smoke.yaml
                scoring_profile: trace_obligation_v1
                """;

        GoldenDatasetSchema.DatasetManifest manifest = new YAMLMapper()
                .readValue(yaml, GoldenDatasetSchema.DatasetManifest.class);

        assertEquals("harness-golden-data/v1", manifest.schema_version());
        assertEquals("seed_smoke", manifest.dataset_id());
        assertEquals("seed", manifest.splits().get(0).id());
        assertEquals("transformer_bert_gpt", manifest.paper_packs().get(0).id());
        assertTrue(manifest.compatibility_exports().isEmpty());
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=GoldenDatasetSchemaTest test
```

Expected: FAIL because `jackson-dataformat-yaml` and `GoldenDatasetSchema` do not exist.

- [ ] **Step 3: Add the YAML dependency**

Add this dependency immediately after the existing `jackson-databind` dependency in `pom.xml`:

```xml
        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-yaml</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 4: Add the schema record holder**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetSchema.java`:

```java
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
```

- [ ] **Step 5: Run the focused test and verify it passes**

Run:

```bash
mvn -q -Dtest=GoldenDatasetSchemaTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetSchema.java src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetSchemaTest.java
git commit -m "test: add golden data schema records"
```

## Task 2: Implement Dataset Loader

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetLoader.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetLoaderTest.java`

**Interfaces:**
- Consumes: `GoldenDatasetSchema.DatasetManifest`, `PaperPackFile`, and `CaseFile`.
- Produces: `GoldenDatasetSchema.GoldenDataset load(Path manifestPath)`.

- [ ] **Step 1: Write the failing loader test**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetLoaderTest.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoldenDatasetLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsManifestPacksAnchorsAndCases() throws Exception {
        Files.createDirectories(tempDir.resolve("paper-packs"));
        Files.createDirectories(tempDir.resolve("cases"));
        Files.writeString(tempDir.resolve("manifest.yaml"), """
                schema_version: harness-golden-data/v1
                dataset_id: seed_smoke
                splits:
                  - id: seed
                    purpose: Smoke coverage
                paper_packs:
                  - id: pack_a
                    path: paper-packs/pack-a.yaml
                case_files:
                  - path: cases/cases.yaml
                scoring_profile: trace_obligation_v1
                """);
        Files.writeString(tempDir.resolve("paper-packs/pack-a.yaml"), """
                id: pack_a
                title: Pack A
                purpose: Loader test
                papers:
                  - paper_id: paper_a
                    role: target
                paper_records:
                  - paper_id: paper_a
                    identity:
                      title: Paper A
                      year: 2024
                evidence_anchors:
                  - anchor_id: anchor_a
                    paper_id: paper_a
                    role: supports
                    element:
                      type: paragraph
                      page: 1
                    selector:
                      exact_text: "Alpha evidence."
                """);
        Files.writeString(tempDir.resolve("cases/cases.yaml"), """
                cases:
                  - id: case_a
                    schema_version: harness-golden-data/v1
                    split: seed
                    question:
                      language: en
                      text: What does Paper A say?
                    capability_tags:
                      - precision_fact_extraction
                    paper_pack_ids:
                      - pack_a
                    corpus_scope:
                      retrieval_corpus: EVAL_HARNESS_SEED
                      required_paper_ids:
                        - paper_a
                    expected_result:
                      kind: answered
                      answer_type: exact_fact
                    expected_intent: {}
                    gold_evidence:
                      required_anchor_ids:
                        - anchor_a
                    answer_contract:
                      type: exact_fact
                    required_trace:
                      obligations: []
                """);

        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader().load(tempDir.resolve("manifest.yaml"));

        assertEquals("seed_smoke", dataset.manifest().dataset_id());
        assertEquals(1, dataset.paperPacks().size());
        assertEquals(1, dataset.paperRecords().size());
        assertEquals(1, dataset.evidenceAnchors().size());
        assertEquals(1, dataset.cases().size());
        assertEquals("case_a", dataset.cases().get(0).id());
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=GoldenDatasetLoaderTest test
```

Expected: FAIL because `GoldenDatasetLoader` does not exist.

- [ ] **Step 3: Implement the loader**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetLoader.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GoldenDatasetLoader {

    private final YAMLMapper yamlMapper;

    public GoldenDatasetLoader() {
        this(YAMLMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build());
    }

    GoldenDatasetLoader(YAMLMapper yamlMapper) {
        this.yamlMapper = yamlMapper;
    }

    public GoldenDatasetSchema.GoldenDataset load(Path manifestPath) throws IOException {
        Path absoluteManifest = manifestPath.toAbsolutePath().normalize();
        Path root = absoluteManifest.getParent();
        GoldenDatasetSchema.DatasetManifest manifest = yamlMapper.readValue(
                absoluteManifest.toFile(),
                GoldenDatasetSchema.DatasetManifest.class
        );

        List<GoldenDatasetSchema.PaperPackFile> packs = new ArrayList<>();
        List<GoldenDatasetSchema.PaperRecord> paperRecords = new ArrayList<>();
        List<GoldenDatasetSchema.EvidenceAnchor> anchors = new ArrayList<>();
        for (GoldenDatasetSchema.ManifestRef ref : manifest.paper_packs()) {
            GoldenDatasetSchema.PaperPackFile pack = yamlMapper.readValue(
                    root.resolve(ref.path()).normalize().toFile(),
                    GoldenDatasetSchema.PaperPackFile.class
            );
            packs.add(pack);
            paperRecords.addAll(pack.paper_records());
            anchors.addAll(pack.evidence_anchors());
        }

        List<GoldenDatasetSchema.GoldenCase> cases = new ArrayList<>();
        for (GoldenDatasetSchema.ManifestRef ref : manifest.case_files()) {
            GoldenDatasetSchema.CaseFile caseFile = yamlMapper.readValue(
                    root.resolve(ref.path()).normalize().toFile(),
                    GoldenDatasetSchema.CaseFile.class
            );
            cases.addAll(caseFile.cases());
        }

        return new GoldenDatasetSchema.GoldenDataset(manifest, packs, paperRecords, anchors, cases);
    }
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

```bash
mvn -q -Dtest=GoldenDatasetLoaderTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetLoader.java src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetLoaderTest.java
git commit -m "test: load golden data manifests"
```

## Task 3: Implement Golden Dataset Validation

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetValidator.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetValidatorTest.java`

**Interfaces:**
- Consumes: `GoldenDatasetSchema.GoldenDataset`.
- Produces: `List<String> validate(GoldenDataset dataset)` and `void requireValid(GoldenDataset dataset)`.

- [ ] **Step 1: Write failing validation tests**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetValidatorTest.java` with tests for the critical authoring rules:

```java
package com.yizhaoqi.smartpai.eval.golden;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetValidatorTest {

    @Test
    void rejectsAnsweredCaseWithoutRequiredAnchor() {
        GoldenDatasetSchema.GoldenDataset dataset = datasetWithCase(new GoldenDatasetSchema.GoldenCase(
                "case_a",
                GoldenDatasetSchema.GOLDEN_SCHEMA_VERSION,
                "seed",
                new GoldenDatasetSchema.Question("en", "What is the value?"),
                List.of("precision_fact_extraction"),
                "easy",
                List.of("pack_a"),
                new GoldenDatasetSchema.CorpusScope("EVAL_HARNESS_SEED", List.of("paper_a"), List.of(), List.of()),
                new GoldenDatasetSchema.ExpectedResult("answered", "exact_fact"),
                Map.of(),
                Map.of(),
                new GoldenDatasetSchema.GoldEvidence(List.of(), List.of(), List.of()),
                List.of(),
                Map.of("type", "exact_fact"),
                new GoldenDatasetSchema.RequiredTrace(List.of()),
                List.of(),
                new GoldenDatasetSchema.CompatibilityProjection(
                        "METHODOLOGY_REPRODUCTION",
                        "MANUAL_SOURCE_QA",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("paper_a"),
                        true
                )
        ));

        List<String> failures = new GoldenDatasetValidator().validate(dataset);

        assertTrue(failures.stream().anyMatch(failure -> failure.contains("ANSWERED_CASE_REQUIRES_ANCHOR")));
    }

    @Test
    void rejectsClaimSupportThatReferencesMissingAnchor() {
        GoldenDatasetSchema.GoldenCase testCase = new GoldenDatasetSchema.GoldenCase(
                "case_a",
                GoldenDatasetSchema.GOLDEN_SCHEMA_VERSION,
                "seed",
                new GoldenDatasetSchema.Question("en", "What is the value?"),
                List.of("precision_fact_extraction"),
                "easy",
                List.of("pack_a"),
                new GoldenDatasetSchema.CorpusScope("EVAL_HARNESS_SEED", List.of("paper_a"), List.of(), List.of()),
                new GoldenDatasetSchema.ExpectedResult("answered", "exact_fact"),
                Map.of(),
                Map.of(),
                new GoldenDatasetSchema.GoldEvidence(List.of("anchor_a"), List.of(), List.of()),
                List.of(new GoldenDatasetSchema.GoldClaim(
                        "claim_a",
                        true,
                        "Claim A",
                        "supported",
                        List.of("missing_anchor"),
                        List.of(),
                        "1",
                        ""
                )),
                Map.of("type", "exact_fact"),
                new GoldenDatasetSchema.RequiredTrace(List.of()),
                List.of(),
                null
        );

        GoldenDatasetSchema.GoldenDataset dataset = datasetWithCase(testCase);

        List<String> failures = new GoldenDatasetValidator().validate(dataset);

        assertTrue(failures.stream().anyMatch(failure -> failure.contains("UNKNOWN_CLAIM_SUPPORT_ANCHOR")));
    }

    @Test
    void requireValidThrowsWithReadableMessage() {
        GoldenDatasetSchema.GoldenDataset dataset = datasetWithCase(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new GoldenDatasetValidator().requireValid(dataset)
        );

        assertTrue(error.getMessage().contains("GOLDEN_DATASET_INVALID"));
    }

    private GoldenDatasetSchema.GoldenDataset datasetWithCase(GoldenDatasetSchema.GoldenCase testCase) {
        GoldenDatasetSchema.DatasetManifest manifest = new GoldenDatasetSchema.DatasetManifest(
                GoldenDatasetSchema.GOLDEN_SCHEMA_VERSION,
                "seed_smoke",
                "Seed Smoke",
                "",
                "2026-07-08",
                List.of(),
                "research/harness-golden-data-strategy.md",
                List.of(new GoldenDatasetSchema.ManifestSplit("seed", "Smoke coverage")),
                List.of(new GoldenDatasetSchema.ManifestRef("pack_a", "paper-packs/pack-a.yaml")),
                List.of(new GoldenDatasetSchema.ManifestRef(null, "cases/seed-smoke.yaml")),
                "trace_obligation_v1",
                List.of()
        );
        GoldenDatasetSchema.EvidenceAnchor anchor = new GoldenDatasetSchema.EvidenceAnchor(
                "anchor_a",
                "paper_a",
                "supports",
                new GoldenDatasetSchema.AnchorElement("paragraph", "Training", 1, "value", null),
                new GoldenDatasetSchema.AnchorSelector("Alpha evidence.", "Alpha"),
                Map.of("value", "1"),
                Map.of("text", "required"),
                List.of()
        );
        List<GoldenDatasetSchema.GoldenCase> cases = testCase == null ? List.of() : List.of(testCase);
        return new GoldenDatasetSchema.GoldenDataset(
                manifest,
                List.of(),
                List.of(),
                List.of(anchor),
                cases
        );
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=GoldenDatasetValidatorTest test
```

Expected: FAIL because `GoldenDatasetValidator` does not exist.

- [ ] **Step 3: Implement validation**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetValidator.java`:

```java
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
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

```bash
mvn -q -Dtest=GoldenDatasetValidatorTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetValidator.java src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetValidatorTest.java
git commit -m "test: validate golden data authoring rules"
```

## Task 4: Add Runnable Golden Smoke Data

**Files:**
- Create: `research/golden-data/manifest.yaml`
- Create: `research/golden-data/paper-packs/transformer-bert-gpt.yaml`
- Create: `research/golden-data/cases/seed-smoke.yaml`
- Create: `research/golden-data/run-traces/transformer-adam-pass.yaml`
- Create: `research/golden-data/run-traces/transformer-adam-fail.yaml`
- Create: `research/golden-data/README.md`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCommittedDataTest.java`

**Interfaces:**
- Consumes: `GoldenDatasetLoader` and `GoldenDatasetValidator`.
- Produces: committed YAML dataset that can be loaded and validated without live services.

- [ ] **Step 1: Write the failing committed-data test**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCommittedDataTest.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetCommittedDataTest {

    @Test
    void committedGoldenSmokeDatasetLoadsAndValidates() throws Exception {
        Path manifest = Path.of("research/golden-data/manifest.yaml");

        assertTrue(Files.exists(manifest), "golden manifest must exist");

        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader().load(manifest);
        new GoldenDatasetValidator().requireValid(dataset);

        assertEquals("harness_golden_seed_smoke", dataset.manifest().dataset_id());
        assertEquals(1, dataset.paperPacks().size());
        assertEquals(4, dataset.cases().size());
        assertTrue(dataset.evidenceAnchors().stream()
                .anyMatch(anchor -> "transformer_adam_training_params_span".equals(anchor.anchor_id())));
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=GoldenDatasetCommittedDataTest test
```

Expected: FAIL because `research/golden-data/manifest.yaml` does not exist.

- [ ] **Step 3: Add the manifest**

Create `research/golden-data/manifest.yaml`:

```yaml
schema_version: harness-golden-data/v1
dataset_id: harness_golden_seed_smoke
title: "Harness Golden Seed Smoke"
description: "Runnable smoke dataset for Golden Case loading, validation, projection, and trace scoring."
created_at: "2026-07-08"
owners:
  - name: "PaperLoom"
source_strategy_doc: "research/harness-golden-data-strategy.md"
splits:
  - id: seed
    purpose: "Runtime smoke coverage"
  - id: stress
    purpose: "Boundary behavior smoke coverage"
paper_packs:
  - id: transformer_bert_gpt
    path: "paper-packs/transformer-bert-gpt.yaml"
case_files:
  - path: "cases/seed-smoke.yaml"
scoring_profile: trace_obligation_v1
compatibility_exports:
  - format: rag_benchmark_case_jsonl
    path: "../../eval/rag/generated/harness-golden-seed-smoke.jsonl"
```

- [ ] **Step 4: Add the paper pack**

Create `research/golden-data/paper-packs/transformer-bert-gpt.yaml`:

```yaml
id: transformer_bert_gpt
title: "Transformer / BERT / GPT Pack"
purpose: "Test original-paper fact extraction, ambiguous attention references, and knowledge-boundary abstention."
capability_tags:
  - precision_fact_extraction
  - methodology_reproduction
  - ambiguity_resolution
  - uncertainty_knowledge_boundary
papers:
  - paper_id: attention_is_all_you_need_2017
    role: target
  - paper_id: bert_2018
    role: successor
  - paper_id: gpt3_2020
    role: hard_distractor
paper_records:
  - paper_id: attention_is_all_you_need_2017
    identity:
      title: "Attention Is All You Need"
      authors:
        - "Ashish Vaswani"
        - "Noam Shazeer"
      year: 2017
      venue: "NeurIPS"
      arxiv_id: "1706.03762"
      version_label: "arXiv/camera-ready"
    source_assets:
      parser: "paperloom-reading-model"
    ingest_expectations:
      requires_pdf_visual_assets: true
      requires_tables: true
      requires_figures: true
      requires_formulas: true
    metadata_quality:
      identity_status: "verified"
  - paper_id: bert_2018
    identity:
      title: "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding"
      authors:
        - "Jacob Devlin"
        - "Ming-Wei Chang"
      year: 2018
      venue: "NAACL"
      arxiv_id: "1810.04805"
      version_label: "arXiv/camera-ready"
    metadata_quality:
      identity_status: "verified"
  - paper_id: gpt3_2020
    identity:
      title: "Language Models are Few-Shot Learners"
      authors:
        - "Tom B. Brown"
      year: 2020
      venue: "NeurIPS"
      arxiv_id: "2005.14165"
      version_label: "arXiv/camera-ready"
    metadata_quality:
      identity_status: "verified"
citation_edges:
  - from_paper_id: bert_2018
    to_paper_id: attention_is_all_you_need_2017
    edge_type: "uses_transformer_encoder"
    evidence_anchor_id: bert_transformer_encoder_background
evidence_anchors:
  - anchor_id: transformer_adam_training_params_span
    paper_id: attention_is_all_you_need_2017
    role: supports
    element:
      type: paragraph
      section: "Training"
      page: 7
      location_hint: "Optimizer hyperparameters"
    selector:
      exact_text: "We used the Adam optimizer with beta1 = 0.9, beta2 = 0.98 and epsilon = 10^-9."
      regex: "Adam optimizer.*beta_?1\\s*=\\s*0\\.9.*beta_?2\\s*=\\s*0\\.98.*epsilon\\s*=\\s*(10\\^-9|1e-9)"
    normalized_facts:
      adam_beta1: "0.9"
      adam_beta2: "0.98"
      adam_epsilon: "1e-9"
    asset_requirement:
      text: "required"
      pdf_page: "required"
    failure_if_missing:
      - "The answer may substitute default Adam values."
  - anchor_id: bert_transformer_encoder_background
    paper_id: bert_2018
    role: background
    element:
      type: abstract
      page: 1
      location_hint: "BERT abstract"
    selector:
      regex: "bidirectional Transformer"
    normalized_facts:
      architecture_role: "bidirectional Transformer encoder"
known_traps:
  - "Default Adam beta2=0.999 is a distractor for Transformer training hyperparameters."
  - "The phrase attention paper may mean several different papers."
```

- [ ] **Step 5: Add the smoke cases**

Create `research/golden-data/cases/seed-smoke.yaml` with exactly four cases: one answered, one ambiguous, one abstention, and one compatibility projection case.

```yaml
cases:
  - id: transformer_adam_params_001
    schema_version: harness-golden-data/v1
    split: seed
    question:
      language: en
      text: "Transformer original paper used Adam with what beta1, beta2, and epsilon?"
    capability_tags:
      - precision_fact_extraction
      - methodology_reproduction
    difficulty: easy
    paper_pack_ids:
      - transformer_bert_gpt
    corpus_scope:
      retrieval_corpus: EVAL_HARNESS_SEED
      required_paper_ids:
        - attention_is_all_you_need_2017
      allowed_paper_ids:
        - attention_is_all_you_need_2017
        - bert_2018
        - gpt3_2020
      hard_negative_paper_ids:
        - bert_2018
        - gpt3_2020
    expected_result:
      kind: answered
      answer_type: exact_fact
    expected_intent:
      entities:
        - "Transformer"
        - "Adam"
      ambiguity_status: "unambiguous"
      required_evidence_types:
        - "paragraph"
    expected_retrieval_plan:
      required_strategies:
        - "paper_identity_resolution"
        - "lexical_search"
        - "section_search"
    gold_evidence:
      required_anchor_ids:
        - transformer_adam_training_params_span
      forbidden_anchor_ids:
        - bert_transformer_encoder_background
    gold_claims:
      - claim_id: adam_beta1
        required: true
        canonical_text: "The Transformer paper used Adam beta1 = 0.9."
        expected_status: supported
        support_anchor_ids:
          - transformer_adam_training_params_span
        exact_value: "0.9"
      - claim_id: adam_beta2
        required: true
        canonical_text: "The Transformer paper used Adam beta2 = 0.98."
        expected_status: supported
        support_anchor_ids:
          - transformer_adam_training_params_span
        exact_value: "0.98"
      - claim_id: adam_epsilon
        required: true
        canonical_text: "The Transformer paper used Adam epsilon = 1e-9."
        expected_status: supported
        support_anchor_ids:
          - transformer_adam_training_params_span
        exact_value: "1e-9"
    answer_contract:
      type: exact_fact
      required_fields:
        beta1: "0.9"
        beta2: "0.98"
        epsilon: "1e-9"
      citation_policy:
        every_required_field_must_cite_anchor: true
    required_trace:
      obligations:
        - id: resolve_original_transformer
          phase: intent
          severity: critical
          must_include:
            - "Attention Is All You Need"
            - "optimizer hyperparameter lookup"
        - id: retrieve_optimizer_span
          phase: evidence
          severity: critical
          must_include_anchor_ids:
            - transformer_adam_training_params_span
        - id: reject_default_adam_values
          phase: verification
          severity: critical
          must_include:
            - "reject default Adam beta2=0.999"
    failure_modes:
      - id: default_adam_values
        description: "Gives beta2=0.999."
    compatibility_projection:
      taskType: METHODOLOGY_REPRODUCTION
      expectedRoute: MANUAL_SOURCE_QA
      requiredEvidenceRegex:
        - "Adam optimizer.*0\\.9.*0\\.98.*(10\\^-9|1e-9)"
      requiredAnswerRegex:
        - "0\\.9"
        - "0\\.98"
        - "1e-?9|10\\^-9"
      forbiddenAnswerRegex:
        - "0\\.999"
      forbiddenEvidenceRegex:
        - "BERT"
      expectedPaperIds:
        - attention_is_all_you_need_2017
      requiresCitation: true

  - id: attention_paper_ambiguous_001
    schema_version: harness-golden-data/v1
    split: stress
    question:
      language: en
      text: "attention paper"
    capability_tags:
      - ambiguity_resolution
    difficulty: easy
    paper_pack_ids:
      - transformer_bert_gpt
    corpus_scope:
      retrieval_corpus: EVAL_HARNESS_SEED
      allowed_paper_ids:
        - attention_is_all_you_need_2017
        - bert_2018
    expected_result:
      kind: needs_clarification
      answer_type: ambiguity_clarification
    expected_intent:
      ambiguity_status: "ambiguous"
      acceptable_options:
        - "Attention Is All You Need"
        - "earlier neural attention papers"
    gold_evidence:
      required_anchor_ids: []
    answer_contract:
      type: ambiguity_clarification
      required_options:
        - "Attention Is All You Need"
        - "earlier neural attention papers"
      must_not_select_paper_without_user_choice: true
    required_trace:
      obligations:
        - id: detect_ambiguous_attention
          phase: intent
          severity: critical
          must_include:
            - "ambiguous"
            - "attention paper"
    compatibility_projection:
      taskType: AMBIGUITY_RESOLUTION
      expectedRoute: CLARIFY
      requiredAnswerRegex:
        - "which|clarify|ambiguous|which paper"
      requiredEvidenceRegex: []
      expectedPaperIds: []
      requiresCitation: false

  - id: gpt5_architecture_boundary_001
    schema_version: harness-golden-data/v1
    split: stress
    question:
      language: en
      text: "What are the architecture details of GPT-5?"
    capability_tags:
      - uncertainty_knowledge_boundary
    difficulty: medium
    paper_pack_ids:
      - transformer_bert_gpt
    corpus_scope:
      retrieval_corpus: EVAL_HARNESS_SEED
      allowed_paper_ids:
        - gpt3_2020
    expected_result:
      kind: abstain_insufficient_evidence
      answer_type: uncertainty_boundary
    expected_intent:
      entities:
        - "GPT-5"
      ambiguity_status: "unanswerable_from_available_evidence"
    gold_evidence:
      required_anchor_ids: []
    gold_claims:
      - claim_id: gpt5_architecture_not_supported
        required: true
        canonical_text: "The available corpus does not support GPT-5 architecture details."
        expected_status: underdetermined
        missing_evidence_reason: "No verified GPT-5 architecture paper is present in the corpus."
    answer_contract:
      type: uncertainty_boundary
      required_sections:
        - what_is_known
        - what_is_not_supported
        - missing_evidence
      must_abstain_from:
        - unsupported_architecture_claims
    required_trace:
      obligations:
        - id: abstain_on_missing_gpt5_evidence
          phase: verification
          severity: critical
          must_include:
            - "insufficient evidence"
            - "no verified GPT-5 architecture paper"
    compatibility_projection:
      taskType: UNCERTAINTY_BOUNDARY
      expectedRoute: CLARIFY
      requiredAnswerRegex:
        - "insufficient|not supported|no verified"
      forbiddenAnswerRegex:
        - "GPT-5 uses"
      requiredEvidenceRegex: []
      expectedPaperIds: []
      requiresCitation: false

  - id: bert_transformer_role_001
    schema_version: harness-golden-data/v1
    split: seed
    question:
      language: en
      text: "Does BERT use the Transformer as an encoder or decoder?"
    capability_tags:
      - precision_fact_extraction
      - deep_comparison
    difficulty: easy
    paper_pack_ids:
      - transformer_bert_gpt
    corpus_scope:
      retrieval_corpus: EVAL_HARNESS_SEED
      required_paper_ids:
        - bert_2018
      allowed_paper_ids:
        - attention_is_all_you_need_2017
        - bert_2018
    expected_result:
      kind: answered
      answer_type: exact_fact
    expected_intent:
      entities:
        - "BERT"
        - "Transformer"
      required_evidence_types:
        - "abstract"
    gold_evidence:
      required_anchor_ids:
        - bert_transformer_encoder_background
    gold_claims:
      - claim_id: bert_uses_bidirectional_transformer_encoder
        required: true
        canonical_text: "BERT uses a bidirectional Transformer encoder."
        expected_status: supported
        support_anchor_ids:
          - bert_transformer_encoder_background
        exact_value: "bidirectional Transformer encoder"
    answer_contract:
      type: exact_fact
      required_fields:
        transformer_role: "bidirectional Transformer encoder"
      citation_policy:
        every_required_field_must_cite_anchor: true
    required_trace:
      obligations:
        - id: retrieve_bert_architecture_anchor
          phase: evidence
          severity: critical
          must_include_anchor_ids:
            - bert_transformer_encoder_background
    compatibility_projection:
      taskType: PRECISION_FACT_EXTRACTION
      expectedRoute: MANUAL_SOURCE_QA
      requiredEvidenceRegex:
        - "bidirectional Transformer"
      requiredAnswerRegex:
        - "encoder|bidirectional"
      expectedPaperIds:
        - bert_2018
      requiresCitation: true
```

- [ ] **Step 6: Add run trace fixtures**

Create `research/golden-data/run-traces/transformer-adam-pass.yaml`:

```yaml
schema_version: harness-run-trace/v1
case_id: transformer_adam_params_001
harness_id: golden_trace_fixture
started_at: "2026-07-08T12:00:00Z"
completed_at: "2026-07-08T12:00:01Z"
result_status: completed
intent_frame:
  answer_type: exact_fact
  entities:
    - Transformer
    - Adam
  notes:
    - "Attention Is All You Need"
    - "optimizer hyperparameter lookup"
retrieval_plan:
  strategies:
    - paper_identity_resolution
    - lexical_search
    - section_search
evidence_ledger:
  items:
    - evidence_id: run_evidence_001
      matched_anchor_id: transformer_adam_training_params_span
      paper_id: attention_is_all_you_need_2017
      title: "Attention Is All You Need"
      section: "Training"
      page: 7
      element_type: paragraph
      span_text: "We used the Adam optimizer with beta1 = 0.9, beta2 = 0.98 and epsilon = 10^-9."
      retrieval_strategy: lexical_search
      relevance_score: 0.99
      confidence_label: high
      supports_claim_ids:
        - adam_beta1
        - adam_beta2
        - adam_epsilon
  rejected_items:
    - evidence_id: rejected_bert_background
      matched_anchor_id: bert_transformer_encoder_background
      paper_id: bert_2018
      title: "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding"
      element_type: abstract
      retrieval_strategy: hard_negative_review
      confidence_label: rejected
  missing_evidence: []
claim_graph:
  claims:
    - claim_id: adam_beta1
      text: "The Transformer paper used Adam beta1 = 0.9."
      status: supported
    - claim_id: adam_beta2
      text: "The Transformer paper used Adam beta2 = 0.98."
      status: supported
    - claim_id: adam_epsilon
      text: "The Transformer paper used Adam epsilon = 1e-9."
      status: supported
reasoning_artifacts:
  - type: field_table
    payload:
      beta1: "0.9"
      beta2: "0.98"
      epsilon: "1e-9"
verification_pass:
  unsupported_claim_count: 0
  contradicted_claim_count: 0
  missing_required_anchor_ids: []
  satisfied_trace_obligation_ids:
    - resolve_original_transformer
    - retrieve_optimizer_span
    - reject_default_adam_values
  failed_trace_obligation_ids: []
  abstention_required: false
final_answer:
  markdown: "The original Transformer paper used Adam with beta1=0.9, beta2=0.98, and epsilon=1e-9 [1]."
  cited_anchor_ids:
    - transformer_adam_training_params_span
  fields:
    beta1: "0.9"
    beta2: "0.98"
    epsilon: "1e-9"
diagnostics:
  token_count: 256
  latency_ms: 1000
```

Create `research/golden-data/run-traces/transformer-adam-fail.yaml`:

```yaml
schema_version: harness-run-trace/v1
case_id: transformer_adam_params_001
harness_id: golden_trace_fixture
started_at: "2026-07-08T12:00:00Z"
completed_at: "2026-07-08T12:00:01Z"
result_status: completed
intent_frame:
  answer_type: exact_fact
  entities:
    - Transformer
retrieval_plan:
  strategies:
    - semantic_search
evidence_ledger:
  items:
    - evidence_id: wrong_evidence_001
      matched_anchor_id: bert_transformer_encoder_background
      paper_id: bert_2018
      title: "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding"
      element_type: abstract
      span_text: "BERT is designed to pre-train deep bidirectional representations."
      retrieval_strategy: semantic_search
      confidence_label: low
      supports_claim_ids: []
  rejected_items: []
  missing_evidence:
    - transformer_adam_training_params_span
claim_graph:
  claims:
    - claim_id: adam_beta2
      text: "The Transformer paper used Adam beta2 = 0.999."
      status: unsupported
reasoning_artifacts:
  - type: field_table
    payload:
      beta1: "0.9"
      beta2: "0.999"
      epsilon: "1e-8"
verification_pass:
  unsupported_claim_count: 1
  contradicted_claim_count: 0
  missing_required_anchor_ids:
    - transformer_adam_training_params_span
  satisfied_trace_obligation_ids: []
  failed_trace_obligation_ids:
    - resolve_original_transformer
    - retrieve_optimizer_span
    - reject_default_adam_values
  abstention_required: false
final_answer:
  markdown: "The Transformer paper used the default Adam beta2=0.999."
  cited_anchor_ids:
    - bert_transformer_encoder_background
  fields:
    beta1: "0.9"
    beta2: "0.999"
    epsilon: "1e-8"
diagnostics:
  token_count: 128
  latency_ms: 700
```

- [ ] **Step 7: Add the README**

Create `research/golden-data/README.md`:

```markdown
# Harness Golden Data

This directory contains the runnable source-of-truth smoke dataset for the evidence-first Golden
Case schema.

Canonical entry point:

```text
research/golden-data/manifest.yaml
```

Commands after implementation:

```bash
mvn -q -Dtest=GoldenDatasetCommittedDataTest test
mvn -q -Dtest=GoldenDatasetCliTest test
```

Generated compatibility JSONL is not canonical. Regenerate it from `manifest.yaml` with the golden
dataset CLI when a downstream flat `RagBenchmarkCase` runner needs it.
```

- [ ] **Step 8: Run the focused test and verify it passes**

Run:

```bash
mvn -q -Dtest=GoldenDatasetCommittedDataTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add research/golden-data src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCommittedDataTest.java
git commit -m "test: add runnable golden smoke data"
```

## Task 5: Add Compatibility Projection To RagBenchmarkCase

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRagBenchmarkProjector.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRagBenchmarkProjectorTest.java`

**Interfaces:**
- Consumes: `GoldenDatasetSchema.GoldenCase`.
- Produces: `RagBenchmarkCase` and JSONL compatible with existing eval loaders.

- [ ] **Step 1: Write the failing projector test**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRagBenchmarkProjectorTest.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import com.yizhaoqi.smartpai.eval.RagBenchmarkCase;
import com.yizhaoqi.smartpai.eval.RagBenchmarkDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenRagBenchmarkProjectorTest {

    @TempDir
    Path tempDir;

    @Test
    void projectsCommittedGoldenCasesToRagBenchmarkCases() throws Exception {
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader()
                .load(Path.of("research/golden-data/manifest.yaml"));

        List<RagBenchmarkCase> projected = new GoldenRagBenchmarkProjector().project(dataset.cases());

        assertEquals(4, projected.size());
        RagBenchmarkCase adam = projected.stream()
                .filter(testCase -> "transformer_adam_params_001".equals(testCase.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("Transformer original paper used Adam with what beta1, beta2, and epsilon?", adam.query());
        assertEquals("METHODOLOGY_REPRODUCTION", adam.taskType());
        assertEquals("MANUAL_SOURCE", adam.scopeMode());
        assertEquals(List.of("attention_is_all_you_need_2017"), adam.expectedPaperIds());
        assertTrue(adam.requiresCitation());
    }

    @Test
    void writesJsonlThatExistingDatasetLoaderCanRead() throws Exception {
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader()
                .load(Path.of("research/golden-data/manifest.yaml"));
        Path output = tempDir.resolve("golden.jsonl");

        new GoldenRagBenchmarkProjector().writeJsonl(dataset.cases(), output);

        List<RagBenchmarkCase> loaded = RagBenchmarkDataset.load(output);
        assertEquals(4, loaded.size());
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=GoldenRagBenchmarkProjectorTest test
```

Expected: FAIL because `GoldenRagBenchmarkProjector` does not exist.

- [ ] **Step 3: Implement the projector**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRagBenchmarkProjector.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.eval.RagBenchmarkCase;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GoldenRagBenchmarkProjector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<RagBenchmarkCase> project(List<GoldenDatasetSchema.GoldenCase> cases) {
        return (cases == null ? List.<GoldenDatasetSchema.GoldenCase>of() : cases)
                .stream()
                .map(this::project)
                .toList();
    }

    public RagBenchmarkCase project(GoldenDatasetSchema.GoldenCase testCase) {
        GoldenDatasetSchema.CompatibilityProjection projection = testCase.compatibility_projection();
        List<String> expectedPaperIds = projection == null
                ? testCase.corpus_scope().required_paper_ids()
                : projection.expectedPaperIds();
        String scopeMode = expectedPaperIds.isEmpty() ? "AUTO_SOURCE" : "MANUAL_SOURCE";
        return new RagBenchmarkCase(
                testCase.id(),
                testCase.question() == null ? "" : testCase.question().text(),
                testCase.question() == null ? "en" : blankToDefault(testCase.question().language(), "en"),
                projection == null ? answerTypeAsTaskType(testCase) : blankToDefault(projection.taskType(), answerTypeAsTaskType(testCase)),
                scopeMode,
                new RagBenchmarkCase.Scope(expectedPaperIds, List.of()),
                projection == null ? "" : blankToDefault(projection.expectedRoute(), ""),
                projection == null ? List.of() : projection.requiredAnswerRegex(),
                projection == null ? List.of() : projection.requiredEvidenceRegex(),
                projection == null ? List.of() : projection.forbiddenAnswerRegex(),
                projection == null ? List.of() : projection.forbiddenEvidenceRegex(),
                expectedPaperIds,
                projection != null && Boolean.TRUE.equals(projection.requiresCitation())
        );
    }

    public void writeJsonl(List<GoldenDatasetSchema.GoldenCase> cases, Path output) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            for (RagBenchmarkCase row : project(cases)) {
                writer.write(OBJECT_MAPPER.writeValueAsString(row));
                writer.newLine();
            }
        }
    }

    private String answerTypeAsTaskType(GoldenDatasetSchema.GoldenCase testCase) {
        if (testCase.expected_result() == null || testCase.expected_result().answer_type() == null) {
            return "GOLDEN_CASE";
        }
        return testCase.expected_result().answer_type().toUpperCase().replace('-', '_');
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

```bash
mvn -q -Dtest=GoldenRagBenchmarkProjectorTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRagBenchmarkProjector.java src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRagBenchmarkProjectorTest.java
git commit -m "test: project golden cases to rag benchmark rows"
```

## Task 6: Add Run Trace Loader And Deterministic Scorer

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRunTraceLoader.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenTraceScorer.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenTraceScorerTest.java`

**Interfaces:**
- Consumes: `GoldenCase`, `RunTrace`, and evidence anchors.
- Produces: `GoldenDatasetSchema.CaseScore`.

- [ ] **Step 1: Write failing scorer tests**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenTraceScorerTest.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenTraceScorerTest {

    @Test
    void scoresPassingTraceFixture() throws Exception {
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader()
                .load(Path.of("research/golden-data/manifest.yaml"));
        GoldenDatasetSchema.GoldenCase testCase = caseById(dataset, "transformer_adam_params_001");
        GoldenDatasetSchema.RunTrace trace = new GoldenRunTraceLoader()
                .load(Path.of("research/golden-data/run-traces/transformer-adam-pass.yaml"));

        GoldenDatasetSchema.CaseScore score = new GoldenTraceScorer().score(testCase, dataset, trace);

        assertTrue(score.passed(), () -> String.join(", ", score.failures()));
    }

    @Test
    void scoresFailingTraceFixture() throws Exception {
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader()
                .load(Path.of("research/golden-data/manifest.yaml"));
        GoldenDatasetSchema.GoldenCase testCase = caseById(dataset, "transformer_adam_params_001");
        GoldenDatasetSchema.RunTrace trace = new GoldenRunTraceLoader()
                .load(Path.of("research/golden-data/run-traces/transformer-adam-fail.yaml"));

        GoldenDatasetSchema.CaseScore score = new GoldenTraceScorer().score(testCase, dataset, trace);

        assertFalse(score.passed());
        assertTrue(score.failures().stream().anyMatch(failure -> failure.contains("REQUIRED_ANCHOR_MISSING")));
        assertTrue(score.failures().stream().anyMatch(failure -> failure.contains("FORBIDDEN_ANCHOR_USED")));
        assertTrue(score.failures().stream().anyMatch(failure -> failure.contains("TRACE_OBLIGATION_FAILED")));
    }

    private GoldenDatasetSchema.GoldenCase caseById(GoldenDatasetSchema.GoldenDataset dataset, String id) {
        Map<String, GoldenDatasetSchema.GoldenCase> cases = dataset.cases().stream()
                .collect(Collectors.toMap(GoldenDatasetSchema.GoldenCase::id, Function.identity()));
        return cases.get(id);
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=GoldenTraceScorerTest test
```

Expected: FAIL because the loader and scorer do not exist.

- [ ] **Step 3: Implement the run trace loader**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRunTraceLoader.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Path;

public final class GoldenRunTraceLoader {

    private final YAMLMapper yamlMapper = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public GoldenDatasetSchema.RunTrace load(Path path) throws IOException {
        return yamlMapper.readValue(path.toFile(), GoldenDatasetSchema.RunTrace.class);
    }
}
```

- [ ] **Step 4: Implement deterministic scoring**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenTraceScorer.java`:

```java
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
        verifyTraceObligations(testCase, trace, failures);
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

    @SuppressWarnings("unchecked")
    private void verifyAnswerFields(GoldenDatasetSchema.GoldenCase testCase, GoldenDatasetSchema.RunTrace trace, List<String> failures) {
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
                                        List<String> failures) {
        List<String> satisfied = trace.verification_pass() == null
                ? List.of()
                : trace.verification_pass().satisfied_trace_obligation_ids();
        List<String> failed = trace.verification_pass() == null
                ? List.of()
                : trace.verification_pass().failed_trace_obligation_ids();
        String traceText = String.valueOf(trace);
        for (GoldenDatasetSchema.TraceObligation obligation : testCase.required_trace().obligations()) {
            if (failed.contains(obligation.id())) {
                failures.add("TRACE_OBLIGATION_FAILED:" + obligation.id());
            }
            if (!obligation.must_include_anchor_ids().isEmpty()) {
                for (String anchorId : obligation.must_include_anchor_ids()) {
                    if (!actualAnchorIds(trace).contains(anchorId)) {
                        failures.add("TRACE_OBLIGATION_ANCHOR_MISSING:" + obligation.id() + ":" + anchorId);
                    }
                }
            }
            for (String requiredText : obligation.must_include()) {
                if (!traceText.toLowerCase().contains(requiredText.toLowerCase())) {
                    failures.add("TRACE_OBLIGATION_TEXT_MISSING:" + obligation.id() + ":" + requiredText);
                }
            }
            if (!obligation.id().isBlank() && !satisfied.contains(obligation.id()) && obligation.must_include().isEmpty()
                    && obligation.must_include_anchor_ids().isEmpty()) {
                failures.add("TRACE_OBLIGATION_NOT_SATISFIED:" + obligation.id());
            }
        }
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
                "required_obligation_count", testCase.required_trace() == null ? 0 : testCase.required_trace().obligations().size(),
                "failure_count", failures.size()
        ));
        return scores;
    }
}
```

- [ ] **Step 5: Run the focused test and verify it passes**

Run:

```bash
mvn -q -Dtest=GoldenTraceScorerTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenRunTraceLoader.java src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenTraceScorer.java src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenTraceScorerTest.java
git commit -m "test: score golden harness run traces"
```

## Task 7: Add Runnable Golden Dataset CLI

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCli.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCliTest.java`

**Interfaces:**
- Consumes: manifest path, output path, trace path, run output path.
- Produces: validation result, compatibility JSONL, and score JSON.

- [ ] **Step 1: Write failing CLI tests**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCliTest.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.eval.RagBenchmarkDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void validatesCommittedManifest() throws Exception {
        int exitCode = GoldenDatasetCli.run(new String[]{
                "validate",
                "--manifest", "research/golden-data/manifest.yaml"
        });

        assertEquals(0, exitCode);
    }

    @Test
    void exportsCompatibilityJsonl() throws Exception {
        Path output = tempDir.resolve("golden.jsonl");

        int exitCode = GoldenDatasetCli.run(new String[]{
                "export-rag",
                "--manifest", "research/golden-data/manifest.yaml",
                "--output", output.toString()
        });

        assertEquals(0, exitCode);
        assertEquals(4, RagBenchmarkDataset.load(output).size());
    }

    @Test
    void scoresTraceToJson() throws Exception {
        Path output = tempDir.resolve("score.json");

        int exitCode = GoldenDatasetCli.run(new String[]{
                "score-trace",
                "--manifest", "research/golden-data/manifest.yaml",
                "--trace", "research/golden-data/run-traces/transformer-adam-pass.yaml",
                "--output", output.toString()
        });

        assertEquals(0, exitCode);
        JsonNode json = OBJECT_MAPPER.readTree(Files.readString(output));
        assertTrue(json.path("passed").asBoolean());
        assertEquals("transformer_adam_params_001", json.path("case_id").asText());
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=GoldenDatasetCliTest test
```

Expected: FAIL because `GoldenDatasetCli` does not exist.

- [ ] **Step 3: Implement the CLI**

Create `src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCli.java`:

```java
package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GoldenDatasetCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GoldenDatasetCli() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing command: validate, export-rag, or score-trace");
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        Path manifest = Path.of(options.getOrDefault("manifest", "research/golden-data/manifest.yaml"));
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader().load(manifest);

        if ("validate".equals(command)) {
            new GoldenDatasetValidator().requireValid(dataset);
            return 0;
        }
        if ("export-rag".equals(command)) {
            new GoldenDatasetValidator().requireValid(dataset);
            Path output = Path.of(required(options, "output"));
            new GoldenRagBenchmarkProjector().writeJsonl(dataset.cases(), output);
            return 0;
        }
        if ("score-trace".equals(command)) {
            new GoldenDatasetValidator().requireValid(dataset);
            Path tracePath = Path.of(required(options, "trace"));
            Path output = Path.of(required(options, "output"));
            GoldenDatasetSchema.RunTrace trace = new GoldenRunTraceLoader().load(tracePath);
            GoldenDatasetSchema.GoldenCase testCase = casesById(dataset).get(trace.case_id());
            GoldenDatasetSchema.CaseScore score = new GoldenTraceScorer().score(testCase, dataset, trace);
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), score);
            return score.passed() ? 0 : 2;
        }
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    private static Map<String, GoldenDatasetSchema.GoldenCase> casesById(GoldenDatasetSchema.GoldenDataset dataset) {
        return dataset.cases().stream()
                .collect(Collectors.toMap(GoldenDatasetSchema.GoldenCase::id, Function.identity()));
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + arg);
            }
            values.put(arg.substring(2), args[++i]);
        }
        return values;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option: --" + key);
        }
        return value;
    }
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

```bash
mvn -q -Dtest=GoldenDatasetCliTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCli.java src/test/java/com/yizhaoqi/smartpai/eval/golden/GoldenDatasetCliTest.java
git commit -m "test: add golden dataset cli"
```

## Task 8: Register And Document The Golden Smoke Benchmark

**Files:**
- Modify: `eval/rag/harnesses.yaml`
- Modify: `eval/rag/README.md`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java`

**Interfaces:**
- Consumes: existing `RagBenchmarkRegistry`.
- Produces: registry rows for the golden smoke benchmark and fixture scorer.

- [ ] **Step 1: Write the failing registry test**

Append this test to `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java`:

```java
    @Test
    void registersHarnessGoldenSeedSmoke() throws Exception {
        RagBenchmarkRegistry registry = RagBenchmarkRegistry.load(Path.of("eval/rag/harnesses.yaml"));

        assertTrue(registry.harnesses().stream()
                .anyMatch(harness -> "golden-trace-fixture".equals(harness.id())
                        && "runnable-golden-fixture".equals(harness.status())
                        && "fixture-trace".equals(harness.retrieval())
                        && harness.benchmarkIds().equals(List.of("harness-golden-seed-smoke"))));

        RagBenchmarkRegistry.BenchmarkDefinition benchmark = registry.benchmark("harness-golden-seed-smoke");
        assertEquals("Harness Golden Seed Smoke", benchmark.name());
        assertEquals("professional", benchmark.tier());
        assertEquals("evidence-first harness trace scoring", benchmark.task());
        assertEquals("research/golden-data/manifest.yaml", benchmark.path());
        assertEquals("tracePassRate", benchmark.primaryMetric());
        assertEquals("4", benchmark.cases());
    }
```

- [ ] **Step 2: Run the registry test and verify it fails**

Run:

```bash
mvn -q -Dtest=RagBenchmarkRegistryTest#registersHarnessGoldenSeedSmoke test
```

Expected: FAIL because the registry rows are not present.

- [ ] **Step 3: Add the harness registry row**

Add this row under `harnesses:` in `eval/rag/harnesses.yaml`:

```yaml
  - id: golden-trace-fixture
    name: Golden Trace Fixture
    description: Offline fixture scorer for evidence-first Golden Cases and committed Harness Run Trace YAML.
    retrieval: fixture-trace
    planner: declared-trace
    verifier: trace-obligation-v1
    status: runnable-golden-fixture
    benchmarkIds: harness-golden-seed-smoke
```

- [ ] **Step 4: Add the benchmark registry row**

Add this row under `benchmarks:` in `eval/rag/harnesses.yaml`:

```yaml
  - id: harness-golden-seed-smoke
    name: Harness Golden Seed Smoke
    tier: professional
    task: evidence-first harness trace scoring
    status: runnable
    path: research/golden-data/manifest.yaml
    source: local committed Golden Case YAML
    primaryMetric: tracePassRate
    gateMode: harness-golden-seed-smoke
    cases: "4"
```

- [ ] **Step 5: Update eval README**

Add this paragraph to `eval/rag/README.md` under the Benchmarks section:

```markdown
`harness-golden-seed-smoke` is the first runnable evidence-first Golden Case dataset. Its canonical
source is `research/golden-data/manifest.yaml`, not generated JSONL. The committed smoke validates
schema loading, authoring rules, compatibility projection, and deterministic scoring of Harness Run
Trace fixtures. Use it to evolve the research-harness schema before connecting live Product Reading
traces.
```

- [ ] **Step 6: Run the registry test and verify it passes**

Run:

```bash
mvn -q -Dtest=RagBenchmarkRegistryTest#registersHarnessGoldenSeedSmoke test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add eval/rag/harnesses.yaml eval/rag/README.md src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java
git commit -m "docs: register golden harness smoke benchmark"
```

## Task 9: End-To-End Verification

**Files:**
- Modify: no source files beyond earlier tasks.
- Test: focused Maven test command.

**Interfaces:**
- Consumes: all completed tasks.
- Produces: verified runnable implementation plan completion.

- [ ] **Step 1: Run all golden package tests**

Run:

```bash
mvn -q -Dtest='com.yizhaoqi.smartpai.eval.golden.*Test' test
```

Expected: PASS.

- [ ] **Step 2: Run the registry test**

Run:

```bash
mvn -q -Dtest=RagBenchmarkRegistryTest#registersHarnessGoldenSeedSmoke test
```

Expected: PASS.

- [ ] **Step 3: Export compatibility JSONL through the CLI test path**

Run:

```bash
mvn -q -Dtest=GoldenDatasetCliTest#exportsCompatibilityJsonl test
```

Expected: PASS and the test proves exported JSONL can be read by `RagBenchmarkDataset`.

- [ ] **Step 4: Score both committed trace fixtures**

Run:

```bash
mvn -q -Dtest=GoldenTraceScorerTest test
```

Expected: PASS. The pass fixture must pass; the fail fixture must fail with anchor, forbidden-anchor, and trace-obligation failures.

- [ ] **Step 5: Run whitespace and conflict checks**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 6: Confirm no verification-only files changed**

Run:

```bash
git status --short
```

Expected: no new changes from Task 9. Any remaining changes should belong to earlier task commits.

## Final Acceptance

- `research/golden-data/manifest.yaml` exists and is the canonical smoke dataset entry point.
- `GoldenDatasetLoader` loads manifest, paper packs, paper records, evidence anchors, and cases.
- `GoldenDatasetValidator` rejects invalid authored data.
- `GoldenRagBenchmarkProjector` exports valid `RagBenchmarkCase` JSONL.
- `GoldenRunTraceLoader` loads committed Harness Run Trace YAML.
- `GoldenTraceScorer` deterministically passes the good fixture and fails the bad fixture.
- `GoldenDatasetCli` supports `validate`, `export-rag`, and `score-trace`.
- `eval/rag/harnesses.yaml` registers `harness-golden-seed-smoke`.
- Focused Maven tests pass:

```bash
mvn -q -Dtest='com.yizhaoqi.smartpai.eval.golden.*Test' test
mvn -q -Dtest=RagBenchmarkRegistryTest#registersHarnessGoldenSeedSmoke test
```

## Self-Review

- Spec coverage: the plan covers `DatasetManifest`, `PaperPack`, `PaperRecord`, `EvidenceAnchor`, `GoldenCase`, `AnswerContract`, `Trace Obligation`, `RunTrace`, `Scorecard`, compatibility projection, authoring rules, and first smoke data from `research/harness-golden-data-schema.md`.
- Placeholder scan: the plan uses concrete file paths, code snippets, YAML data, commands, and expected results.
- Type consistency: all task interfaces use `GoldenDatasetSchema` nested records, `GoldenDatasetLoader.load(Path)`, `GoldenDatasetValidator.requireValid(GoldenDatasetSchema.GoldenDataset)`, `GoldenRagBenchmarkProjector.project(List<GoldenDatasetSchema.GoldenCase>)`, `GoldenRunTraceLoader.load(Path)`, and `GoldenTraceScorer.score(GoldenDatasetSchema.GoldenCase, GoldenDatasetSchema.GoldenDataset, GoldenDatasetSchema.RunTrace)`.
