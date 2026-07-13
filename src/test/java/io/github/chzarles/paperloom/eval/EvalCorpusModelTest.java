package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.eval.model.EvalChunk;
import io.github.chzarles.paperloom.eval.model.EvalPaper;
import io.github.chzarles.paperloom.eval.model.EvalQuery;
import io.github.chzarles.paperloom.eval.model.EvalRun;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvalCorpusModelTest {

    @Test
    void retrievalCorpusNamesAreExplicitDataDomains() {
        assertEquals("PRODUCT_LIBRARY", RetrievalCorpus.PRODUCT_LIBRARY.name());
        assertEquals("EVAL_LITSEARCH", RetrievalCorpus.EVAL_LITSEARCH.name());
        assertEquals("EVAL_QASPER", RetrievalCorpus.EVAL_QASPER.name());
    }

    @Test
    void evalEntitiesLiveInPaperloomEvalSchema() {
        assertTable(EvalPaper.class, "eval_papers");
        assertTable(EvalChunk.class, "eval_chunks");
        assertTable(EvalQuery.class, "eval_queries");
        assertTable(EvalRun.class, "eval_runs");
    }

    private static void assertTable(Class<?> entityClass, String tableName) {
        Table table = entityClass.getAnnotation(Table.class);
        assertEquals(tableName, table.name());
        assertEquals("paperloom_eval", table.catalog());
    }
}
