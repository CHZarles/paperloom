package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPageLocatorQueryPlannerTest {

    @Test
    void expandsChineseRelatedConceptQueryWithPaperTitleAndKeywordTerms() {
        PaperPageLocatorQueryPlanner.PlannedQuery planned = PaperPageLocatorQueryPlanner.plan(
                "在论文里找相关概念",
                List.of(
                        page(1,
                                "Keywords",
                                "Agentic Search, Semantic Search, Lexical Search, Context Engineering, "
                                        + "Agent Harnesses, LLM Evaluation, Grep"),
                        page(2,
                                "2 Overview of Retrieval in Agentic Systems",
                                "Retrieval strategies include lexical, semantic, and hybrid search.")
                )
        );

        assertEquals("在论文里找相关概念", planned.originalQuery());
        assertTrue(planned.expansions().contains("agentic search"));
        assertTrue(planned.expansions().contains("semantic search"));
        assertTrue(planned.expansions().contains("lexical search"));
        assertTrue(planned.expansions().contains("agent harnesses"));
        assertTrue(planned.expansions().contains("grep"));
        assertTrue(planned.expansions().stream().noneMatch("is"::equals));
        assertTrue(planned.expansions().stream().noneMatch("is grep"::equals));
        assertTrue(planned.expandedQuery().contains("agentic search"));
    }

    @Test
    void keepsKeywordExpansionFromAbsorbingTheWholePage() {
        PaperPageLocatorQueryPlanner.PlannedQuery planned = PaperPageLocatorQueryPlanner.plan(
                "在论文里找相关概念",
                List.of(
                        page(1,
                                "Keywords",
                                """
                                        Sahil Sen PricewaterhouseCoopers, U.S. sahil.s.sen@pwc.com
                                        Recent advances in Large Language Model agents have enabled complex workflows, but retrieval remains hard.
                                        Important practical dimensions, including how tool outputs are presented to the model, remain under-explored in agent loops.
                                        Agentic Search, Semantic Search, Lexical Search, Context Engineering, Agent Harnesses, LLM Evaluation, Grep
                                        """.trim())
                )
        );

        assertTrue(planned.expansions().contains("semantic search"));
        assertTrue(planned.expansions().stream().noneMatch(term -> term.contains("pricewaterhousecoopers")));
        assertTrue(planned.expansions().stream().noneMatch(term -> term.contains("recent advances")));
        assertTrue(planned.expansions().stream().noneMatch(term -> term.contains("important practical")));
    }

    @Test
    void expandsChinesePaperSummaryQueryWithNavigationalSections() {
        PaperPageLocatorQueryPlanner.PlannedQuery planned = PaperPageLocatorQueryPlanner.plan(
                "这个文章讲了什么",
                List.of(
                        page(1, "Abstract", "This paper reports an empirical study."),
                        page(2, "1 Introduction", "Modern LLM agents increasingly rely on RAG."),
                        page(7, "6 Conclusion", "These results highlight retrieval mechanics.")
                )
        );

        assertTrue(planned.expansions().contains("abstract"));
        assertTrue(planned.expansions().contains("introduction"));
        assertTrue(planned.expansions().contains("conclusion"));
        assertTrue(planned.expansions().contains("method"));
        assertTrue(planned.expandedQuery().contains("conclusion"));
    }

    @Test
    void expandsChineseRetrievalStrategyQueryIntoStrategyTerms() {
        PaperPageLocatorQueryPlanner.PlannedQuery planned = PaperPageLocatorQueryPlanner.plan(
                "论文比较了哪些检索策略",
                List.of(page(2,
                        "2.1 Retrieval Strategies",
                        "Retrieval strategies include lexical, semantic, and hybrid search."))
        );

        assertTrue(planned.expansions().contains("retrieval strategies"));
        assertTrue(planned.expansions().contains("lexical search"));
        assertTrue(planned.expansions().contains("semantic search"));
        assertTrue(planned.expansions().contains("hybrid"));
    }

    @Test
    void expandsChineseMethodDatasetQueryIntoMethodologyTerms() {
        PaperPageLocatorQueryPlanner.PlannedQuery planned = PaperPageLocatorQueryPlanner.plan(
                "方法部分的数据集和任务设置在哪里",
                List.of(page(3,
                        "3 Methodology",
                        "Task and Dataset. Retrieval Implementations. Agent Harnesses."))
        );

        assertTrue(planned.expansions().contains("methodology"));
        assertTrue(planned.expansions().contains("task"));
        assertTrue(planned.expansions().contains("dataset"));
        assertTrue(planned.expansions().contains("retrieval implementations"));
    }

    @Test
    void expandsChineseLimitationConclusionQueryIntoClosingSections() {
        PaperPageLocatorQueryPlanner.PlannedQuery planned = PaperPageLocatorQueryPlanner.plan(
                "论文的局限性和结论在哪里",
                List.of(
                        page(7, "5 Limitations", "We do not claim that grep beats vector in general."),
                        page(7, "6 Conclusion", "These results highlight retrieval mechanics.")
                )
        );

        assertTrue(planned.expansions().contains("limitations"));
        assertTrue(planned.expansions().contains("conclusion"));
        assertTrue(planned.expansions().contains("future work"));
    }

    @Test
    void expandsScientificQaBaselineQuestionsWithComparisonAndEvaluationTerms() {
        PaperPageLocatorQueryPlanner.PlannedQuery planned = PaperPageLocatorQueryPlanner.planScientificQa(
                "what are the pivot-based baselines?",
                List.of(page(4,
                        "4 Experiments",
                        "We compare our approach against pivoting and multilingual NMT baselines."))
        );

        assertTrue(planned.expansions().contains("baseline"));
        assertTrue(planned.expansions().contains("baselines"));
        assertTrue(planned.expansions().contains("compare"));
        assertTrue(planned.expansions().contains("comparison"));
        assertTrue(planned.expansions().contains("evaluation"));
        assertTrue(planned.expandedQuery().contains("comparison"));
    }

    @Test
    void expandsScientificQaDatasetAndAnnotationQuestionsWithEvidenceTerms() {
        PaperPageLocatorQueryPlanner.PlannedQuery datasetPlanned = PaperPageLocatorQueryPlanner.planScientificQa(
                "what language pairs are explored?",
                List.of(page(3,
                        "Data and Evaluation",
                        "The MultiUN corpus includes Arabic, Spanish, Russian, and English."))
        );
        PaperPageLocatorQueryPlanner.PlannedQuery annotationPlanned = PaperPageLocatorQueryPlanner.planScientificQa(
                "What crowdsourcing platform is used?",
                List.of(page(5,
                        "Annotation",
                        "Manual annotations were collected from crowd workers."))
        );

        assertTrue(datasetPlanned.expansions().contains("corpus"));
        assertTrue(datasetPlanned.expansions().contains("languages"));
        assertTrue(datasetPlanned.expansions().contains("language pairs"));
        assertTrue(datasetPlanned.expansions().contains("evaluation data"));
        assertTrue(annotationPlanned.expansions().contains("crowdsourcing"));
        assertTrue(annotationPlanned.expansions().contains("annotation"));
        assertTrue(annotationPlanned.expansions().contains("workers"));
        assertTrue(annotationPlanned.expansions().contains("platform"));
    }

    private PaperPageDocument page(int pageNumber, String sectionTitle, String text) {
        return new PaperPageDocument(
                "paper-a",
                "Is Grep All You Need? How Agent Harnesses Reshape Agentic Search",
                "paper-a.pdf",
                pageNumber,
                text,
                List.of(pageNumber),
                List.of(sectionTitle),
                List.of("TEXT"),
                List.of(),
                List.of()
        );
    }
}
