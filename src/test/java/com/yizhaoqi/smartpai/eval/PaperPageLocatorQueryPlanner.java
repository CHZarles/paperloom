package com.yizhaoqi.smartpai.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class PaperPageLocatorQueryPlanner {

    private static final Pattern NON_TERM = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
    private static final int MAX_EXPANSIONS = 32;
    private static final Set<String> STOPWORDS = Set.of(
            "all", "and", "are", "for", "how", "into", "need", "of", "the", "this", "you",
            "with", "from", "is", "that", "what", "when", "where", "which", "who", "why"
    );

    private PaperPageLocatorQueryPlanner() {
    }

    public static PlannedQuery plan(String query, List<PaperPageDocument> pages) {
        String originalQuery = query == null ? "" : query.trim();
        String lower = originalQuery.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> expansions = new LinkedHashSet<>();
        if (isRelatedConceptQuery(lower)) {
            addAll(expansions, "keywords", "related work", "background", "overview");
            addPaperTerms(expansions, pages);
        }
        if (isRetrievalStrategyQuery(lower)) {
            addAll(expansions,
                    "retrieval strategies",
                    "lexical search",
                    "semantic search",
                    "hybrid",
                    "vector retrieval",
                    "grep"
            );
        }
        if (isMethodDatasetQuery(lower)) {
            addAll(expansions,
                    "methodology",
                    "method",
                    "task",
                    "dataset",
                    "retrieval implementations",
                    "agent harnesses",
                    "evaluation"
            );
        }
        if (isLimitationConclusionQuery(lower)) {
            addAll(expansions,
                    "limitations",
                    "conclusion",
                    "discussion",
                    "future work"
            );
            addSectionHints(expansions, pages);
        }
        if (isSummaryQuery(lower)) {
            addAll(expansions,
                    "abstract",
                    "introduction",
                    "conclusion",
                    "method",
                    "approach",
                    "contribution",
                    "contributions"
            );
            addSectionHints(expansions, pages);
        }
        List<String> plannedExpansions = expansions.stream()
                .limit(MAX_EXPANSIONS)
                .toList();
        return new PlannedQuery(
                originalQuery,
                joinQuery(originalQuery, plannedExpansions),
                plannedExpansions
        );
    }

    public static PlannedQuery planScientificQa(String query, List<PaperPageDocument> pages) {
        String originalQuery = query == null ? "" : query.trim();
        String lower = originalQuery.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> expansions = new LinkedHashSet<>();
        if (isBaselineComparisonQuestion(lower)) {
            addAll(expansions,
                    "baseline",
                    "baselines",
                    "compare",
                    "compared",
                    "comparison",
                    "related approaches",
                    "against",
                    "evaluation",
                    "experiments",
                    "results"
            );
            if (lower.contains("pivot")) {
                addAll(expansions, "pivoting", "pivot based");
            }
        }
        if (isDatasetCorpusQuestion(lower)) {
            addAll(expansions,
                    "dataset",
                    "datasets",
                    "corpus",
                    "corpora",
                    "data",
                    "evaluation data",
                    "collection",
                    "source"
            );
        }
        if (isLanguagePairQuestion(lower)) {
            addAll(expansions,
                    "corpus",
                    "evaluation data",
                    "language",
                    "languages",
                    "language pairs",
                    "parallel data",
                    "translation",
                    "directions"
            );
        }
        if (isModelMethodQuestion(lower)) {
            addAll(expansions,
                    "model",
                    "models",
                    "method",
                    "methods",
                    "approach",
                    "approaches",
                    "algorithm",
                    "algorithms",
                    "architecture"
            );
        }
        if (isEmbeddingQuestion(lower)) {
            addAll(expansions, "embedding", "embeddings", "representation", "representations");
        }
        if (isEvaluationResultQuestion(lower)) {
            addAll(expansions,
                    "evaluation",
                    "evaluated",
                    "experiment",
                    "experiments",
                    "results",
                    "performance",
                    "accuracy",
                    "f1",
                    "table"
            );
        }
        if (isAnnotationCrowdQuestion(lower)) {
            addAll(expansions,
                    "annotation",
                    "annotations",
                    "annotators",
                    "manual",
                    "human",
                    "crowdsourcing",
                    "crowd",
                    "workers",
                    "platform"
            );
        }
        addScientificSectionHints(expansions, pages);
        List<String> plannedExpansions = expansions.stream()
                .limit(MAX_EXPANSIONS)
                .toList();
        return new PlannedQuery(
                originalQuery,
                joinQuery(originalQuery, plannedExpansions),
                plannedExpansions
        );
    }

    private static boolean isRelatedConceptQuery(String lower) {
        return containsAny(lower,
                "相关概念",
                "核心概念",
                "关键词",
                "关键概念",
                "concept",
                "concepts",
                "keyword",
                "keywords"
        );
    }

    private static boolean isRetrievalStrategyQuery(String lower) {
        return containsAny(lower,
                "检索策略",
                "检索方法",
                "搜索策略",
                "retrieval strateg",
                "retrieval method",
                "search strateg"
        );
    }

    private static boolean isMethodDatasetQuery(String lower) {
        return containsAny(lower,
                "方法部分",
                "数据集",
                "任务设置",
                "任务和数据",
                "method",
                "methodology",
                "dataset",
                "task"
        );
    }

    private static boolean isLimitationConclusionQuery(String lower) {
        return containsAny(lower,
                "局限",
                "限制",
                "不足",
                "结论",
                "limitation",
                "limitations",
                "conclusion",
                "future work"
        );
    }

    private static boolean isSummaryQuery(String lower) {
        return containsAny(lower,
                "讲了什么",
                "讲什么",
                "文章讲",
                "论文讲",
                "总结",
                "概括",
                "摘要",
                "主要内容",
                "summary",
                "summarize",
                "abstract",
                "what is this paper about"
        );
    }

    private static boolean isBaselineComparisonQuestion(String lower) {
        return containsAny(lower,
                "baseline",
                "baselines",
                "compare",
                "compared",
                "comparison",
                "approach",
                "approaches"
        );
    }

    private static boolean isDatasetCorpusQuestion(String lower) {
        return containsAny(lower,
                "dataset",
                "datasets",
                "data",
                "corpus",
                "corpora",
                "source",
                "sources",
                "topic",
                "topics"
        );
    }

    private static boolean isLanguagePairQuestion(String lower) {
        return containsAny(lower,
                "language pair",
                "language pairs",
                "languages",
                "translation direction",
                "translation directions"
        );
    }

    private static boolean isModelMethodQuestion(String lower) {
        return containsAny(lower,
                "model",
                "models",
                "algorithm",
                "algorithms",
                "method",
                "methods",
                "predictive",
                "approach",
                "approaches"
        );
    }

    private static boolean isEmbeddingQuestion(String lower) {
        return containsAny(lower, "embedding", "embeddings", "representation", "representations");
    }

    private static boolean isEvaluationResultQuestion(String lower) {
        return containsAny(lower,
                "evaluated",
                "evaluate",
                "evaluation",
                "experiment",
                "experiments",
                "performance",
                "accuracy",
                "f1",
                "state of the art",
                "result",
                "results"
        );
    }

    private static boolean isAnnotationCrowdQuestion(String lower) {
        return containsAny(lower,
                "annotation",
                "annotations",
                "annotator",
                "annotators",
                "crowdsourcing",
                "crowd",
                "manual annotation",
                "manual annotations",
                "platform"
        );
    }

    private static void addPaperTerms(LinkedHashSet<String> expansions, List<PaperPageDocument> pages) {
        for (PaperPageDocument page : pages == null ? List.<PaperPageDocument>of() : pages) {
            addKeywordSectionTerms(expansions, page);
            addTitleTerms(expansions, page.paperTitle());
            if (expansions.size() >= MAX_EXPANSIONS) {
                return;
            }
        }
    }

    private static void addKeywordSectionTerms(LinkedHashSet<String> expansions, PaperPageDocument page) {
        boolean keywordPage = page.sectionTitles().stream()
                .anyMatch(section -> section != null && section.toLowerCase(Locale.ROOT).contains("keyword"));
        if (!keywordPage) {
            return;
        }
        for (String line : page.pageText().split("\\R")) {
            if (!looksLikeKeywordLine(line)) {
                continue;
            }
            for (String segment : line.split("[,;]")) {
                addIfUseful(expansions, segment);
            }
        }
    }

    private static boolean looksLikeKeywordLine(String line) {
        String normalized = line == null ? "" : line.trim();
        if (normalized.isBlank() || normalized.contains("@")) {
            return false;
        }
        String[] segments = normalized.split("[,;]");
        if (segments.length < 3 || segments.length > 16) {
            return false;
        }
        for (String segment : segments) {
            if (termTokens(segment).size() > 5) {
                return false;
            }
        }
        int tokenCount = termTokens(normalized).size();
        return tokenCount >= 3 && tokenCount <= 32;
    }

    private static void addTitleTerms(LinkedHashSet<String> expansions, String title) {
        List<String> terms = termTokens(title);
        for (String term : terms) {
            addIfUseful(expansions, term);
        }
        for (int i = 0; i + 1 < terms.size(); i++) {
            addIfUseful(expansions, terms.get(i) + " " + terms.get(i + 1));
        }
    }

    private static void addSectionHints(LinkedHashSet<String> expansions, List<PaperPageDocument> pages) {
        for (PaperPageDocument page : pages == null ? List.<PaperPageDocument>of() : pages) {
            for (String sectionTitle : page.sectionTitles()) {
                List<String> sectionTerms = termTokens(sectionTitle);
                if (sectionTerms.contains("abstract")) {
                    addIfUseful(expansions, "abstract");
                }
                if (sectionTerms.contains("introduction")) {
                    addIfUseful(expansions, "introduction");
                }
                if (sectionTerms.contains("conclusion")) {
                    addIfUseful(expansions, "conclusion");
                }
                if (sectionTerms.contains("method") || sectionTerms.contains("methods")) {
                    addIfUseful(expansions, "method");
                }
            }
        }
    }

    private static void addScientificSectionHints(LinkedHashSet<String> expansions, List<PaperPageDocument> pages) {
        for (PaperPageDocument page : pages == null ? List.<PaperPageDocument>of() : pages) {
            for (String sectionTitle : page.sectionTitles()) {
                List<String> sectionTerms = termTokens(sectionTitle);
                if (sectionTerms.contains("experiment") || sectionTerms.contains("experiments")) {
                    addIfUseful(expansions, "experiments");
                }
                if (sectionTerms.contains("evaluation") || sectionTerms.contains("evaluations")) {
                    addIfUseful(expansions, "evaluation");
                }
                if (sectionTerms.contains("data") || sectionTerms.contains("dataset")
                        || sectionTerms.contains("datasets")) {
                    addIfUseful(expansions, "dataset");
                }
                if (sectionTerms.contains("annotation") || sectionTerms.contains("annotations")) {
                    addIfUseful(expansions, "annotation");
                }
                if (sectionTerms.contains("results")) {
                    addIfUseful(expansions, "results");
                }
            }
        }
    }

    private static List<String> termTokens(String text) {
        List<String> tokens = new ArrayList<>();
        String normalized = NON_TERM.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT)).replaceAll(" ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void addAll(LinkedHashSet<String> expansions, String... values) {
        for (String value : values) {
            addIfUseful(expansions, value);
        }
    }

    private static void addIfUseful(LinkedHashSet<String> expansions, String value) {
        String normalized = normalizePhrase(value);
        if (!normalized.isBlank()) {
            expansions.add(normalized);
        }
    }

    private static String normalizePhrase(String value) {
        String normalized = NON_TERM.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT)).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() < 2) {
            return "";
        }
        return normalized;
    }

    private static String joinQuery(String originalQuery, List<String> expansions) {
        List<String> parts = new ArrayList<>();
        if (!originalQuery.isBlank()) {
            parts.add(originalQuery);
        }
        parts.addAll(expansions);
        return String.join(" ", parts);
    }

    public record PlannedQuery(
            String originalQuery,
            String expandedQuery,
            List<String> expansions
    ) {
        public PlannedQuery {
            originalQuery = originalQuery == null ? "" : originalQuery;
            expandedQuery = expandedQuery == null ? "" : expandedQuery;
            expansions = expansions == null ? List.of() : List.copyOf(expansions);
        }
    }
}
