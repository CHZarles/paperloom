package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperQueryPlannerTest {

    @Test
    void unforcedQueryDoesNotInferSemanticIntentFromPhraseLists() {
        PaperQueryPlanner planner = new PaperQueryPlanner();

        PaperQueryPlanner.RetrievalPlan plan = planner.plan("我说论文实验数据");

        assertEquals("我说论文实验数据", plan.normalizedQuery());
        assertEquals(PaperQueryPlanner.RetrievalIntent.GENERAL, plan.intent());
        assertEquals(java.util.List.of("我说论文实验数据"), plan.queryTexts());
    }

    @Test
    void keepsSpecificTermsWithoutAddingHardcodedSynonyms() {
        PaperQueryPlanner planner = new PaperQueryPlanner();

        PaperQueryPlanner.RetrievalPlan plan = planner.plan("Chronos 方法是什么");

        assertEquals(PaperQueryPlanner.RetrievalIntent.GENERAL, plan.intent());
        assertEquals(java.util.List.of("Chronos 方法是什么"), plan.queryTexts());
    }

    @Test
    void unforcedRecommendationQueryDoesNotOwnTopLevelPaperDiscovery() {
        PaperQueryPlanner planner = new PaperQueryPlanner();

        PaperQueryPlanner.RetrievalPlan plan = planner.plan("推荐一些 post-hoc hallucination detection 相关论文");

        assertEquals(PaperQueryPlanner.RetrievalIntent.GENERAL, plan.intent());
        assertFalse(plan.paperLevelSearch());
        assertEquals(java.util.List.of("推荐一些 post-hoc hallucination detection 相关论文"), plan.queryTexts());
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
        assertEquals(java.util.List.of("推荐一些 post-hoc hallucination detection 相关论文"), plan.queryTexts());
    }
}
