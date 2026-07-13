package io.github.chzarles.paperloom.service;

import java.util.ArrayList;
import java.util.List;

public class ReadingAnswerPresenter {

    private static final int MAX_VISIBLE_SHORTLIST_ITEMS = 5;
    private static final String FIND_LOCATIONS_ACTION = "FIND_LOCATIONS";
    private static final String LIST_LOCATIONS_ACTION = "LIST_LOCATIONS";

    public String render(AnswerEnvelope envelope,
                         ReadingTurnArtifacts artifacts,
                         String renderedEvidenceMarkdown) {
        AnswerEnvelope safeEnvelope = envelope == null
                ? new AnswerEnvelope(AnswerType.NON_EVIDENCE, "", List.of(), List.of(), List.of(), List.of(), List.of(), "")
                : envelope;
        ReadingTurnArtifacts safeArtifacts = artifacts == null
                ? ReadingTurnArtifacts.empty("")
                : artifacts;
        AnswerType answerType = safeEnvelope.answerType();
        if (answerType == AnswerType.EVIDENCE_ANSWER) {
            return evidenceAnswer(safeEnvelope, safeArtifacts, renderedEvidenceMarkdown);
        }
        if (answerType == AnswerType.PRODUCT_STATE) {
            if (hasAmbiguousShortlist(safeArtifacts)) {
                return ambiguousPaperChoiceAnswer(safeArtifacts);
            }
            if (!safeArtifacts.paperShortlist().items().isEmpty() && !isExplicitLocationAction(safeArtifacts)) {
                return paperShortlistAnswer(safeEnvelope, safeArtifacts);
            }
            if (!safeArtifacts.readingPlan().steps().isEmpty()) {
                return readingPlanAnswer(safeEnvelope, safeArtifacts);
            }
            if (!safeArtifacts.paperShortlist().items().isEmpty()) {
                return paperShortlistAnswer(safeEnvelope, safeArtifacts);
            }
            return productStateAnswer(safeEnvelope, safeArtifacts);
        }
        if (answerType == AnswerType.INSUFFICIENT_EVIDENCE) {
            return insufficientEvidenceAnswer(safeEnvelope, safeArtifacts);
        }
        if (answerType == AnswerType.CLARIFICATION_NEEDED) {
            return clarificationAnswer(safeEnvelope, safeArtifacts);
        }
        return generalAnswer(safeEnvelope, safeArtifacts);
    }

    private String paperShortlistAnswer(AnswerEnvelope envelope, ReadingTurnArtifacts artifacts) {
        ReadingTurnArtifacts.PaperShortlistItem first = artifacts.paperShortlist().items().get(0);
        String startTitle = displayTitle(first.title(), first.originalFilename(), "the first paper card");
        StringBuilder answer = base(
                artifacts,
                "Start with " + startTitle + rolePhrase(first) + "; this shortlist is still metadata-only until we read a passage.",
                paperStartHereText(startTitle, first),
                "Open the paper card and check the title, abstract, and metadata before treating it as content evidence.",
                appendVerificationNotes(paperNotVerifiedText(first), visibleLimitations(envelope)),
                "Open " + startTitle + "'s abstract, then ask for quote-backed claims."
        );
        appendShortlist(answer, artifacts.paperShortlist().items());
        return answer.toString();
    }

    private boolean hasAmbiguousShortlist(ReadingTurnArtifacts artifacts) {
        return artifacts != null
                && !artifacts.paperShortlist().items().isEmpty()
                && artifacts.paperShortlist().items().stream().anyMatch(ReadingTurnArtifacts.PaperShortlistItem::ambiguous);
    }

    private boolean isExplicitLocationAction(ReadingTurnArtifacts artifacts) {
        if (artifacts == null || artifacts.intentFrame() == null) {
            return false;
        }
        String action = artifacts.intentFrame().readingAction();
        return LIST_LOCATIONS_ACTION.equals(action) || FIND_LOCATIONS_ACTION.equals(action);
    }

    private String ambiguousPaperChoiceAnswer(ReadingTurnArtifacts artifacts) {
        StringBuilder answer = base(
                artifacts,
                "I found multiple possible papers, so I have not selected one as the current reading target.",
                "Choose one paper card from the shortlist, because reading the wrong paper would make the next evidence step unreliable.",
                "Compare the visible title, filename, authors, year, and match reason on the paper cards before choosing.",
                "No paper has been selected and no quoted passage has been read yet.",
                "Choose one paper card so I can open its outline or read a specific passage."
        );
        appendShortlist(answer, artifacts.paperShortlist().items());
        return answer.toString();
    }

