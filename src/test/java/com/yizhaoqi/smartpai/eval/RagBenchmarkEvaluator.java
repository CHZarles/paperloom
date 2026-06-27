package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.EvidenceQuality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class RagBenchmarkEvaluator {

    public RagBenchmarkVerdict evaluate(RagBenchmarkCase testCase, RagBenchmarkActual actual) {
        List<String> failures = new ArrayList<>();
        Set<String> failureClasses = new LinkedHashSet<>();
        String markdown = actual.markdown();
        String evidenceText = joinedEvidenceText(actual);

        if (testCase.expectedRoute() != null
                && !testCase.expectedRoute().isBlank()
                && !testCase.expectedRoute().equals(actual.route())) {
            failures.add("ROUTE_MISMATCH:expected=" + testCase.expectedRoute() + ",actual=" + actual.route());
            failureClasses.add("INTENT_ROUTE");
        }

        for (String pattern : testCase.requiredAnswerRegex()) {
            if (!matches(pattern, markdown)) {
                failures.add("ANSWER_REQUIRED_PATTERN_MISSING:" + pattern);
                failureClasses.add("ANSWER_QUALITY");
            }
        }

        for (String pattern : testCase.forbiddenAnswerRegex()) {
            if (matches(pattern, markdown)) {
                failures.add("ANSWER_FORBIDDEN_PATTERN:" + pattern);
                failureClasses.add("FALSE_NEGATIVE");
            }
        }

        for (String pattern : testCase.requiredEvidenceRegex()) {
            if (!matches(pattern, evidenceText)) {
                failures.add("EVIDENCE_REQUIRED_PATTERN_MISSING:" + pattern);
                failureClasses.add("FALSE_NEGATIVE");
            }
        }

        for (String pattern : testCase.forbiddenEvidenceRegex()) {
            if (actual.referenceMappings().values().stream().anyMatch(reference -> matches(pattern, referenceEvidenceText(reference)))) {
                failures.add("EVIDENCE_FORBIDDEN_PATTERN:" + pattern);
                failureClasses.add("BAD_EVIDENCE");
            }
        }

        if (testCase.requiresCitation() && actual.referenceMappings().isEmpty()) {
            failures.add("CITATION_REQUIRED");
            failureClasses.add("FALSE_NEGATIVE");
        }

        List<String> scopedPaperIds = testCase.scope().paperIds();
        if (!scopedPaperIds.isEmpty()) {
            for (ChatHandler.ReferenceInfo reference : actual.referenceMappings().values()) {
                if (reference.paperId() != null && !scopedPaperIds.contains(reference.paperId())) {
                    failures.add("SCOPE_LEAK:" + reference.paperId());
                    failureClasses.add("SCOPE_CONTROL");
                }
            }
        }

        for (var entry : actual.referenceMappings().entrySet()) {
            String text = referenceEvidenceText(entry.getValue());
            if (!EvidenceQuality.isUsable(text)) {
                failures.add("EVIDENCE_UNUSABLE:" + entry.getKey());
                failureClasses.add("BAD_EVIDENCE");
            }
        }

        if (testCase.requiresCitation() && !actual.referenceMappings().isEmpty() && !hasRenderedCitation(markdown)) {
            failures.add("CITATION_NOT_RENDERED");
            failureClasses.add("CITATION_MAPPING");
        }

        return new RagBenchmarkVerdict(testCase.id(), failures.isEmpty(), failures, List.copyOf(failureClasses));
    }

    private boolean hasRenderedCitation(String markdown) {
        return Pattern.compile("\\[\\d+]").matcher(markdown == null ? "" : markdown).find();
    }

    private String joinedEvidenceText(RagBenchmarkActual actual) {
        StringBuilder builder = new StringBuilder();
        actual.referenceMappings().values().forEach(reference -> builder
                .append(reference.paperTitle()).append('\n')
                .append(reference.sectionTitle()).append('\n')
                .append(referenceEvidenceText(reference)).append('\n'));
        return builder.toString();
    }

    private String referenceEvidenceText(ChatHandler.ReferenceInfo reference) {
        if (reference == null) {
            return "";
        }
        if (reference.matchedChunkText() != null && !reference.matchedChunkText().isBlank()) {
            return reference.matchedChunkText();
        }
        if (reference.tableText() != null && !reference.tableText().isBlank()) {
            return reference.tableText();
        }
        if (reference.tableMarkdown() != null && !reference.tableMarkdown().isBlank()) {
            return reference.tableMarkdown();
        }
        return reference.evidenceSnippet() == null ? "" : reference.evidenceSnippet();
    }

    private boolean matches(String pattern, String value) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
                    .matcher(value == null ? "" : value)
                    .find();
        } catch (PatternSyntaxException ignored) {
            return (value == null ? "" : value).toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        }
    }
}
