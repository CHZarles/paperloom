package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperQueryPlannerTest {

    @Test
    void expandsChineseExperimentDataQuestionIntoPaperRetrievalPlan() {
        PaperQueryPlanner planner = new PaperQueryPlanner();

        PaperQueryPlanner.RetrievalPlan plan = planner.plan("我说论文实验数据");

        assertEquals("论文实验数据", plan.normalizedQuery());
        assertEquals(PaperQueryPlanner.RetrievalIntent.EXPERIMENT_RESULT, plan.intent());
        assertTrue(plan.queryTexts().contains("论文实验数据"));
        assertTrue(plan.queryTexts().contains("experiment results"));
        assertTrue(plan.queryTexts().contains("evaluation results"));
        assertTrue(plan.queryTexts().contains("accuracy"));
        assertTrue(plan.queryTexts().contains("benchmark"));
        assertTrue(plan.queryTexts().contains("table"));
        assertTrue(plan.preferredSourceKinds().contains("TABLE"));
        assertTrue(plan.preferredSourceKinds().contains("CHART"));
        assertTrue(plan.preferredSourceKinds().contains("FIGURE"));
    }

    @Test
    void keepsSpecificPaperMethodTermsWhileAddingMethodSynonyms() {
        PaperQueryPlanner planner = new PaperQueryPlanner();

        PaperQueryPlanner.RetrievalPlan plan = planner.plan("Chronos 方法是什么");

        assertEquals(PaperQueryPlanner.RetrievalIntent.METHOD, plan.intent());
        assertTrue(plan.queryTexts().contains("Chronos 方法"));
        assertTrue(plan.queryTexts().contains("method"));
        assertTrue(plan.queryTexts().contains("approach"));
    }

    @Test
    void expandsChineseHighNoiseQueriesToEnglishNoiseEvidenceTerms() {
        PaperQueryPlanner planner = new PaperQueryPlanner();

        PaperQueryPlanner.RetrievalPlan plan = planner.plan("讲一讲高噪声场景");

        assertEquals(PaperQueryPlanner.RetrievalIntent.EXPERIMENT_RESULT, plan.intent());
        assertTrue(plan.queryTexts().contains("high noise"));
        assertTrue(plan.queryTexts().contains("increasing noise"));
        assertTrue(plan.queryTexts().contains("context scaling"));
    }

    @Test
    void unforcedRecommendationQueryDoesNotOwnTopLevelPaperDiscovery() {
        PaperQueryPlanner planner = new PaperQueryPlanner();

        PaperQueryPlanner.RetrievalPlan plan = planner.plan("推荐一些 post-hoc hallucination detection 相关论文");

        assertEquals(PaperQueryPlanner.RetrievalIntent.GENERAL, plan.intent());
        assertFalse(plan.paperLevelSearch());
    }

    @Test
    void forcedLiteratureSearchNormalizesTopicForPaperLevelRetrieval() {
        PaperQueryPlanner planner = new PaperQueryPlanner();

        PaperQueryPlanner.RetrievalPlan plan = planner.plan(
                "推荐一些 post-hoc hallucination detection 相关论文",
                PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH
        );

        assertEquals(PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH, plan.intent());
        assertTrue(plan.paperLevelSearch());
        assertEquals(java.util.List.of("post-hoc hallucination detection"), plan.queryTexts());
        assertTrue(plan.preferredSections().contains("abstract"));
        assertTrue(plan.preferredSections().contains("related work"));
    }
}