    private void appendShortlist(StringBuilder answer, List<ReadingTurnArtifacts.PaperShortlistItem> items) {
        List<String> rows = new ArrayList<>();
        int index = 1;
        for (ReadingTurnArtifacts.PaperShortlistItem item : items) {
            if (index > MAX_VISIBLE_SHORTLIST_ITEMS) {
                break;
            }
            String title = displayTitle(item.title(), item.originalFilename(), "");
            if (title.isBlank()) {
                continue;
            }
            StringBuilder row = new StringBuilder();
            row.append(index++).append(". ").append(title);
            if (item.year() != null) {
                row.append(" (").append(item.year()).append(")");
            }
            if (!item.venue().isBlank()) {
                row.append(", ").append(item.venue());
            }
            if (!item.role().isBlank()) {
                row.append(" - role: ").append(item.role());
            }
            if (!item.evidenceStatus().isBlank()) {
                row.append(" - ").append(item.evidenceStatus());
            }
            if (!item.roleEvidenceStatus().isBlank()) {
                row.append(" - ").append(item.roleEvidenceStatus());
            }
            rows.add(row.toString());
        }
        if (!rows.isEmpty()) {
            answer.append("\n\nCandidate papers:\n");
            for (String row : rows) {
                answer.append(row).append("\n");
            }
        }
    }

    private String readingPlanAnswer(AnswerEnvelope envelope, ReadingTurnArtifacts artifacts) {
        ReadingTurnArtifacts.ReadingPlanStep first = artifacts.readingPlan().steps().get(0);
        String location = firstNonBlank(first.locationLabel(), "the first readable location");
        String start = location;
        if (!first.paperTitle().isBlank()) {
            start = first.paperTitle() + ", " + location;
        }
        StringBuilder answer = base(
                artifacts,
                "Start with " + start + "; it is a navigation target until a quoted passage is read.",
                start + ", because it is the first concrete place to inspect.",
                "Open this page or section from the reading plan and read it before relying on any claim.",
                appendVerificationNotes(
                        firstNonBlank(first.evidenceStatus(), "These locations are not quote-backed evidence yet."),
                        visibleLimitations(envelope)
                ),
                "Open " + location + " and ask for a quote-backed summary."
        );
        appendPlan(answer, artifacts.readingPlan().steps());
        return answer.toString();
    }

    private void appendPlan(StringBuilder answer, List<ReadingTurnArtifacts.ReadingPlanStep> steps) {
        List<String> rows = new ArrayList<>();
        int index = 1;
        for (ReadingTurnArtifacts.ReadingPlanStep step : steps) {
            if (index > MAX_VISIBLE_SHORTLIST_ITEMS) {
                break;
            }
            String location = firstNonBlank(step.locationLabel(), "Readable location");
            StringBuilder row = new StringBuilder();
            row.append(index++).append(". ");
            if (!step.paperTitle().isBlank()) {
                row.append(step.paperTitle()).append(", ");
            }
            row.append(location);
            if (!step.preview().isBlank()) {
                row.append(" - ").append(step.preview());
            }
            rows.add(row.toString());
        }
        if (!rows.isEmpty()) {
            answer.append("\n\nReading plan:\n");
            for (String row : rows) {
                answer.append(row).append("\n");
            }
        }
    }

