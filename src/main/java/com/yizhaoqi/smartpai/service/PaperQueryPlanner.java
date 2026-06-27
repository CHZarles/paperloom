package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PaperQueryPlanner {

    public RetrievalPlan plan(String userQuery) {
        return plan(userQuery, null);
    }

    public RetrievalPlan plan(String userQuery, RetrievalIntent forcedIntent) {
        String normalizedForIntent = normalizeQuery(userQuery);
        RetrievalIntent intent = forcedIntent == null ? detectIntent(normalizedForIntent) : forcedIntent;
        String normalized = intent == RetrievalIntent.LITERATURE_SEARCH
                ? normalizeLiteratureSearchQuery(normalizedForIntent)
                : normalizedForIntent;
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (!normalized.isBlank()) {
            queries.add(normalized);
        }
        switch (intent) {
            case EXPERIMENT_RESULT -> {
                queries.add("experiment results");
                queries.add("experimental results");
                queries.add("evaluation results");
                queries.add("accuracy");
                queries.add("benchmark");
                queries.add("dataset");
                queries.add("table");
                queries.add("chart");
                if (containsAny(normalized.toLowerCase(Locale.ROOT), "噪声", "noise")) {
                    queries.add("high noise");
                    queries.add("increasing noise");
                    queries.add("context scaling");
                }
            }
            case METHOD -> {
                queries.add("method");
                queries.add("approach");
                queries.add("architecture");
                queries.add("algorithm");
            }
            case LIMITATION -> {
                queries.add("limitations");
                queries.add("threats to validity");
                queries.add("future work");
            }
            case SUMMARY -> {
                queries.add("abstract");
                queries.add("introduction");
                queries.add("conclusion");
            }
            case LITERATURE_SEARCH -> {
                // paper_search already targets title/abstract/facet metadata. Adding field-label
                // words such as "title" or "abstract" to the user topic makes those labels match
                // nearly every metadata document and drowns the actual topic signal.
            }
            case GENERAL -> {
                // Keep the original query dominant for general questions.
            }
        }
        return new RetrievalPlan(
                userQuery == null ? "" : userQuery,
                normalized,
                intent,
                List.copyOf(queries),
                preferredSourceKinds(intent),
                preferredSections(intent)
        );
    }

    private String normalizeQuery(String userQuery) {
        if (userQuery == null) {
            return "";
        }
        String normalized = userQuery.replaceAll("\\s+", " ").trim();
        normalized = normalized
                .replaceFirst("^(我说|我是说|刚才说的是|你知道|你懂|你了解|请问|帮我查一下|查一下|说说|解释一下|介绍一下)", "")
                .replaceFirst("(是什么|是啥|吗|么|呢|\\?)$", "")
                .trim();
        return normalized;
    }

    private String normalizeLiteratureSearchQuery(String query) {
        if (query == null) {
            return "";
        }
        String cleaned = query
                .replaceAll("(?i)recommend papers|related papers|research papers|papers about|papers on", " ")
                .replace("推荐一下", " ")
                .replace("推荐一些", " ")
                .replace("推荐", " ")
                .replace("相关论文", " ")
                .replace("有哪些论文", " ")
                .replace("有什么论文", " ")
                .replace("研究论文", " ")
                .replace("论文", " ")
                .replace("文章", " ")
                .replace("和", " ")
                .replace("的", " ")
                .replace("相关", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? query : cleaned;
    }

    private RetrievalIntent detectIntent(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "实验", "数据", "结果", "指标", "准确率", "表格", "图表",
                "噪声", "高噪声",
                "experiment", "experimental", "evaluation", "result", "accuracy", "benchmark",
                "dataset", "table", "chart", "noise")) {
            return RetrievalIntent.EXPERIMENT_RESULT;
        }
        if (containsAny(lower, "方法", "模型", "算法", "架构", "method", "approach", "algorithm", "architecture")) {
            return RetrievalIntent.METHOD;
        }
        if (containsAny(lower, "限制", "不足", "缺陷", "局限", "limitation", "threat", "future work")) {
            return RetrievalIntent.LIMITATION;
        }
        if (containsAny(lower, "总结", "概括", "摘要", "summary", "summarize", "abstract")) {
            return RetrievalIntent.SUMMARY;
        }
        return RetrievalIntent.GENERAL;
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> preferredSourceKinds(RetrievalIntent intent) {
        return switch (intent) {
            case EXPERIMENT_RESULT -> List.of("TABLE", "CHART", "FIGURE", "TEXT");
            case METHOD, LIMITATION, SUMMARY, LITERATURE_SEARCH, GENERAL -> List.of("TEXT", "TABLE", "FIGURE", "CHART", "FORMULA");
        };
    }

    private List<String> preferredSections(RetrievalIntent intent) {
        return switch (intent) {
            case EXPERIMENT_RESULT -> List.of("experiment", "evaluation", "results", "dataset", "appendix");
            case METHOD -> List.of("method", "approach", "model", "architecture");
            case LIMITATION -> List.of("limitation", "discussion", "future work");
            case SUMMARY -> List.of("abstract", "introduction", "conclusion");
            case LITERATURE_SEARCH -> List.of("title", "abstract", "introduction", "related work");
            case GENERAL -> List.of();
        };
    }

    public enum RetrievalIntent {
        EXPERIMENT_RESULT,
        METHOD,
        LIMITATION,
        SUMMARY,
        LITERATURE_SEARCH,
        GENERAL
    }

    public record RetrievalPlan(
            String originalQuery,
            String normalizedQuery,
            RetrievalIntent intent,
            List<String> queryTexts,
            List<String> preferredSourceKinds,
            List<String> preferredSections
    ) {
        public RetrievalPlan {
            queryTexts = queryTexts == null ? List.of() : dedupe(queryTexts);
            preferredSourceKinds = preferredSourceKinds == null ? List.of() : dedupe(preferredSourceKinds);
            preferredSections = preferredSections == null ? List.of() : dedupe(preferredSections);
        }

        public boolean paperLevelSearch() {
            return intent == RetrievalIntent.LITERATURE_SEARCH;
        }

        private static List<String> dedupe(List<String> values) {
            Set<String> seen = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    seen.add(value.trim());
                }
            }
            return new ArrayList<>(seen);
        }
    }
}
