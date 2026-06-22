package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
