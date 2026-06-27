package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class QasperBenchmarkConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> ANSWERABLE_FORBIDDEN_ANSWERS = List.of(
            "暂无相关信息",
            "没有找到足够可靠",
            "无法生成足够可靠",
            "no sufficient evidence",
            "not enough information"
    );
    private static final List<String> FORBIDDEN_EVIDENCE = List.of(
            "^\\d+$",
            "^References?$",
            "^Bibliography$"
    );

    public List<RagBenchmarkCase> convert(Path qasperJson, int maxCases) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(qasperJson.toFile());
        List<RagBenchmarkCase> cases = new ArrayList<>();
        root.fields().forEachRemaining(entry -> {
            if (maxCases > 0 && cases.size() >= maxCases) {
                return;
            }
            String paperId = entry.getKey();
            JsonNode paper = entry.getValue();
            String paperTitle = paper.path("title").asText(paperId);
            for (JsonNode qa : paper.path("qas")) {
                if (maxCases > 0 && cases.size() >= maxCases) {
                    break;
                }
                JsonNode answer = firstAnswerableAnswerWithEvidence(qa.path("answers"));
                if (answer == null) {
                    continue;
                }
                cases.add(toCase(paperId, paperTitle, qa, answer));
            }
        });
        return cases;
    }

    public void writeJsonl(Path qasperJson, Path output, int maxCases) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (RagBenchmarkCase testCase : convert(qasperJson, maxCases)) {
            lines.add(OBJECT_MAPPER.writeValueAsString(testCase));
        }
        Files.write(output, lines);
    }

    private JsonNode firstAnswerableAnswerWithEvidence(JsonNode answers) {
        if (answers == null || !answers.isArray()) {
            return null;
        }
        JsonNode fallback = null;
        for (JsonNode wrapper : answers) {
            JsonNode answer = wrapper.path("answer");
            if (answer.path("unanswerable").asBoolean(false)) {
                continue;
            }
            if (firstText(answer.path("highlighted_evidence")) != null || firstText(answer.path("evidence")) != null) {
                if (!answerPatterns(answer).isEmpty()) {
                    return answer;
                }
                if (fallback == null) {
                    fallback = answer;
                }
            }
        }
        return fallback;
    }

    private RagBenchmarkCase toCase(String paperId, String paperTitle, JsonNode qa, JsonNode answer) {
        String questionId = qa.path("question_id").asText("");
        String evidence = firstText(answer.path("highlighted_evidence"));
        if (evidence == null) {
            evidence = firstText(answer.path("evidence"));
        }
        return new RagBenchmarkCase(
                caseId(paperId, questionId),
                qa.path("question").asText(""),
                "en",
                "QASPER_EVIDENCE_QA",
                "MANUAL_SOURCE",
                new RagBenchmarkCase.Scope(List.of(paperId), List.of(paperTitle)),
                "MANUAL_SOURCE_QA",
                answerPatterns(answer),
                List.of(regexLiteral(evidence)),
                ANSWERABLE_FORBIDDEN_ANSWERS,
                FORBIDDEN_EVIDENCE,
                List.of(paperId),
                true
        );
    }

    private List<String> answerPatterns(JsonNode answer) {
        String freeForm = answer.path("free_form_answer").asText("");
        if (!freeForm.isBlank()) {
            return List.of(answerRegexLiteral(freeForm));
        }
        String span = firstUsableExtractiveSpan(answer.path("extractive_spans"));
        if (span != null) {
            return List.of(answerRegexLiteral(span));
        }
        JsonNode yesNo = answer.path("yes_no");
        if (!yesNo.isMissingNode() && !yesNo.isNull()) {
            return List.of(yesNo.asBoolean() ? "(?i)\\byes\\b" : "(?i)\\bno\\b");
        }
        return List.of();
    }

    private String firstUsableExtractiveSpan(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode value : array) {
            String text = value.asText("").replaceAll("\\s+", " ").trim();
            if (!text.isBlank() && !isReferencePlaceholder(text)) {
                return text;
            }
        }
        return null;
    }

    private boolean isReferencePlaceholder(String text) {
        return text != null && text.matches("(?i)^(BIBREF|TABREF|FIGREF)\\d+$");
    }

    private String firstText(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode value : array) {
            String text = value.asText("").replaceAll("\\s+", " ").trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String regexLiteral(String text) {
        return Pattern.quote(text == null ? "" : text.replaceAll("\\s+", " ").trim());
    }

    private String answerRegexLiteral(String text) {
        return regexLiteral(cleanReferencePlaceholders(text));
    }

    private String cleanReferencePlaceholders(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?i)\\b(?:BIBREF|TABREF|FIGREF)\\d+\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String caseId(String paperId, String questionId) {
        String normalizedPaperId = paperId == null ? "unknown" : paperId.replaceAll("[^A-Za-z0-9]+", "_");
        String normalizedQuestionId = questionId == null || questionId.isBlank() ? "unknown" : questionId;
        int questionPrefixEnd = Math.min(12, normalizedQuestionId.length());
        return "qasper_" + normalizedPaperId + "_" + normalizedQuestionId.substring(0, questionPrefixEnd);
    }
}