    private String evidenceAnswer(AnswerEnvelope envelope,
                                  ReadingTurnArtifacts artifacts,
                                  String renderedEvidenceMarkdown) {
        ReadingTurnArtifacts.ClaimEvidenceRow first = artifacts.claimEvidencePanel().rows().isEmpty()
                ? new ReadingTurnArtifacts.ClaimEvidenceRow(
                firstNonBlank(renderedEvidenceMarkdown, envelope.answer(), "No validated cited claim is available."),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of()
        )
                : artifacts.claimEvidencePanel().rows().get(0);
        String claim = firstNonBlank(first.claim(), stripMarkers(firstNonBlank(renderedEvidenceMarkdown, envelope.answer())));
        String quote = firstNonBlank(first.quote(), "the cited passage");
        String marker = firstNonBlank(first.citationMarker(), "");
        String location = firstNonBlank(first.locationLabel(), "the cited passage");
        String start = first.paperTitle().isBlank() ? location : first.paperTitle() + ", " + location;
        String shortAnswer = claim;
        if (!marker.isBlank() && !shortAnswer.contains(marker)) {
            shortAnswer = shortAnswer + " " + marker;
        }
        return base(
                artifacts,
                shortAnswer,
                start + ", because this is where the checked passage comes from.",
                "This quote proves: " + claim + (marker.isBlank() ? "" : " " + marker)
                        + " Quote: \"" + quote + "\"",
                "This quote cannot prove broader claims outside this passage, missing visual details, or results not stated here.",
                "Open the cited page or section and read the surrounding paragraph."
        ).toString();
    }

    private String productStateAnswer(AnswerEnvelope envelope, ReadingTurnArtifacts artifacts) {
        String scope = scopeText(artifacts);
        return base(
                artifacts,
                firstNonBlank(envelope.answer(), scope.isBlank()
                        ? "I checked the current reading context, but no paper content has been read yet."
                        : "The current reading scope is " + scope + "."),
                scope.isBlank() ? "the current reading scope" : scope,
                "Check the library scope indicator and paper cards in the UI.",
                uncertaintyText(artifacts, "This is session or metadata state, not paper-content evidence."),
                "Choose a paper card or ask for a quote-backed summary."
        ).toString();
    }

    private String insufficientEvidenceAnswer(AnswerEnvelope envelope, ReadingTurnArtifacts artifacts) {
        if (hasAmbiguousShortlist(artifacts)) {
            return ambiguousPaperChoiceAnswer(artifacts);
        }
        if (!artifacts.readingPlan().steps().isEmpty()) {
            return readingPlanAnswer(envelope, artifacts);
        }
        if (!artifacts.paperShortlist().items().isEmpty()) {
            return paperShortlistAnswer(envelope, artifacts);
        }
        return base(
                artifacts,
                firstNonBlank(envelope.answer(), "The current observations do not contain enough quote-backed evidence to answer that."),
                firstReadingTarget(artifacts),
                "Ask me to open a paper section or quote so the claim can be checked.",
                uncertaintyText(artifacts, "Required evidence is missing or has not been read yet."),
                "Pick one paper or section to read next."
        ).toString();
    }

    private String clarificationAnswer(AnswerEnvelope envelope, ReadingTurnArtifacts artifacts) {
        return base(
                artifacts,
                firstNonBlank(envelope.answer(), "The current reading target is ambiguous."),
                firstReadingTarget(artifacts),
                "Choose one paper, section, or citation from the visible options.",
                uncertaintyText(artifacts, "The current target is ambiguous."),
                "Choose one target so I can continue."
        ).toString();
    }

    private String generalAnswer(AnswerEnvelope envelope, ReadingTurnArtifacts artifacts) {
        return base(
                artifacts,
                firstNonBlank(envelope.answer(), "No validated reading artifact is available for this request."),
                firstReadingTarget(artifacts),
                "Use a paper card, section, page, or citation as the next checkable unit.",
                uncertaintyText(artifacts, "No quote-backed evidence has been attached to this answer yet."),
                "Choose a paper or ask for a quote-backed claim."
        ).toString();
    }

    private StringBuilder base(ReadingTurnArtifacts artifacts,
                               String shortAnswer,
                               String startHere,
                               String howToVerify,
                               String notVerified,
                               String nextStep) {
        StringBuilder builder = new StringBuilder();
        builder.append("I understand your goal as: ")
                .append(firstNonBlank(artifacts.goalCard().interpretedGoal(), "unresolved paper-reading goal"))
                .append(sentenceSuffix(artifacts.goalCard().interpretedGoal()))
                .append("\n\n");
        builder.append("Short answer: ").append(trimSentence(shortAnswer)).append("\n\n");
        builder.append("Start here: ").append(trimSentence(startHere)).append("\n\n");
        builder.append("How to verify: ").append(trimSentence(howToVerify)).append("\n\n");
        builder.append("Not verified yet: ").append(trimSentence(notVerified)).append("\n\n");
        builder.append("Next step: ").append(trimSentence(nextStep));
        return builder;
    }

