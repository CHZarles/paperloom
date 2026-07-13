package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalBudgetTest {

    @Test
    void qaDefaultUsesInteractivePageWindowBudget() {
        RetrievalBudget budget = RetrievalBudget.forQa();

        assertEquals(3, budget.pageWindowTopK());
        assertEquals(1, budget.pageWindowRadius());
        assertEquals("scientific-qa-diverse-windows", budget.pageWindowPlanner());
    }

    @Test
    void exposesHighRecallAndDeepAuditPageWindowBudgetsWithoutChangingDefault() {
        RetrievalBudget highRecall = RetrievalBudget.forQaHighRecallPageWindows();
        RetrievalBudget deepAudit = RetrievalBudget.forQaDeepAuditPageWindows();

        assertEquals(5, highRecall.pageWindowTopK());
        assertEquals(1, highRecall.pageWindowRadius());
        assertEquals("scientific-qa-diverse-windows", highRecall.pageWindowPlanner());

        assertEquals(7, deepAudit.pageWindowTopK());
        assertEquals(1, deepAudit.pageWindowRadius());
        assertEquals("scientific-qa-diverse-windows", deepAudit.pageWindowPlanner());

        assertEquals(3, RetrievalBudget.forQa().pageWindowTopK());
    }
}
