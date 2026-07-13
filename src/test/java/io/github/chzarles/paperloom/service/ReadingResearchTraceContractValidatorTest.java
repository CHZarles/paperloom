package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingResearchTraceContractValidatorTest {

    private final ReadingResearchTraceContractValidator validator = new ReadingResearchTraceContractValidator();

    @Test
    void acceptsCompletedEvidenceTraceWithSupportedClaimGraph() {
        ReadingResearchTrace trace = traceForEnvelope(new AnswerEnvelope(
                AnswerType.EVIDENCE_ANSWER,
                "The selected location reports an improved score. [1]",
                List.of(Map.of(
                        "claim", "The selected location reports an improved score.",
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        ));

        ReadingResearchTraceContractValidation validation = validator.validateForCompletion(
                trace,
                traceAnswerEnvelope(trace),
                ProductResultStatus.COMPLETED
        );

        assertTrue(validation.valid(), validation.reason());
    }

    @Test
    void rejectsCompletedEvidenceTraceWithoutAtomicClaimText() {
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.EVIDENCE_ANSWER,
                "The selected location reports an improved score. [1]",
                List.of(Map.of(
                        "claim", "",
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
        ReadingResearchTrace trace = traceForEnvelope(envelope);

        ReadingResearchTraceContractValidation validation = validator.validateForCompletion(
                trace,
                envelope,
                ProductResultStatus.COMPLETED
        );

        assertFalse(validation.valid());
        assertTrue(validation.missingFields().contains("claim_text"));
        assertTrue(validation.missingFields().contains("claim_support"));
    }

    @Test
    void rejectsCompletedTraceWithVisibleInternalIdentifierInResearchAnswer() {
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.PRODUCT_STATE,
                "Start with paper_handle_abc.",
                List.of(),
                List.of(Map.of("claim", "A candidate paper was found.", "sourceTool", "search_paper_candidates")),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
        ReadingResearchTrace trace = new ReadingResearchTrace(
                "research-harness-artifacts/v1",
                new ReadingResearchTrace.IntentFrameArtifact(
                        "generation-1",
                        "Recommend papers",
                        "Recommend papers",
                        List.of("Agentic Eval Benchmark"),
                        List.of("Agentic Eval Benchmark"),
                        List.of(),
                        List.of(),
                        List.of(),
                        "constraint_filter",
                        "unambiguous",
                        List.of("metadata"),
                        List.of("paper_discovery")
                ),
                new ReadingResearchTrace.RetrievalPlanArtifact(
                        "retrieval_plan_1",
                        "generation-1",
                        List.of("Agentic Eval Benchmark"),
                        List.of(new ReadingResearchTrace.StrategyStep(
                                "retrieval_step_1",
                                "semantic_search",
                                "candidate papers",
                                List.of("metadata"),
                                "observed"
                        )),
                        List.of("metadata"),
                        List.of("3 to 5 beginner-suitable paper cards when doing topic discovery"),
                        "hard negatives must not be cited",
                        List.of("artifact contract reached a terminal state")
                ),
                new ReadingResearchTrace.EvidenceLedgerArtifact(
                        "evidence_ledger_1",
                        "generation-1",
                        List.of(),
                        List.of(),
                        List.of("paper_content_quote")
                ),
                new ReadingResearchTrace.ClaimGraphArtifact("claim_graph_1", "generation-1", List.of(), List.of()),
                List.of(new ReadingResearchTrace.ReasoningArtifact(
                        "reasoning_artifact_1",
                        "generation-1",
                        "constraint_filter_table",
                        "Paper shortlist",
                        List.of(),
                        List.of(),
                        Map.of("items", List.of())
                )),
                new ReadingResearchTrace.VerificationPassArtifact(
                        "verification_pass_1",
                        "generation-1",
                        true,
                        List.of("paper_discovery"),
                        "satisfied",
                        0,
                        0,
                        List.of(),
                        "unambiguous",
                        List.of("artifact_contract_satisfied"),
                        false,
                        List.of("reading_artifact_contract"),
                        List.of(),
                        "COMPLETED",
                        "COMPLETED"
                ),
                new ReadingResearchTrace.ResearchAnswerArtifact(
                        "research_answer_1",
                        "generation-1",
                        "answered",
                        "constraint_filter",
                        "Start with paper_handle_abc.",
                        List.of(Map.of("type", "summary", "text", "Start with paper_handle_abc.")),
                        List.of(),
                        List.of(),
                        List.of("reasoning_artifact_1"),
                        "verification_pass_1"
                )
        );

        ReadingResearchTraceContractValidation validation = validator.validateForCompletion(
                trace,
                envelope,
                ProductResultStatus.COMPLETED
        );

        assertFalse(validation.valid());
        assertTrue(validation.missingFields().contains("visible_internal_identifier"));
    }

    private ReadingResearchTrace traceForEnvelope(AnswerEnvelope envelope) {
        ReadingTurnObservationLedger ledger = ledger();
        List<Map<String, Object>> references = references();
        ReadingTurnProjection projection = new ReadingTurnArtifactProjector().project(
                ledger,
                envelope,
                references,
                Map.of("source_quote_abc", 1)
        );
        ReadingArtifactContractValidation artifactValidation = new ReadingArtifactContractValidator().validate(
                envelope,
                projection,
                references
        );
        return new ReadingResearchTraceProjector().project(
                "generation-1",
                ledger,
                envelope,
                projection.artifacts(),
                references,
                artifactValidation,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private AnswerEnvelope traceAnswerEnvelope(ReadingResearchTrace trace) {
        return new AnswerEnvelope(
                AnswerType.EVIDENCE_ANSWER,
                trace.researchAnswer().summary(),
                List.of(Map.of(
                        "claim", "The selected location reports an improved score.",
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private ReadingTurnObservationLedger ledger() {
        return new ReadingTurnObservationLedger(
                "Explain the selected result",
                ReadingIntentFrame.observed(
                        "Explain the selected result",
                        "",
                        List.of("agent evaluation"),
                        List.of("score"),
                        List.of(new ReadingIntentFrame.LocationQueryPlan(
                                "score",
                                "METRIC",
                                "zh",
                                "en",
                                List.of("RESULT"),
                                List.of("PAGE")
                        )),
                        List.of("PAGE"),
                        List.of("METRIC"),
                        List.of("zh"),
                        List.of("en"),
                        List.of("RESULT")
                ),
                Map.of(),
                List.of(Map.of(
                        "kind", "READING_PAPER_CHOICE",
                        "sourceTool", "search_paper_candidates",
                        "paperHandle", "paper_handle_abc",
                        "title", "Agentic Eval Benchmark"
                )),
                Map.of("paper_handle_abc", Map.of(
                        "paperId", "paper-raw",
                        "paperHandle", "paper_handle_abc",
                        "title", "Agentic Eval Benchmark",
                        "paperTypes", List.of("benchmark")
                )),
                Map.of("page_ref_abc", Map.of(
                        "locationRef", "page_ref_abc",
                        "sourceTool", "find_reading_locations",
                        "paperId", "paper-raw",
                        "paperHandle", "paper_handle_abc",
                        "title", "Agentic Eval Benchmark",
                        "sectionTitle", "Results",
                        "pageNumber", 3
                )),
                Map.of()
        );
    }

    private List<Map<String, Object>> references() {
        return List.of(Map.ofEntries(
                Map.entry("referenceNumber", 1),
                Map.entry("sourceQuoteRef", "source_quote_abc"),
                Map.entry("paperId", "paper-raw"),
                Map.entry("paperVersion", "model-v1"),
                Map.entry("paperHandle", "paper_handle_abc"),
                Map.entry("paperTitle", "Agentic Eval Benchmark"),
                Map.entry("locationRef", "page_ref_abc"),
                Map.entry("pageNumber", 3),
                Map.entry("sectionTitle", "Results"),
                Map.entry("contentKind", "TEXT"),
                Map.entry("content", "The reported score improves.")
        ));
    }
}
