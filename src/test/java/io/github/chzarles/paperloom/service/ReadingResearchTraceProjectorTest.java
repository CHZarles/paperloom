package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingResearchTraceProjectorTest {

    @Test
    void projectsVerifiedEvidenceAnswerIntoCanonicalTraceArtifacts() {
        ReadingTurnObservationLedger ledger = new ReadingTurnObservationLedger(
                "Explain the method claim",
                ReadingIntentFrame.observed(
                        "Explain the method claim",
                        "FIND_LOCATIONS",
                        List.of(),
                        List.of("training objective"),
                        List.of(new ReadingIntentFrame.LocationQueryPlan(
                                "training objective",
                                "METHOD",
                                "zh",
                                "en",
                                List.of("METHOD"),
                                List.of("SECTION")
                        )),
                        List.of("SECTION"),
                        List.of("METHOD"),
                        List.of("zh"),
                        List.of("en"),
                        List.of("METHOD")
                ),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of("page_ref_abc", Map.of(
                        "locationRef", "page_ref_abc",
                        "sourceTool", "find_reading_locations",
                        "paperId", "paper-1",
                        "paperHandle", "paper_handle_abc",
                        "title", "Readable Paper",
                        "sectionTitle", "Method",
                        "pageNumber", 3
                )),
                Map.of()
        );
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.EVIDENCE_ANSWER,
                "The method claim is supported. {{sourceQuoteRef:source_quote_abc}}",
                List.of(Map.of(
                        "claim", "The method claim is supported.",
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
        List<Map<String, Object>> references = List.of(Map.ofEntries(
                Map.entry("referenceNumber", 1),
                Map.entry("sourceQuoteRef", "source_quote_abc"),
                Map.entry("paperId", "paper-1"),
                Map.entry("paperVersion", "model-v1"),
                Map.entry("paperHandle", "paper_handle_abc"),
                Map.entry("paperTitle", "Readable Paper"),
                Map.entry("locationRef", "page_ref_abc"),
                Map.entry("pageNumber", 3),
                Map.entry("sectionTitle", "Method"),
                Map.entry("contentKind", "paragraph"),
                Map.entry("content", "The method is described in this quoted passage.")
        ));
        ReadingTurnProjection projection = new ReadingTurnArtifactProjector().project(
                ledger,
                envelope,
                references,
                Map.of("source_quote_abc", 1)
        );
        ReadingArtifactContractValidation validation = new ReadingArtifactContractValidator().validate(
                envelope,
                projection,
                references
        );

        ReadingResearchTrace trace = new ReadingResearchTraceProjector().project(
                "generation-1",
                ledger,
                envelope,
                projection.artifacts(),
                references,
                validation,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );

        assertTrue(validation.valid());
        assertEquals("research-harness-artifacts/v1", trace.schemaVersion());
        assertEquals("generation-1", trace.intentFrame().questionId());
        assertEquals("typed_location_query_plan_observed", projection.artifacts().intentFrame().planningStatus());
        ReadingTurnArtifacts.ReadingPlanStep step = projection.artifacts().readingPlan().steps().get(0);
        assertEquals("Readable Paper", step.actions().get(0).payload().get("paperTitle"));
        assertEquals("page_ref_abc", step.actions().get(0).payload().get("locationRef"));
        assertEquals("semantic_search", trace.retrievalPlan().strategySteps().get(0).retrievalStrategy());
        assertEquals("evidence_1", trace.evidenceLedger().items().get(0).evidenceId());
        assertEquals("model-v1", trace.evidenceLedger().items().get(0).paperVersion());
        assertEquals("claim_1", trace.claimGraph().claims().get(0).claimId());
        assertEquals(List.of("evidence_1"), trace.claimGraph().claims().get(0).supportingEvidenceIds());
        assertFalse(trace.reasoningArtifacts().isEmpty());
        assertTrue(trace.verificationPass().valid());
        assertEquals("answered", trace.researchAnswer().status());
        assertEquals(List.of("claim_1"), trace.researchAnswer().citedClaimIds());
    }
}
