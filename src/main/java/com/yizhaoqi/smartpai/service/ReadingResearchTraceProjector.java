package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class ReadingResearchTraceProjector {

    ReadingResearchTrace project(String questionId,
                                 ReadingTurnObservationLedger ledger,
                                 AnswerEnvelope envelope,
                                 ReadingTurnArtifacts artifacts,
                                 List<Map<String, Object>> references,
                                 ReadingArtifactContractValidation validation,
                                 ProductStopReason stopReason,
                                 ProductResultStatus resultStatus) {
        ReadingTurnObservationLedger safeLedger = ledger == null
                ? ReadingTurnObservationLedger.empty("")
                : ledger;
        ReadingTurnArtifacts safeArtifacts = artifacts == null
                ? ReadingTurnArtifacts.empty("")
                : artifacts;
        AnswerEnvelope safeEnvelope = envelope == null
                ? new AnswerEnvelope(AnswerType.NON_EVIDENCE, "", List.of(), List.of(), List.of(), List.of(), List.of(), "")
                : envelope;
        ReadingArtifactContractValidation safeValidation = validation == null
                ? ReadingArtifactContractValidation.invalid(List.of("verification_pass"))
                : validation;
        String safeQuestionId = firstNonBlank(questionId, "current_reading_turn");
        Map<String, String> evidenceIdsBySourceQuoteRef = evidenceIdsBySourceQuoteRef(references);
        List<ReadingResearchTrace.ClaimNode> claims = claimNodes(safeEnvelope, evidenceIdsBySourceQuoteRef);
        List<ReadingResearchTrace.EvidenceItem> evidenceItems = evidenceItems(
                safeLedger,
                references,
                supportClaimIdsBySourceQuoteRef(safeEnvelope)
        );
        ReadingResearchTrace.ClaimGraphArtifact claimGraph = claimGraph(safeQuestionId, claims);
        List<ReadingResearchTrace.ReasoningArtifact> reasoningArtifacts = reasoningArtifacts(
                safeQuestionId,
                safeArtifacts,
                claimGraph,
                evidenceItems
        );
        ReadingResearchTrace.VerificationPassArtifact verificationPass = verificationPass(
                safeQuestionId,
                safeLedger.intentFrame(),
                safeValidation,
                safeEnvelope,
                stopReason,
                resultStatus
        );
        return new ReadingResearchTrace(
                "research-harness-artifacts/v1",
                intentFrame(safeQuestionId, safeLedger, safeEnvelope),
                retrievalPlan(safeQuestionId, safeLedger, safeArtifacts, references),
                evidenceLedger(safeQuestionId, evidenceItems, safeArtifacts),
                claimGraph,
                reasoningArtifacts,
                verificationPass,
                researchAnswer(safeQuestionId, safeEnvelope, claimGraph, evidenceItems, reasoningArtifacts, verificationPass, resultStatus)
        );
    }

    private ReadingResearchTrace.IntentFrameArtifact intentFrame(String questionId,
                                                                 ReadingTurnObservationLedger ledger,
                                                                 AnswerEnvelope envelope) {
        ReadingIntentFrame intentFrame = ledger.intentFrame();
        List<String> paperMentions = paperMentions(ledger);
        List<String> methodMentions = new ArrayList<>();
        methodMentions.addAll(intentFrame.locationIntents());
        methodMentions.addAll(intentFrame.sectionRoles());
        List<String> entities = new ArrayList<>();
        entities.addAll(paperMentions);
        entities.addAll(intentFrame.paperQueryTexts());
        entities.addAll(intentFrame.locationQueryTexts());
        return new ReadingResearchTrace.IntentFrameArtifact(
                questionId,
                firstNonBlank(intentFrame.originalUserRequest(), ledger.userGoal()),
                normalizeQuestion(firstNonBlank(intentFrame.originalUserRequest(), ledger.userGoal())),
                entities,
                paperMentions,
                methodMentions,
                List.of(),
                intentFrameConstraints(intentFrame),
                answerType(envelope.answerType()),
                ambiguityStatus(envelope),
                requiredEvidenceTypes(envelope.answerType()),
                requiredCapabilities(intentFrame, envelope.answerType())
        );
    }

    private ReadingResearchTrace.RetrievalPlanArtifact retrievalPlan(String questionId,
                                                                     ReadingTurnObservationLedger ledger,
                                                                     ReadingTurnArtifacts artifacts,
                                                                     List<Map<String, Object>> references) {
        List<ReadingResearchTrace.StrategyStep> steps = new ArrayList<>();
        int stepIndex = 1;
        if (!ledger.sessionStatePayload().isEmpty()) {
            steps.add(strategyStep(stepIndex++, "metadata_filter", "fixed readable-paper scope", List.of("metadata"), "observed"));
        }
        if (!ledger.productStateItems().isEmpty()) {
            steps.add(strategyStep(stepIndex++, paperChoiceStrategy(ledger), "candidate papers", List.of("metadata"), "observed"));
        }
        if (semanticLocationEvidenceMissing(ledger) && ledger.locationPayloads().isEmpty()) {
            steps.add(strategyStep(stepIndex++, "semantic_search", "readable locations", List.of("paragraph", "metadata"), "missing"));
        }
        if (!ledger.locationPayloads().isEmpty()) {
            steps.add(strategyStep(stepIndex++, locationStrategy(ledger), "readable locations", List.of("paragraph", "metadata"), "observed"));
        }
        if (references != null && !references.isEmpty()) {
            steps.add(strategyStep(stepIndex, evidenceStrategy(ledger), "quoted source passages", List.of("paragraph"), "observed"));
        }
        if (steps.isEmpty()) {
            steps.add(strategyStep(stepIndex, "metadata_filter", "no retrieval observation", List.of(), "missing"));
        }
        return new ReadingResearchTrace.RetrievalPlanArtifact(
                "retrieval_plan_1",
                questionId,
                targetEntities(ledger),
                steps,
                expectedEvidenceTypes(artifacts),
                requiredRecallTargets(artifacts),
                "hard negatives must not be read or cited unless they are explicitly selected evidence",
                stopConditions(artifacts, references)
        );
    }

    private ReadingResearchTrace.StrategyStep strategyStep(int index,
                                                           String strategy,
                                                           String target,
                                                           List<String> evidenceTypes,
                                                           String status) {
        return new ReadingResearchTrace.StrategyStep(
                "retrieval_step_" + index,
                strategy,
                target,
                evidenceTypes,
                status
        );
    }

    private ReadingResearchTrace.EvidenceLedgerArtifact evidenceLedger(String questionId,
                                                                       List<ReadingResearchTrace.EvidenceItem> evidenceItems,
                                                                       ReadingTurnArtifacts artifacts) {
        return new ReadingResearchTrace.EvidenceLedgerArtifact(
                "evidence_ledger_1",
                questionId,
                evidenceItems,
                List.of(),
                artifacts.missingEvidence().missing()
        );
    }

    private List<ReadingResearchTrace.EvidenceItem> evidenceItems(ReadingTurnObservationLedger ledger,
                                                                  List<Map<String, Object>> references,
                                                                  Map<String, List<String>> supportClaimIdsByRef) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<ReadingResearchTrace.EvidenceItem> items = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> reference : references) {
            String sourceQuoteRef = stringValue(reference.get("sourceQuoteRef"));
            String locationRef = stringValue(reference.get("locationRef"));
            Map<String, Object> location = ledger.locationPayloads().getOrDefault(locationRef, Map.of());
            items.add(new ReadingResearchTrace.EvidenceItem(
                    "evidence_" + index,
                    sourceQuoteRef,
                    stringValue(reference.get("paperId")),
                    firstNonBlank(stringValue(reference.get("paperTitle")), stringValue(location.get("title"))),
                    stringValue(reference.get("paperVersion")),
                    firstNonBlank(stringValue(reference.get("sectionTitle")), stringValue(location.get("sectionTitle")), stringValue(location.get("heading"))),
                    integerValue(reference.get("pageNumber")),
                    locationRef,
                    elementType(reference),
                    stringValue(reference.get("content")),
                    stringValue(reference.get("bboxOrCellRef")),
                    retrievalStrategyForLocation(location),
                    null,
                    evidenceQuality(reference),
                    supportClaimIdsByRef.getOrDefault(sourceQuoteRef, List.of()),
                    List.of()
            ));
            index += 1;
        }
        return List.copyOf(items);
    }

    private ReadingResearchTrace.ClaimGraphArtifact claimGraph(String questionId,
                                                               List<ReadingResearchTrace.ClaimNode> claims) {
        List<ReadingResearchTrace.ClaimEvidenceEdge> edges = new ArrayList<>();
        for (ReadingResearchTrace.ClaimNode claim : claims) {
            for (String evidenceId : claim.supportingEvidenceIds()) {
                edges.add(new ReadingResearchTrace.ClaimEvidenceEdge(claim.claimId(), evidenceId, "supports"));
            }
            for (String evidenceId : claim.refutingEvidenceIds()) {
                edges.add(new ReadingResearchTrace.ClaimEvidenceEdge(claim.claimId(), evidenceId, "refutes"));
            }
        }
        return new ReadingResearchTrace.ClaimGraphArtifact(
                "claim_graph_1",
                questionId,
                claims,
                edges
        );
    }

    private List<ReadingResearchTrace.ClaimNode> claimNodes(AnswerEnvelope envelope,
                                                            Map<String, String> evidenceIdsBySourceQuoteRef) {
        List<ReadingResearchTrace.ClaimNode> claims = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> rawClaim : envelope.evidenceBasedClaims()) {
            String claimId = "claim_" + index;
            String claimText = stringValue(rawClaim.get("claim"));
            List<String> supportEvidenceIds = stringList(rawClaim.get("sourceQuoteRefs")).stream()
                    .map(evidenceIdsBySourceQuoteRef::get)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .toList();
            boolean supported = !claimText.isBlank() && !supportEvidenceIds.isEmpty();
            claims.add(new ReadingResearchTrace.ClaimNode(
                    claimId,
                    claimText,
                    "paper_content",
                    supportEvidenceIds,
                    List.of(),
                    supported ? "supported" : "underdetermined",
                    supported ? "high" : "none",
                    List.of()
            ));
            index += 1;
        }
        return List.copyOf(claims);
    }

    private List<ReadingResearchTrace.ReasoningArtifact> reasoningArtifacts(String questionId,
                                                                            ReadingTurnArtifacts artifacts,
                                                                            ReadingResearchTrace.ClaimGraphArtifact claimGraph,
                                                                            List<ReadingResearchTrace.EvidenceItem> evidenceItems) {
        List<ReadingResearchTrace.ReasoningArtifact> results = new ArrayList<>();
        List<String> claimIds = claimGraph.claims().stream().map(ReadingResearchTrace.ClaimNode::claimId).toList();
        List<String> evidenceIds = evidenceItems.stream().map(ReadingResearchTrace.EvidenceItem::evidenceId).toList();
        if (!artifacts.claimEvidencePanel().rows().isEmpty()) {
            results.add(reasoningArtifact(
                    "reasoning_artifact_1",
                    questionId,
                    "uncertainty_boundary_report",
                    "Claim evidence boundary",
                    claimIds,
                    evidenceIds,
                    Map.of("rows", artifacts.claimEvidencePanel().rows(), "missingEvidence", artifacts.missingEvidence())
            ));
            return List.copyOf(results);
        }
        if (!artifacts.readingPlan().steps().isEmpty()) {
            results.add(reasoningArtifact(
                    "reasoning_artifact_1",
                    questionId,
                    "multi_hop_chain",
                    "Reading plan",
                    claimIds,
                    evidenceIds,
                    Map.of("steps", artifacts.readingPlan().steps(), "missingEvidence", artifacts.missingEvidence())
            ));
            return List.copyOf(results);
        }
        if (!artifacts.paperShortlist().items().isEmpty()) {
            results.add(reasoningArtifact(
                    "reasoning_artifact_1",
                    questionId,
                    "constraint_filter_table",
                    "Paper shortlist",
                    claimIds,
                    evidenceIds,
                    Map.of("items", artifacts.paperShortlist().items(), "missingEvidence", artifacts.missingEvidence())
            ));
            return List.copyOf(results);
        }
        results.add(reasoningArtifact(
                "reasoning_artifact_1",
                questionId,
                "uncertainty_boundary_report",
                "Missing evidence report",
                claimIds,
                evidenceIds,
                Map.of("missingEvidence", artifacts.missingEvidence(), "uncertaintyNotes", artifacts.uncertaintyNotes())
        ));
        return List.copyOf(results);
    }

    private ReadingResearchTrace.ReasoningArtifact reasoningArtifact(String artifactId,
                                                                     String questionId,
                                                                     String type,
                                                                     String title,
                                                                     List<String> sourceClaimIds,
                                                                     List<String> sourceEvidenceIds,
                                                                     Map<String, Object> payload) {
        return new ReadingResearchTrace.ReasoningArtifact(
                artifactId,
                questionId,
                type,
                title,
                sourceClaimIds,
                sourceEvidenceIds,
                payload
        );
    }

    private ReadingResearchTrace.VerificationPassArtifact verificationPass(String questionId,
                                                                           ReadingIntentFrame intentFrame,
                                                                           ReadingArtifactContractValidation validation,
                                                                           AnswerEnvelope envelope,
                                                                           ProductStopReason stopReason,
                                                                           ProductResultStatus resultStatus) {
        boolean complete = resultStatus == ProductResultStatus.COMPLETED && validation.valid();
        return new ReadingResearchTrace.VerificationPassArtifact(
                "verification_pass_1",
                questionId,
                complete,
                requiredCapabilities(intentFrame, envelope.answerType()),
                validation.valid() ? "satisfied" : "missing",
                unsupportedClaimCount(envelope, validation),
                0,
                validation.missingFields(),
                ambiguityStatus(envelope),
                validation.valid() ? List.of("artifact_contract_satisfied") : List.of("artifact_contract_failed"),
                resultStatus == ProductResultStatus.INCOMPLETE_PRECISE
                        || envelope.answerType() == AnswerType.INSUFFICIENT_EVIDENCE,
                validation.valid() ? List.of("reading_artifact_contract") : List.of(),
                validation.valid() ? List.of() : List.of("reading_artifact_contract"),
                resultStatus == null ? "" : resultStatus.name(),
                stopReason == null ? "" : stopReason.name()
        );
    }

    private ReadingResearchTrace.ResearchAnswerArtifact researchAnswer(String questionId,
                                                                       AnswerEnvelope envelope,
                                                                       ReadingResearchTrace.ClaimGraphArtifact claimGraph,
                                                                       List<ReadingResearchTrace.EvidenceItem> evidenceItems,
                                                                       List<ReadingResearchTrace.ReasoningArtifact> reasoningArtifacts,
                                                                       ReadingResearchTrace.VerificationPassArtifact verificationPass,
                                                                       ProductResultStatus resultStatus) {
        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, Object> summarySection = new LinkedHashMap<>();
        summarySection.put("type", "summary");
        summarySection.put("text", envelope.answer());
        sections.add(Map.copyOf(summarySection));
        return new ReadingResearchTrace.ResearchAnswerArtifact(
                "research_answer_1",
                questionId,
                answerStatus(envelope, resultStatus),
                answerType(envelope.answerType()),
                envelope.answer(),
                sections,
                claimGraph.claims().stream().map(ReadingResearchTrace.ClaimNode::claimId).toList(),
                evidenceItems.stream().map(ReadingResearchTrace.EvidenceItem::evidenceId).toList(),
                reasoningArtifacts.stream().map(ReadingResearchTrace.ReasoningArtifact::artifactId).toList(),
                verificationPass.verificationId()
        );
    }

    private Map<String, String> evidenceIdsBySourceQuoteRef(List<Map<String, Object>> references) {
        if (references == null || references.isEmpty()) {
            return Map.of();
        }
        Map<String, String> ids = new LinkedHashMap<>();
        int index = 1;
        for (Map<String, Object> reference : references) {
            String sourceQuoteRef = stringValue(reference.get("sourceQuoteRef"));
            if (!sourceQuoteRef.isBlank()) {
                ids.putIfAbsent(sourceQuoteRef, "evidence_" + index);
            }
            index += 1;
        }
        return Map.copyOf(ids);
    }

    private Map<String, List<String>> supportClaimIdsBySourceQuoteRef(AnswerEnvelope envelope) {
        if (envelope == null || envelope.evidenceBasedClaims().isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> mutable = new LinkedHashMap<>();
        int index = 1;
        for (Map<String, Object> rawClaim : envelope.evidenceBasedClaims()) {
            String claimId = "claim_" + index;
            for (String ref : stringList(rawClaim.get("sourceQuoteRefs"))) {
                mutable.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(claimId);
            }
            index += 1;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : mutable.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private String answerType(AnswerType answerType) {
        if (answerType == AnswerType.EVIDENCE_ANSWER) {
            return "definition_trace";
        }
        if (answerType == AnswerType.PRODUCT_STATE) {
            return "constraint_filter";
        }
        if (answerType == AnswerType.CLARIFICATION_NEEDED) {
            return "ambiguity_resolution";
        }
        if (answerType == AnswerType.INSUFFICIENT_EVIDENCE) {
            return "uncertainty_boundary";
        }
        return "uncertainty_boundary";
    }

    private String ambiguityStatus(AnswerEnvelope envelope) {
        if (envelope.answerType() == AnswerType.CLARIFICATION_NEEDED) {
            return "needs_user_choice";
        }
        if (envelope.answerType() == AnswerType.INSUFFICIENT_EVIDENCE) {
            return "unanswerable_from_available_evidence";
        }
        return "unambiguous";
    }

    private List<String> requiredEvidenceTypes(AnswerType answerType) {
        if (answerType == AnswerType.EVIDENCE_ANSWER) {
            return List.of("paragraph");
        }
        if (answerType == AnswerType.PRODUCT_STATE) {
            return List.of("metadata");
        }
        return List.of("metadata", "paragraph");
    }

    private List<String> requiredCapabilities(ReadingIntentFrame intentFrame, AnswerType answerType) {
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        if (intentFrame != null && !intentFrame.paperQueryTexts().isEmpty()) {
            capabilities.add("paper_discovery");
        }
        if (intentFrame != null && !intentFrame.locationQueryTexts().isEmpty()) {
            capabilities.add("reading_location_retrieval_plan");
        }
        if (answerType == AnswerType.EVIDENCE_ANSWER) {
            capabilities.add("source_quote_grounding");
            capabilities.add("claim_verification");
        }
        if (answerType == AnswerType.PRODUCT_STATE) {
            capabilities.add("metadata_navigation");
        }
        if (capabilities.isEmpty()) {
            capabilities.add("reading_context_validation");
        }
        return List.copyOf(capabilities);
    }

    private String answerStatus(AnswerEnvelope envelope, ProductResultStatus resultStatus) {
        if (resultStatus == ProductResultStatus.INCOMPLETE_PRECISE) {
            return "abstained";
        }
        if (envelope.answerType() == AnswerType.CLARIFICATION_NEEDED) {
            return "needs_clarification";
        }
        if (envelope.answerType() == AnswerType.INSUFFICIENT_EVIDENCE) {
            return "abstained";
        }
        if (envelope.answerType() == AnswerType.EVIDENCE_ANSWER || envelope.answerType() == AnswerType.PRODUCT_STATE) {
            return "answered";
        }
        return "partial_with_limits";
    }

    private int unsupportedClaimCount(AnswerEnvelope envelope, ReadingArtifactContractValidation validation) {
        if (envelope.answerType() != AnswerType.EVIDENCE_ANSWER) {
            return 0;
        }
        return validation.valid() ? 0 : Math.max(1, envelope.evidenceBasedClaims().size());
    }

    private List<String> paperMentions(ReadingTurnObservationLedger ledger) {
        List<String> mentions = new ArrayList<>();
        for (Map<String, Object> paper : ledger.paperPayloadsByHandle().values()) {
            String title = stringValue(paper.get("title"));
            String filename = stringValue(paper.get("originalFilename"));
            if (!title.isBlank()) {
                mentions.add(title);
            } else if (!filename.isBlank()) {
                mentions.add(filename);
            }
        }
        return dedupe(mentions);
    }

    private List<String> targetEntities(ReadingTurnObservationLedger ledger) {
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        entities.addAll(paperMentions(ledger));
        entities.addAll(ledger.intentFrame().paperQueryTexts());
        entities.addAll(ledger.intentFrame().locationQueryTexts());
        return List.copyOf(entities);
    }

    private List<String> intentFrameConstraints(ReadingIntentFrame intentFrame) {
        List<String> constraints = new ArrayList<>();
        if (!intentFrame.readingAction().isBlank()) {
            constraints.add("explicit reading action: " + intentFrame.readingAction());
        }
        if (!intentFrame.sourceLanguages().isEmpty()) {
            constraints.add("source language: " + String.join(", ", intentFrame.sourceLanguages()));
        }
        if (!intentFrame.retrievalLanguages().isEmpty()) {
            constraints.add("retrieval language: " + String.join(", ", intentFrame.retrievalLanguages()));
        }
        return List.copyOf(constraints);
    }

    private String paperChoiceStrategy(ReadingTurnObservationLedger ledger) {
        boolean hasPaperQuery = !ledger.intentFrame().paperQueryTexts().isEmpty();
        return hasPaperQuery ? "semantic_search" : "metadata_filter";
    }

    private String locationStrategy(ReadingTurnObservationLedger ledger) {
        return ledger.locationPayloads().values().stream()
                .anyMatch(location -> "find_reading_locations".equals(stringValue(location.get("sourceTool"))))
                ? "semantic_search"
                : "metadata_filter";
    }

    private String evidenceStrategy(ReadingTurnObservationLedger ledger) {
        return ledger.locationPayloads().isEmpty() ? "metadata_filter" : locationStrategy(ledger);
    }

    private String retrievalStrategyForLocation(Map<String, Object> location) {
        String sourceTool = stringValue(location.get("sourceTool"));
        if ("find_reading_locations".equals(sourceTool)) {
            return "semantic_search";
        }
        if ("list_paper_locations".equals(sourceTool) || "get_paper_outline".equals(sourceTool)) {
            return "metadata_filter";
        }
        return "metadata_filter";
    }

    private List<String> expectedEvidenceTypes(ReadingTurnArtifacts artifacts) {
        if (!artifacts.claimEvidencePanel().rows().isEmpty()) {
            return List.of("paragraph");
        }
        if (!artifacts.readingPlan().steps().isEmpty()) {
            return List.of("paragraph", "metadata");
        }
        if (!artifacts.paperShortlist().items().isEmpty()) {
            return List.of("metadata");
        }
        return List.of();
    }

    private List<String> requiredRecallTargets(ReadingTurnArtifacts artifacts) {
        if (!artifacts.claimEvidencePanel().rows().isEmpty()) {
            return List.of("at least one source quote for each cited claim");
        }
        if (!artifacts.readingPlan().steps().isEmpty()) {
            return List.of("at least one readable location");
        }
        if (!artifacts.paperShortlist().items().isEmpty()) {
            return List.of("3 to 5 beginner-suitable paper cards when doing topic discovery");
        }
        return List.of("one checkable reading target");
    }

    private List<String> stopConditions(ReadingTurnArtifacts artifacts, List<Map<String, Object>> references) {
        if (references != null && !references.isEmpty()) {
            return List.of("all cited claims have source quote references");
        }
        if (!artifacts.missingEvidence().missing().isEmpty()) {
            return List.of("required evidence is missing: " + String.join(", ", artifacts.missingEvidence().missing()));
        }
        return List.of("artifact contract reached a terminal state");
    }

    private boolean semanticLocationEvidenceMissing(ReadingTurnObservationLedger ledger) {
        if (ledger == null || ledger.retrievalStatusPayload().isEmpty()) {
            return false;
        }
        Map<String, Object> status = objectMap(ledger.retrievalStatusPayload().get("semanticLocationEvidence"));
        return "semantic_location_evidence".equals(stringValue(status.get("missingEvidence")));
    }

    private String elementType(Map<String, Object> reference) {
        String contentKind = stringValue(reference.get("contentKind")).toLowerCase(Locale.ROOT);
        if (contentKind.contains("table")) {
            return "table";
        }
        if (contentKind.contains("figure")) {
            return "figure";
        }
        return "paragraph";
    }

    private String evidenceQuality(Map<String, Object> reference) {
        boolean hasQuote = !stringValue(reference.get("content")).isBlank();
        boolean hasPaper = !stringValue(reference.get("paperId")).isBlank();
        boolean hasLocation = !stringValue(reference.get("locationRef")).isBlank();
        return hasQuote && hasPaper && hasLocation ? "source_grounded" : "incomplete";
    }

    private String normalizeQuestion(String value) {
        return stringValue(value).replaceAll("\\s+", " ");
    }

    private List<String> dedupe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::stringValue)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        return rawValues.stream()
                .map(this::stringValue)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return Map.copyOf(result);
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
