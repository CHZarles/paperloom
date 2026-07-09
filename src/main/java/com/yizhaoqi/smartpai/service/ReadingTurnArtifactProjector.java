package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ReadingTurnArtifactProjector {

    private static final int MAX_PAPER_SHORTLIST_ITEMS = 5;
    private static final int MAX_READING_PLAN_ITEMS = 8;
    private static final String LIST_LOCATIONS_ACTION = "LIST_LOCATIONS";
    private static final Set<String> BEGINNER_PAPER_ROLES = Set.of(
            "survey",
            "benchmark",
            "method",
            "critique",
            "background",
            "example"
    );

    public ReadingTurnProjection project(ReadingTurnObservationLedger ledger,
                                         AnswerEnvelope envelope,
                                         List<Map<String, Object>> citationReferences,
                                         Map<String, Integer> citationNumbersByRef) {
        ReadingTurnArtifacts artifacts = artifacts(ledger, envelope, citationReferences, citationNumbersByRef);
        return new ReadingTurnProjection(artifacts, statePatch(artifacts));
    }

    private ReadingTurnArtifacts artifacts(ReadingTurnObservationLedger ledger,
                                           AnswerEnvelope envelope,
                                           List<Map<String, Object>> citationReferences,
                                           Map<String, Integer> citationNumbersByRef) {
        if (ledger == null) {
            return ReadingTurnArtifacts.empty("");
        }
        Map<String, Object> searchScope = objectMap(ledger.sessionStatePayload().get("searchScope"));
        List<ReadingTurnArtifacts.PaperShortlistItem> paperShortlist =
                paperShortlistArtifacts(ledger, envelope, citationReferences);
        List<ReadingTurnArtifacts.ReadingPlanStep> readingPlan = readingPlanArtifacts(ledger);
        List<ReadingTurnArtifacts.ClaimEvidenceRow> evidenceRows = claimEvidenceArtifacts(
                envelope,
                citationReferences,
                citationNumbersByRef
        );
        List<String> uncertaintyNotes = uncertaintyNotes(ledger, paperShortlist, readingPlan, evidenceRows);
        return new ReadingTurnArtifacts(
                "reading-turn-artifacts/v1",
                new ReadingTurnArtifacts.GoalCard(
                        safeVisibleGoal(ledger.userGoal()),
                        stringValue(searchScope.get("label")),
                        integerValue(searchScope.get("readablePaperCount")),
                        Boolean.TRUE.equals(searchScope.get("immutable")) || !searchScope.isEmpty(),
                        goalActions()
                ),
                ledger.intentFrame(),
                new ReadingTurnArtifacts.PaperShortlist(paperShortlist),
                new ReadingTurnArtifacts.ReadingPlan(readingPlan),
                new ReadingTurnArtifacts.ClaimEvidencePanel(evidenceRows),
                missingEvidenceArtifact(ledger, paperShortlist, readingPlan, evidenceRows, uncertaintyNotes),
                topLevelActions(paperShortlist, readingPlan, evidenceRows),
                uncertaintyNotes,
                ReadingTurnArtifacts.ResearchTraceSummary.empty()
        );
    }

    private List<ReadingTurnArtifacts.PaperShortlistItem> paperShortlistArtifacts(
            ReadingTurnObservationLedger ledger,
            AnswerEnvelope envelope,
            List<Map<String, Object>> citationReferences) {
        List<ReadingTurnArtifacts.PaperShortlistItem> items = new ArrayList<>();
        Map<String, PaperRoleEvidence> quoteBackedRoles =
                quoteBackedRoleEvidenceByPaperHandle(envelope, citationReferences);
        int index = 0;
        for (Map<String, Object> item : ledger.productStateItems()) {
            if (items.size() >= MAX_PAPER_SHORTLIST_ITEMS) {
                break;
            }
            String title = stringValue(item.get("title"));
            String filename = stringValue(item.get("originalFilename"));
            if (title.isBlank() && filename.isBlank()) {
                continue;
            }
            String paperHandle = stringValue(item.get("paperHandle"));
            Map<String, Object> paperPayload = ledger.paperPayloadsByHandle().getOrDefault(paperHandle, Map.of());
            String paperId = stringValue(paperPayload.get("paperId"));
            PaperRoleEvidence roleEvidence = paperRoleEvidence(paperPayload);
            if (roleEvidence.role().isBlank()) {
                roleEvidence = quoteBackedRoles.getOrDefault(paperHandle, roleEvidence);
            }
            index += 1;
            items.add(new ReadingTurnArtifacts.PaperShortlistItem(
                    paperId,
                    paperHandle,
                    title,
                    filename,
                    stringList(item.get("authors")),
                    integerValue(item.get("year")),
                    stringValue(item.get("venue")),
                    roleEvidence.role(),
                    roleEvidence.status(),
                    roleEvidence.source(),
                    matchReason(item),
                    shortlistEvidenceStatus(roleEvidence),
                    Boolean.TRUE.equals(item.get("ambiguous")),
                    paperActions(paperId, paperHandle, title, filename)
            ));
        }
        return List.copyOf(items);
    }

    private List<ReadingTurnArtifacts.ReadingPlanStep> readingPlanArtifacts(ReadingTurnObservationLedger ledger) {
        List<ReadingTurnArtifacts.ReadingPlanStep> steps = new ArrayList<>();
        for (Map<String, Object> location : orderedLocations(ledger.locationPayloads().values())) {
            if (steps.size() >= MAX_READING_PLAN_ITEMS) {
                break;
            }
            String label = locationLabel(location);
            if (label.isBlank()) {
                continue;
            }
            String paperId = stringValue(location.get("paperId"));
            String paperHandle = stringValue(location.get("paperHandle"));
            String paperTitle = stringValue(location.get("title"));
            String originalFilename = stringValue(location.get("originalFilename"));
            steps.add(new ReadingTurnArtifacts.ReadingPlanStep(
                    paperId,
                    paperHandle,
                    stringValue(location.get("locationRef")),
                    paperTitle,
                    label,
                    snippet(stringValue(location.get("preview")), 180),
                    "navigation-only; this location has not been read as quoted evidence yet",
                    locationActions(
                            paperId,
                            paperHandle,
                            paperTitle,
                            originalFilename,
                            stringValue(location.get("locationRef")),
                            label
                    )
            ));
        }
        return List.copyOf(steps);
    }

    private List<Map<String, Object>> orderedLocations(Iterable<Map<String, Object>> locations) {
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (Map<String, Object> location : locations == null ? List.<Map<String, Object>>of() : locations) {
            ordered.add(location);
        }
        ordered.sort(Comparator.comparingInt(this::locationRank));
        return ordered;
    }

    private int locationRank(Map<String, Object> location) {
        String sectionRole = normalized(stringValue(location.get("sectionRole")));
        String label = normalized(firstNonBlank(
                stringValue(location.get("label")),
                stringValue(location.get("sectionTitle")),
                stringValue(location.get("heading"))
        ));
        String locationType = normalized(stringValue(location.get("locationType")));
        if ("ABSTRACT".equals(sectionRole) || "ABSTRACT".equals(label)) {
            return 0;
        }
        if ("INTRODUCTION".equals(sectionRole) || label.contains("INTRODUCTION")) {
            return 1;
        }
        if ("SECTION".equals(locationType) || !sectionRole.isBlank()) {
            return 2;
        }
        if ("PAGE".equals(locationType)) {
            return 3;
        }
        return 4;
    }

    private List<ReadingTurnArtifacts.ClaimEvidenceRow> claimEvidenceArtifacts(
            AnswerEnvelope envelope,
            List<Map<String, Object>> citationReferences,
            Map<String, Integer> citationNumbersByRef) {
        if (citationReferences == null || citationReferences.isEmpty()) {
            return List.of();
        }
        Map<String, String> claimsByRef = claimTextBySourceQuoteRef(envelope);
        List<ReadingTurnArtifacts.ClaimEvidenceRow> rows = new ArrayList<>();
        for (Map<String, Object> reference : citationReferences) {
            String sourceQuoteRef = stringValue(reference.get("sourceQuoteRef"));
            if (sourceQuoteRef.isBlank()) {
                continue;
            }
            Integer number = citationNumbersByRef == null ? null : citationNumbersByRef.get(sourceQuoteRef);
            String marker = number == null ? "" : "[" + number + "]";
            String quote = snippet(stringValue(reference.get("content")), 260);
            String claim = firstNonBlank(
                    cleanClaimText(claimsByRef.get(sourceQuoteRef)),
                    quote.isBlank() ? "a relevant passage was found for the selected location" : quote
            );
            String paperId = stringValue(reference.get("paperId"));
            String paperHandle = stringValue(reference.get("paperHandle"));
            String locationRef = stringValue(reference.get("locationRef"));
            String locationLabel = sourceQuoteLabel(reference);
            rows.add(new ReadingTurnArtifacts.ClaimEvidenceRow(
                    claim,
                    quote,
                    marker,
                    sourceQuoteRef,
                    paperId,
                    paperHandle,
                    stringValue(reference.get("paperTitle")),
                    locationRef,
                    locationLabel,
                    stringValue(reference.get("contentKind")),
                    List.of("broader claims outside this passage", "missing visual details", "results not stated in the quote"),
                    sourceQuoteActions(sourceQuoteRef, paperId, paperHandle, locationRef, marker)
            ));
        }
        return List.copyOf(rows);
    }

    private ReadingTurnArtifacts.MissingEvidence missingEvidenceArtifact(
            ReadingTurnObservationLedger ledger,
            List<ReadingTurnArtifacts.PaperShortlistItem> paperShortlist,
            List<ReadingTurnArtifacts.ReadingPlanStep> readingPlan,
            List<ReadingTurnArtifacts.ClaimEvidenceRow> evidenceRows,
            List<String> uncertaintyNotes) {
        if (!evidenceRows.isEmpty()) {
            return new ReadingTurnArtifacts.MissingEvidence(
                    List.of("visual_pdf_page_evidence"),
                    "The quoted text is available; visual PDF/page evidence is only proven when the citation detail reports it.",
                    List.of()
            );
        }
        List<String> missing = new ArrayList<>();
        if (!paperShortlist.isEmpty()) {
            missing.add("paper_content_quote");
            if (paperShortlist.stream().anyMatch(item -> item.role().isBlank())) {
                missing.add("paper_role_metadata");
            }
        }
        if (!readingPlan.isEmpty()) {
            missing.add("read_location_quote");
        }
        if (semanticLocationEvidenceMissing(ledger)) {
            missing.add("semantic_location_evidence");
        }
        if (paperShortlist.isEmpty() && readingPlan.isEmpty()) {
            missing.add("checkable_reading_target");
        }
        return new ReadingTurnArtifacts.MissingEvidence(
                missing,
                uncertaintyNotes == null || uncertaintyNotes.isEmpty()
                        ? "No quote-backed evidence has been attached to this turn."
                        : String.join(" ", uncertaintyNotes),
                List.of()
        );
    }

    private List<ReadingTurnArtifacts.UiAction> goalActions() {
        return List.of(new ReadingTurnArtifacts.UiAction(
                "REFINE_GOAL",
                "Refine goal",
                Map.of()
        ));
    }

    private List<ReadingTurnArtifacts.UiAction> topLevelActions(
            List<ReadingTurnArtifacts.PaperShortlistItem> paperShortlist,
            List<ReadingTurnArtifacts.ReadingPlanStep> readingPlan,
            List<ReadingTurnArtifacts.ClaimEvidenceRow> evidenceRows) {
        if (!evidenceRows.isEmpty()) {
            return evidenceRows.get(0).actions();
        }
        if (!readingPlan.isEmpty()) {
            return readingPlan.get(0).actions();
        }
        if (!paperShortlist.isEmpty()) {
            return paperShortlist.get(0).actions();
        }
        return List.of();
    }

    private List<ReadingTurnArtifacts.UiAction> paperActions(String paperId,
                                                             String paperHandle,
                                                             String title,
                                                             String originalFilename) {
        Map<String, Object> payload = compactPayload(Map.of(
                "paperId", stringValue(paperId),
                "paperHandle", stringValue(paperHandle),
                "paperTitle", stringValue(title),
                "originalFilename", stringValue(originalFilename)
        ));
        List<ReadingTurnArtifacts.UiAction> actions = new ArrayList<>();
        if (!payload.isEmpty()) {
            actions.add(new ReadingTurnArtifacts.UiAction("OPEN_PAPER", "Open paper", payload));
            Map<String, Object> listLocationsPayload = new LinkedHashMap<>(payload);
            listLocationsPayload.put("readingAction", LIST_LOCATIONS_ACTION);
            actions.add(new ReadingTurnArtifacts.UiAction(
                    "LIST_LOCATIONS",
                    "Show readable locations",
                    compactPayload(listLocationsPayload)
            ));
        }
        return List.copyOf(actions);
    }

    private List<ReadingTurnArtifacts.UiAction> locationActions(String paperId,
                                                                String paperHandle,
                                                                String paperTitle,
                                                                String originalFilename,
                                                                String locationRef,
                                                                String label) {
        Map<String, Object> payload = compactPayload(Map.of(
                "paperId", stringValue(paperId),
                "paperHandle", stringValue(paperHandle),
                "paperTitle", stringValue(paperTitle),
                "originalFilename", stringValue(originalFilename),
                "locationRef", stringValue(locationRef),
                "locationLabel", stringValue(label)
        ));
        if (payload.isEmpty()) {
            return List.of();
        }
        return List.of(new ReadingTurnArtifacts.UiAction("READ_LOCATION", "Read location", payload));
    }

    private List<ReadingTurnArtifacts.UiAction> sourceQuoteActions(String sourceQuoteRef,
                                                                   String paperId,
                                                                   String paperHandle,
                                                                   String locationRef,
                                                                   String citationMarker) {
        Map<String, Object> payload = compactPayload(Map.of(
                "sourceQuoteRef", stringValue(sourceQuoteRef),
                "paperId", stringValue(paperId),
                "paperHandle", stringValue(paperHandle),
                "locationRef", stringValue(locationRef),
                "citationMarker", stringValue(citationMarker)
        ));
        if (payload.isEmpty()) {
            return List.of();
        }
        return List.of(new ReadingTurnArtifacts.UiAction("OPEN_SOURCE_QUOTE", "Open citation", payload));
    }

    private ReadingStatePatch statePatch(ReadingTurnArtifacts artifacts) {
        if (artifacts == null) {
            return ReadingStatePatch.empty();
        }
        List<ReadingTurnArtifacts.PaperShortlistItem> shortlistItems = artifacts.paperShortlist().items();
        List<ReadingStatePatch.SelectedPaper> latestShortlist = shortlistItems.stream()
                .map(item -> new ReadingStatePatch.SelectedPaper(
                        item.paperId(),
                        item.paperHandle(),
                        item.title(),
                        item.originalFilename()
                ))
                .filter(ReadingStatePatch.SelectedPaper::hasIdentity)
                .toList();

        ReadingStatePatch.SelectedSourceQuote selectedSourceQuote = null;
        ReadingStatePatch.SelectedLocation selectedLocation = null;
        ReadingStatePatch.SelectedPaper selectedPaper = shortlistItems.stream()
                .filter(item -> !item.ambiguous())
                .map(item -> new ReadingStatePatch.SelectedPaper(
                        item.paperId(),
                        item.paperHandle(),
                        item.title(),
                        item.originalFilename()
                ))
                .filter(ReadingStatePatch.SelectedPaper::hasIdentity)
                .findFirst()
                .orElse(null);

        if (!artifacts.claimEvidencePanel().rows().isEmpty()) {
            ReadingTurnArtifacts.ClaimEvidenceRow row = artifacts.claimEvidencePanel().rows().get(0);
            selectedSourceQuote = new ReadingStatePatch.SelectedSourceQuote(
                    row.sourceQuoteRef(),
                    row.paperId(),
                    row.paperHandle(),
                    row.locationRef(),
                    row.citationMarker()
            );
            selectedLocation = new ReadingStatePatch.SelectedLocation(
                    row.paperId(),
                    row.paperHandle(),
                    row.locationRef(),
                    row.locationLabel()
            );
            selectedPaper = new ReadingStatePatch.SelectedPaper(
                    row.paperId(),
                    row.paperHandle(),
                    row.paperTitle(),
                    ""
            );
        } else if (!artifacts.readingPlan().steps().isEmpty()) {
            ReadingTurnArtifacts.ReadingPlanStep step = artifacts.readingPlan().steps().get(0);
            selectedLocation = new ReadingStatePatch.SelectedLocation(
                    step.paperId(),
                    step.paperHandle(),
                    step.locationRef(),
                    step.locationLabel()
            );
            selectedPaper = new ReadingStatePatch.SelectedPaper(
                    step.paperId(),
                    step.paperHandle(),
                    step.paperTitle(),
                    ""
            );
        }

        if (selectedPaper != null && !selectedPaper.hasIdentity()) {
            selectedPaper = null;
        }
        if (selectedLocation != null && !selectedLocation.hasIdentity()) {
            selectedLocation = null;
        }
        if (selectedSourceQuote != null && !selectedSourceQuote.hasIdentity()) {
            selectedSourceQuote = null;
        }
        return new ReadingStatePatch(selectedPaper, selectedLocation, selectedSourceQuote, latestShortlist);
    }

    private Map<String, String> claimTextBySourceQuoteRef(AnswerEnvelope envelope) {
        if (envelope == null) {
            return Map.of();
        }
        Map<String, String> claimsByRef = new LinkedHashMap<>();
        for (Map<String, Object> claim : envelope.evidenceBasedClaims()) {
            String claimText = cleanClaimText(stringValue(claim.get("claim")));
            for (String ref : stringList(claim.get("sourceQuoteRefs"))) {
                if (!ref.isBlank() && !claimText.isBlank()) {
                    claimsByRef.putIfAbsent(ref, claimText);
                }
            }
        }
        return Map.copyOf(claimsByRef);
    }

    private List<String> uncertaintyNotes(ReadingTurnObservationLedger ledger,
                                          List<ReadingTurnArtifacts.PaperShortlistItem> paperShortlist,
                                          List<ReadingTurnArtifacts.ReadingPlanStep> readingPlan,
                                          List<ReadingTurnArtifacts.ClaimEvidenceRow> evidenceRows) {
        List<String> notes = new ArrayList<>();
        if (!paperShortlist.isEmpty() && evidenceRows.isEmpty()) {
            if (paperShortlist.size() < 3) {
                notes.add("A complete recommendation needs 3 to 5 beginner shortlist papers; only "
                        + paperShortlist.size()
                        + (paperShortlist.size() == 1 ? " candidate was" : " candidates were")
                        + " observed.");
            }
            notes.add("The paper shortlist is metadata-only until a passage is read.");
            if (paperShortlist.stream().anyMatch(item -> item.role().isBlank())) {
                notes.add("Beginner paper roles were not inferred because explicit role metadata is missing.");
            }
        }
        if (!readingPlan.isEmpty() && evidenceRows.isEmpty()) {
            notes.add("The reading locations are navigation targets, not quote-backed claims yet.");
        }
        if (semanticLocationEvidenceMissing(ledger)) {
            notes.add("The typed in-paper search found no matching passage, and no outline fallback was used as evidence.");
        }
        if (evidenceRows.isEmpty()) {
            notes.add("No quoted paper passage has been verified in this answer.");
        } else {
            notes.add("Visual PDF/page evidence may still be unavailable unless the citation panel shows it.");
        }
        if (ledger != null && ledger.sessionStatePayload().isEmpty() && paperShortlist.isEmpty() && readingPlan.isEmpty()) {
            notes.add("The current scope count was not checked in this turn.");
        }
        return List.copyOf(notes);
    }

    private boolean semanticLocationEvidenceMissing(ReadingTurnObservationLedger ledger) {
        if (ledger == null || ledger.retrievalStatusPayload().isEmpty()) {
            return false;
        }
        Map<String, Object> semanticLocationEvidence =
                objectMap(ledger.retrievalStatusPayload().get("semanticLocationEvidence"));
        return "semantic_location_evidence".equals(stringValue(semanticLocationEvidence.get("missingEvidence")));
    }

    private Map<String, PaperRoleEvidence> quoteBackedRoleEvidenceByPaperHandle(
            AnswerEnvelope envelope,
            List<Map<String, Object>> citationReferences) {
        if (envelope == null || envelope.evidenceBasedClaims().isEmpty()
                || citationReferences == null || citationReferences.isEmpty()) {
            return Map.of();
        }
        Map<String, String> paperHandleBySourceQuoteRef = new LinkedHashMap<>();
        for (Map<String, Object> reference : citationReferences) {
            String sourceQuoteRef = stringValue(reference.get("sourceQuoteRef"));
            String paperHandle = stringValue(reference.get("paperHandle"));
            if (!sourceQuoteRef.isBlank() && !paperHandle.isBlank()) {
                paperHandleBySourceQuoteRef.put(sourceQuoteRef, paperHandle);
            }
        }
        if (paperHandleBySourceQuoteRef.isEmpty()) {
            return Map.of();
        }

        Map<String, String> roleByPaperHandle = new LinkedHashMap<>();
        Set<String> conflictingPaperHandles = new java.util.LinkedHashSet<>();
        for (Map<String, Object> claim : envelope.evidenceBasedClaims()) {
            String role = normalizedBeginnerRole(firstNonBlank(
                    stringValue(claim.get("beginnerRole")),
                    stringValue(claim.get("paperRole")),
                    stringValue(claim.get("role"))
            ));
            if (role.isBlank()) {
                continue;
            }
            List<String> refs = stringList(claim.get("sourceQuoteRefs"));
            if (refs.size() != 1) {
                continue;
            }
            String paperHandle = paperHandleBySourceQuoteRef.get(refs.get(0));
            if (paperHandle == null || paperHandle.isBlank() || conflictingPaperHandles.contains(paperHandle)) {
                continue;
            }
            String existingRole = roleByPaperHandle.get(paperHandle);
            if (existingRole != null && !existingRole.equals(role)) {
                roleByPaperHandle.remove(paperHandle);
                conflictingPaperHandles.add(paperHandle);
                continue;
            }
            roleByPaperHandle.put(paperHandle, role);
        }

        Map<String, PaperRoleEvidence> evidenceByPaperHandle = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : roleByPaperHandle.entrySet()) {
            evidenceByPaperHandle.put(entry.getKey(), new PaperRoleEvidence(
                    entry.getValue(),
                    "role backed by quoted evidence",
                    "quote_backed_evidence"
            ));
        }
        for (String paperHandle : conflictingPaperHandles) {
            evidenceByPaperHandle.putIfAbsent(paperHandle, new PaperRoleEvidence(
                    "",
                    "conflicting quote-backed beginner roles; no role assigned",
                    "conflicting_quote_backed_roles"
            ));
        }
        return Map.copyOf(evidenceByPaperHandle);
    }

    private String cleanClaimText(String value) {
        return stringValue(value);
    }

    private String safeVisibleGoal(String userGoal) {
        String goal = stringValue(userGoal).replaceAll("\\s+", " ");
        if (goal.isBlank() || containsVisibleInternalLeak(goal)) {
            return "unresolved paper-reading goal";
        }
        return snippet(goal, 180);
    }

    private Map<String, Object> compactPayload(Map<String, Object> rawPayload) {
        if (rawPayload == null || rawPayload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawPayload.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            payload.put(entry.getKey(), value);
        }
        return Map.copyOf(payload);
    }

    private String matchReason(Map<String, Object> item) {
        List<String> reasons = stringList(item == null ? null : item.get("matchReasons"));
        if (!reasons.isEmpty()) {
            return String.join("; ", reasons);
        }
        return "Matched by paper metadata in the current readable scope.";
    }

    private String shortlistEvidenceStatus(PaperRoleEvidence roleEvidence) {
        if (roleEvidence != null && "quote_backed_evidence".equals(roleEvidence.source())) {
            return "beginner role backed by quoted evidence; other claims need separate quotes";
        }
        return "metadata-only; no quoted passage has been read yet";
    }

    private PaperRoleEvidence paperRoleEvidence(Map<String, Object> paperPayload) {
        RoleSourceMatch explicitRole = firstExplicitRole(paperPayload, "paperRoles", "beginnerRoles", "readingRoles", "paperTypes");
        if (explicitRole != null) {
            return new PaperRoleEvidence(
                    explicitRole.role(),
                    "role metadata provided by " + explicitRole.source(),
                    explicitRole.source()
            );
        }
        boolean hasRoleLikeMetadata = hasAnyListValue(paperPayload, "paperRoles", "beginnerRoles", "readingRoles", "paperTypes");
        if (hasRoleLikeMetadata) {
            return new PaperRoleEvidence(
                    "",
                    "role metadata is present but none matches the beginner role taxonomy",
                    "unmapped_role_metadata"
            );
        }
        return new PaperRoleEvidence(
                "",
                "explicit role metadata is missing; no beginner role assigned",
                "missing_role_metadata"
        );
    }

    private RoleSourceMatch firstExplicitRole(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            for (String value : stringList(source.get(key))) {
                String role = normalizedBeginnerRole(value);
                if (!role.isBlank()) {
                    return new RoleSourceMatch(role, key);
                }
            }
        }
        return null;
    }

    private boolean hasAnyListValue(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (!stringList(source.get(key)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String normalizedBeginnerRole(String value) {
        String normalized = stringValue(value)
                .toLowerCase()
                .replace('_', '-')
                .replace(' ', '-');
        return BEGINNER_PAPER_ROLES.contains(normalized) ? normalized : "";
    }

    private String locationLabel(Map<String, Object> location) {
        String label = firstNonBlank(
                stringValue(location.get("label")),
                stringValue(location.get("sectionTitle")),
                stringValue(location.get("heading"))
        );
        String pageLabel = pageLabel(location);
        if (label.isBlank()) {
            return pageLabel.isBlank() ? "Location" : pageLabel;
        }
        if (pageLabel.isBlank()) {
            return label;
        }
        if (normalized(label).equals(normalized(pageLabel))) {
            return label;
        }
        return label + ", " + pageLabel;
    }

    private String pageLabel(Map<String, Object> location) {
        String page = firstNonBlank(stringValue(location.get("pageNumber")), stringValue(location.get("pageStart")));
        String pageEnd = firstNonBlank(stringValue(location.get("pageEndNumber")), stringValue(location.get("pageEnd")));
        if (page.isBlank()) {
            return "";
        }
        if (!pageEnd.isBlank() && !pageEnd.equals(page)) {
            return "pages " + page + "-" + pageEnd;
        }
        return "page " + page;
    }

    private String sourceQuoteLabel(Map<String, Object> quote) {
        String paperTitle = stringValue(quote.get("paperTitle"));
        String sectionTitle = stringValue(quote.get("sectionTitle"));
        String page = pageLabel(quote);
        List<String> parts = new ArrayList<>();
        if (!sectionTitle.isBlank() && !normalized(sectionTitle).equals(normalized(paperTitle))) {
            parts.add(sectionTitle);
        }
        if (!page.isBlank()) {
            parts.add(page);
        }
        return parts.isEmpty() ? "cited passage" : String.join(", ", parts);
    }

    private String normalized(String value) {
        return stringValue(value)
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
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

    @SuppressWarnings("unchecked")
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
        return result;
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

    private String snippet(String value, int maxLength) {
        String text = stringValue(value).replaceAll("\\s+", " ");
        if (maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)).trim() + "...";
    }

    private boolean containsVisibleInternalLeak(String text) {
        String value = text == null ? "" : text;
        List<String> visibleInternalTokens = List.of(
                "paper_handle_",
                "page_ref_",
                "section_ref_",
                "location_ref_",
                "source_quote_",
                "paperHandle",
                "locationRef",
                "sourceQuoteRef",
                "parserQuality",
                "parserName",
                "parserVersion",
                "AUTO_SOURCE",
                "AUTO_LIBRARY",
                "SOURCE_SET_SNAPSHOT",
                "immutable=true",
                "Source Quote",
                "get_session_state",
                "list_papers",
                "search_paper_candidates",
                "find_papers_by_identity",
                "get_paper_outline",
                "list_paper_locations",
                "find_reading_locations",
                "read_locations",
                "trace_source_quotes"
        );
        for (String token : visibleInternalTokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record PaperRoleEvidence(String role, String status, String source) {
    }

    private record RoleSourceMatch(String role, String source) {
    }
}
