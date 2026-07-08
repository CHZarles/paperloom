package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.List;

public class ReadingAnswerPresenter {

    private static final int MAX_VISIBLE_SHORTLIST_ITEMS = 5;

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
            if (!safeArtifacts.paperShortlist().isEmpty()) {
                return paperShortlistAnswer(safeArtifacts);
            }
            if (!safeArtifacts.readingPlan().isEmpty()) {
                return readingPlanAnswer(safeArtifacts);
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

    private String paperShortlistAnswer(ReadingTurnArtifacts artifacts) {
        ReadingTurnArtifacts.PaperShortlistItem first = artifacts.paperShortlist().get(0);
        String startTitle = displayTitle(first.title(), first.originalFilename(), "the first paper card");
        StringBuilder answer = base(
                artifacts,
                "I found a short reading path, not a fully verified paper-content answer yet.",
                startTitle + ", because it is the strongest current entry point from the available paper cards.",
                "Open the paper card and check the title, abstract, and metadata before treating it as content evidence.",
                firstNonBlank(first.evidenceStatus(), "This shortlist is metadata-only; no paper passage has been read yet."),
                "Open " + startTitle + "'s abstract, then ask for quote-backed claims."
        );
        appendShortlist(answer, artifacts.paperShortlist());
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
            if (!item.evidenceStatus().isBlank()) {
                row.append(" - ").append(item.evidenceStatus());
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

    private String readingPlanAnswer(ReadingTurnArtifacts artifacts) {
        ReadingTurnArtifacts.ReadingPlanStep first = artifacts.readingPlan().get(0);
        String location = firstNonBlank(first.locationLabel(), "the first readable location");
        String start = location;
        if (!first.paperTitle().isBlank()) {
            start = first.paperTitle() + ", " + location;
        }
        StringBuilder answer = base(
                artifacts,
                "I found concrete places to inspect, but they are navigation targets until a passage is read.",
                start + ", because it is the first concrete place to inspect.",
                "Open this page or section from the reading plan and read it before relying on any claim.",
                firstNonBlank(first.evidenceStatus(), "These locations are not quote-backed evidence yet."),
                "Open " + location + " and ask for a quote-backed summary."
        );
        appendPlan(answer, artifacts.readingPlan());
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
        ReadingTurnArtifacts.ClaimEvidenceRow first = artifacts.claimEvidenceRows().isEmpty()
                ? new ReadingTurnArtifacts.ClaimEvidenceRow(
                firstNonBlank(renderedEvidenceMarkdown, envelope.answer()),
                "",
                "",
                "",
                ""
        )
                : artifacts.claimEvidenceRows().get(0);
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
        return base(
                artifacts,
                firstNonBlank(envelope.answer(), "I do not have enough quote-backed evidence to answer that yet."),
                firstReadingTarget(artifacts),
                "Ask me to open a paper section or quote so the claim can be checked.",
                uncertaintyText(artifacts, "Required evidence is missing or has not been read yet."),
                "Pick one paper or section to read next."
        ).toString();
    }

    private String clarificationAnswer(AnswerEnvelope envelope, ReadingTurnArtifacts artifacts) {
        return base(
                artifacts,
                firstNonBlank(envelope.answer(), "I need one clarification before reading further."),
                firstReadingTarget(artifacts),
                "Choose one paper, section, or citation from the visible options.",
                uncertaintyText(artifacts, "The current target is ambiguous."),
                "Choose one target so I can continue."
        ).toString();
    }

    private String generalAnswer(AnswerEnvelope envelope, ReadingTurnArtifacts artifacts) {
        return base(
                artifacts,
                firstNonBlank(envelope.answer(), "I can help turn this into a paper-reading step."),
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
                .append(firstNonBlank(artifacts.interpretedGoal(), "make progress on this paper-reading task"))
                .append(".\n\n");
        builder.append("Short answer: ").append(trimSentence(shortAnswer)).append("\n\n");
        builder.append("Start here: ").append(trimSentence(startHere)).append("\n\n");
        builder.append("How to verify: ").append(trimSentence(howToVerify)).append("\n\n");
        builder.append("Not verified yet: ").append(trimSentence(notVerified)).append("\n\n");
        builder.append("Next step: ").append(trimSentence(nextStep));
        return builder;
    }

    private String firstReadingTarget(ReadingTurnArtifacts artifacts) {
        if (!artifacts.paperShortlist().isEmpty()) {
            ReadingTurnArtifacts.PaperShortlistItem first = artifacts.paperShortlist().get(0);
            return displayTitle(first.title(), first.originalFilename(), "the first paper card");
        }
        if (!artifacts.readingPlan().isEmpty()) {
            return firstNonBlank(artifacts.readingPlan().get(0).locationLabel(), "the first readable location");
        }
        if (!artifacts.claimEvidenceRows().isEmpty()) {
            return firstNonBlank(artifacts.claimEvidenceRows().get(0).locationLabel(), "the cited passage");
        }
        String scope = scopeText(artifacts);
        return scope.isBlank() ? "the current reading scope" : scope;
    }

    private String uncertaintyText(ReadingTurnArtifacts artifacts, String fallback) {
        if (artifacts.uncertaintyNotes().isEmpty()) {
            return fallback;
        }
        return String.join(" ", artifacts.uncertaintyNotes());
    }

    private String scopeText(ReadingTurnArtifacts artifacts) {
        String label = artifacts.scopeLabel();
        if (label.isBlank() && artifacts.readablePaperCount() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(label.isBlank() ? "the current readable papers" : label);
        if (artifacts.readablePaperCount() != null) {
            builder.append(", ").append(artifacts.readablePaperCount()).append(" papers");
        }
        if (artifacts.scopeLocked()) {
            builder.append(", locked");
        }
        return builder.toString();
    }

    private String displayTitle(String title, String filename, String fallback) {
        return firstNonBlank(title, filename, fallback);
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
        return normalized.isBlank() ? "Not available yet." : normalized;
    }

    private String stripMarkers(String value) {
        return value == null ? "" : value.replaceAll("\\{\\{\\s*sourceQuoteRef\\s*:[^}]+}}", "").trim();
    }
}