    private String firstReadingTarget(ReadingTurnArtifacts artifacts) {
        if (!artifacts.paperShortlist().items().isEmpty()) {
            ReadingTurnArtifacts.PaperShortlistItem first = artifacts.paperShortlist().items().get(0);
            return displayTitle(first.title(), first.originalFilename(), "the first paper card");
        }
        if (!artifacts.readingPlan().steps().isEmpty()) {
            return firstNonBlank(artifacts.readingPlan().steps().get(0).locationLabel(), "the first readable location");
        }
        if (!artifacts.claimEvidencePanel().rows().isEmpty()) {
            return firstNonBlank(artifacts.claimEvidencePanel().rows().get(0).locationLabel(), "the cited passage");
        }
        String scope = scopeText(artifacts);
        return scope.isBlank() ? "no checkable reading target was observed" : scope;
    }

    private String paperStartHereText(String startTitle, ReadingTurnArtifacts.PaperShortlistItem first) {
        if (first == null || first.matchReason().isBlank()) {
            return startTitle + ", because it is the first observed paper card.";
        }
        String role = rolePhrase(first);
        return startTitle + role + ". Selection reason: " + first.matchReason();
    }

    private String rolePhrase(ReadingTurnArtifacts.PaperShortlistItem item) {
        if (item == null || item.role().isBlank()) {
            return "";
        }
        return " as the " + item.role() + " paper";
    }

    private String paperNotVerifiedText(ReadingTurnArtifacts.PaperShortlistItem first) {
        if (first == null) {
            return "This shortlist is metadata-only; no paper passage has been read yet.";
        }
        List<String> notes = new ArrayList<>();
        if (!first.evidenceStatus().isBlank()) {
            notes.add(first.evidenceStatus());
        }
        if (!first.roleEvidenceStatus().isBlank()) {
            notes.add(first.roleEvidenceStatus());
        }
        return notes.isEmpty()
                ? "This shortlist is metadata-only; no paper passage has been read yet."
                : String.join(" ", notes);
    }

    @SafeVarargs
    private String appendVerificationNotes(String primary, List<String>... noteGroups) {
        List<String> values = new ArrayList<>();
        String normalizedPrimary = firstNonBlank(primary);
        if (!normalizedPrimary.isBlank()) {
            values.add(normalizedPrimary);
        }
        if (noteGroups == null) {
            return String.join(" ", values);
        }
        for (List<String> notes : noteGroups) {
            for (String note : notes == null ? List.<String>of() : notes) {
                String normalized = firstNonBlank(note);
                if (!normalized.isBlank() && values.stream().noneMatch(normalized::equals)) {
                    values.add(normalized);
                }
            }
        }
        return String.join(" ", values);
    }

    private List<String> visibleLimitations(AnswerEnvelope envelope) {
        if (envelope == null || envelope.answerType() != AnswerType.INSUFFICIENT_EVIDENCE) {
            return List.of();
        }
        return envelope.limitations();
    }

    private String uncertaintyText(ReadingTurnArtifacts artifacts, String defaultText) {
        if (artifacts.uncertaintyNotes().isEmpty()) {
            return defaultText;
        }
        return String.join(" ", artifacts.uncertaintyNotes());
    }

    private String scopeText(ReadingTurnArtifacts artifacts) {
        String label = artifacts.goalCard().scopeLabel();
        if (label.isBlank() && artifacts.goalCard().readablePaperCount() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(label.isBlank() ? "the current readable papers" : label);
        if (artifacts.goalCard().readablePaperCount() != null) {
            builder.append(", ").append(artifacts.goalCard().readablePaperCount()).append(" papers");
        }
        if (artifacts.goalCard().scopeLocked()) {
            builder.append(", locked");
        }
        return builder.toString();
    }

    private String displayTitle(String title, String filename, String defaultTitle) {
        return firstNonBlank(title, filename, defaultTitle);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String trimSentence(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? "Missing from validated reading artifacts." : normalized;
    }

    private String sentenceSuffix(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.endsWith(".") || normalized.endsWith("?") || normalized.endsWith("!")
                || normalized.endsWith("。") || normalized.endsWith("？") || normalized.endsWith("！")) {
            return "";
        }
        return ".";
    }

    private String stripMarkers(String value) {
        return value == null ? "" : value.replaceAll("\\{\\{\\s*sourceQuoteRef\\s*:[^}]+}}", "").trim();
    }
}
