package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatHandlerReferenceEvidenceTest {

    @Test
    void buildsReferenceEvidenceFromSearchResultWithStructuredProvenance() {
        ChatHandler handler = newHandler();
        SearchResult result = new SearchResult(
                "paper-1",
                17,
                "full chunk text",
                0.82d,
                "2",
                "TEAM_A",
                false,
                "Parsed Paper Title",
                "uploaded-paper.pdf",
                3,
                "anchor text",
                "HYBRID",
                "matched chunk text",
                "PARAGRAPH",
                "Method",
                2,
                "{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}",
                "OpenDataLoader",
                "2.4.7",
                "TABLE",
                "table-17",
                "Metric: Accuracy PaperLoom: 91.2",
                "| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |",
                true
        );
        result.setSourceType("PDF");
        result.setEvidenceAssetLevel("PDF_VISUAL");
        result.setPdfEvidenceAvailable(true);
        result.setStructuredImport(false);
        result.setEvalImport(false);
        result.setPageScreenshotAvailable(true);
        result.setFigureScreenshotAvailable(false);
        result.setAssetWarnings(List.of());

        ChatHandler.ReferenceInfo detail = ReflectionTestUtils.invokeMethod(
                handler,
                "buildReferenceInfo",
                result,
                "Parsed Paper Title",
                "bandit method"
        );

        assertEquals("paper-1", detail.paperId());
        assertEquals("Parsed Paper Title", detail.paperTitle());
        assertEquals("uploaded-paper.pdf", detail.originalFilename());
        assertEquals(3, detail.pageNumber());
        assertEquals(17, detail.chunkId());
        assertEquals("HYBRID", detail.retrievalMode());
        assertEquals("matched chunk text", detail.matchedChunkText());
        assertEquals("PARAGRAPH", detail.elementType());
        assertEquals("Method", detail.sectionTitle());
        assertEquals(2, detail.sectionLevel());
        assertEquals("{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}", detail.bboxJson());
        assertEquals("OpenDataLoader", detail.parserName());
        assertEquals("2.4.7", detail.parserVersion());
        assertEquals("TABLE", detail.sourceKind());
        assertEquals("table-17", detail.tableId());
        assertEquals("Metric: Accuracy PaperLoom: 91.2", detail.tableText());
        assertEquals("| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |", detail.tableMarkdown());
        assertEquals(true, detail.tableScreenshotAvailable());
        assertEquals("PDF", detail.sourceType());
        assertEquals("PDF_VISUAL", detail.evidenceAssetLevel());
        assertEquals(true, detail.pdfEvidenceAvailable());
        assertEquals(false, detail.structuredImport());
        assertEquals(false, detail.evalImport());
        assertEquals(true, detail.pageScreenshotAvailable());
        assertEquals(false, detail.figureScreenshotAvailable());
        assertEquals(List.of(), detail.assetWarnings());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializesReferenceEvidenceWithPaperAndParserProvenance() {
        ChatHandler handler = newHandler();
        Map<Integer, ChatHandler.ReferenceInfo> references = Map.of(
                1, new ChatHandler.ReferenceInfo(
                        "paper-1",
                        "Parsed Paper Title",
                        "uploaded-paper.pdf",
                        3,
                        "anchor text",
                        "HYBRID",
                        "混合召回",
                        "bandit method",
                        "matched chunk text",
                        "evidence snippet",
                        0.82d,
                        17,
                        "PARAGRAPH",
                        "Method",
                        2,
                        "{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}",
                        "OpenDataLoader",
                        "2.4.7",
                        "TABLE",
                        "table-17",
                        "Metric: Accuracy PaperLoom: 91.2",
                        "| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |",
                        true,
                        "EVAL_IMPORT",
                        "TEXT_ONLY",
                        false,
                        true,
                        true,
                        false,
                        false,
                        List.of("structured_import_text_only")
                )
        );

        Map<String, Map<String, Object>> serialized = ReflectionTestUtils.invokeMethod(
                handler,
                "toSerializableReferenceMappings",
                references
        );

        Map<String, Object> detail = serialized.get("1");
        assertEquals("paper-1", detail.get("paperId"));
        assertEquals("Parsed Paper Title", detail.get("paperTitle"));
        assertEquals("uploaded-paper.pdf", detail.get("originalFilename"));
        assertEquals(3, detail.get("pageNumber"));
        assertEquals(17, detail.get("chunkId"));
        assertEquals("HYBRID", detail.get("retrievalMode"));
        assertEquals(0.82d, detail.get("score"));
        assertEquals("PARAGRAPH", detail.get("elementType"));
        assertEquals("Method", detail.get("sectionTitle"));
        assertEquals(2, detail.get("sectionLevel"));
        assertEquals("{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}", detail.get("bboxJson"));
        assertEquals("OpenDataLoader", detail.get("parserName"));
        assertEquals("2.4.7", detail.get("parserVersion"));
        assertEquals("TABLE", detail.get("sourceKind"));
        assertEquals("table-17", detail.get("tableId"));
        assertEquals("Metric: Accuracy PaperLoom: 91.2", detail.get("tableText"));
        assertEquals("| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |", detail.get("tableMarkdown"));
        assertEquals(true, detail.get("tableScreenshotAvailable"));
        assertEquals("EVAL_IMPORT", detail.get("sourceType"));
        assertEquals("TEXT_ONLY", detail.get("evidenceAssetLevel"));
        assertEquals(false, detail.get("pdfEvidenceAvailable"));
        assertEquals(true, detail.get("structuredImport"));
        assertEquals(true, detail.get("evalImport"));
        assertEquals(false, detail.get("pageScreenshotAvailable"));
        assertEquals(false, detail.get("figureScreenshotAvailable"));
        assertEquals(List.of("structured_import_text_only"), detail.get("assetWarnings"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresSerializedReferenceEvidenceForHistoryDetail() {
        ChatHandler handler = newHandler();
        Map<String, Object> serializedDetail = new LinkedHashMap<>();
        serializedDetail.put("paperId", "paper-1");
        serializedDetail.put("paperTitle", "Parsed Paper Title");
        serializedDetail.put("originalFilename", "uploaded-paper.pdf");
        serializedDetail.put("pageNumber", 3);
        serializedDetail.put("anchorText", "anchor text");
        serializedDetail.put("retrievalMode", "HYBRID");
        serializedDetail.put("retrievalLabel", "混合召回");
        serializedDetail.put("retrievalQuery", "bandit method");
        serializedDetail.put("matchedChunkText", "matched chunk text");
        serializedDetail.put("evidenceSnippet", "evidence snippet");
        serializedDetail.put("score", 0.82d);
        serializedDetail.put("chunkId", 17);
        serializedDetail.put("elementType", "PARAGRAPH");
        serializedDetail.put("sectionTitle", "Method");
        serializedDetail.put("sectionLevel", 2);
        serializedDetail.put("bboxJson", "{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}");
        serializedDetail.put("parserName", "OpenDataLoader");
        serializedDetail.put("parserVersion", "2.4.7");
        serializedDetail.put("sourceKind", "TABLE");
        serializedDetail.put("tableId", "table-17");
        serializedDetail.put("tableText", "Metric: Accuracy PaperLoom: 91.2");
        serializedDetail.put("tableMarkdown", "| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |");
        serializedDetail.put("tableScreenshotAvailable", true);
        serializedDetail.put("sourceType", "EVAL_IMPORT");
        serializedDetail.put("evidenceAssetLevel", "TEXT_ONLY");
        serializedDetail.put("pdfEvidenceAvailable", false);
        serializedDetail.put("structuredImport", true);
        serializedDetail.put("evalImport", true);
        serializedDetail.put("pageScreenshotAvailable", false);
        serializedDetail.put("figureScreenshotAvailable", false);
        serializedDetail.put("assetWarnings", List.of("structured_import_text_only"));

        Map<Integer, ChatHandler.ReferenceInfo> restored = ReflectionTestUtils.invokeMethod(
                handler,
                "toReferenceInfoMap",
                Map.of("1", serializedDetail)
        );

        ChatHandler.ReferenceInfo detail = restored.get(1);
        assertEquals("paper-1", detail.paperId());
        assertEquals("Parsed Paper Title", detail.paperTitle());
        assertEquals("uploaded-paper.pdf", detail.originalFilename());
        assertEquals(3, detail.pageNumber());
        assertEquals(17, detail.chunkId());
        assertEquals("HYBRID", detail.retrievalMode());
        assertEquals(0.82d, detail.score());
        assertEquals("PARAGRAPH", detail.elementType());
        assertEquals("Method", detail.sectionTitle());
        assertEquals(2, detail.sectionLevel());
        assertEquals("{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}", detail.bboxJson());
        assertEquals("OpenDataLoader", detail.parserName());
        assertEquals("2.4.7", detail.parserVersion());
        assertEquals("TABLE", detail.sourceKind());
        assertEquals("table-17", detail.tableId());
        assertEquals("Metric: Accuracy PaperLoom: 91.2", detail.tableText());
        assertEquals("| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |", detail.tableMarkdown());
        assertEquals(true, detail.tableScreenshotAvailable());
        assertEquals("EVAL_IMPORT", detail.sourceType());
        assertEquals("TEXT_ONLY", detail.evidenceAssetLevel());
        assertEquals(false, detail.pdfEvidenceAvailable());
        assertEquals(true, detail.structuredImport());
        assertEquals(true, detail.evalImport());
        assertEquals(false, detail.pageScreenshotAvailable());
        assertEquals(false, detail.figureScreenshotAvailable());
        assertEquals(List.of("structured_import_text_only"), detail.assetWarnings());
    }

    @Test
    void restoresLegacyReferenceEvidenceWithConservativeDefaults() {
        ChatHandler handler = newHandler();
        Map<String, Object> serializedDetail = new LinkedHashMap<>();
        serializedDetail.put("paperId", "paper-1");
        serializedDetail.put("paperTitle", "Parsed Paper Title");
        serializedDetail.put("originalFilename", "uploaded-paper.pdf");
        serializedDetail.put("matchedChunkText", "matched chunk text");

        Map<Integer, ChatHandler.ReferenceInfo> restored = ReflectionTestUtils.invokeMethod(
                handler,
                "toReferenceInfoMap",
                Map.of("1", serializedDetail)
        );

        ChatHandler.ReferenceInfo detail = restored.get(1);
        assertEquals("PDF", detail.sourceType());
        assertEquals("PDF_PENDING_ASSETS", detail.evidenceAssetLevel());
        assertEquals(false, detail.pdfEvidenceAvailable());
        assertEquals(false, detail.structuredImport());
        assertEquals(false, detail.evalImport());
        assertEquals(false, detail.pageScreenshotAvailable());
        assertEquals(false, detail.figureScreenshotAvailable());
        assertEquals(List.of(), detail.assetWarnings());
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsMinerUEvidenceFieldsForHistoryDetail() {
        ChatHandler handler = newHandler();
        SearchResult result = new SearchResult(
                "paper-1",
                23,
                "Figure 2 shows the benchmark accuracy.",
                0.91d,
                "2",
                "TEAM_A",
                false,
                "Parsed Paper Title",
                "uploaded-paper.pdf",
                5,
                "Figure 2",
                "EXPANDED_HYBRID",
                "Figure 2 shows the benchmark accuracy.",
                "FIGURE",
                "Experiments",
                2,
                "{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}",
                "MinerU",
                "1.3.0",
                "FIGURE",
                null,
                null,
                null,
                false
        );
        result.setFigureId("figure-2");
        result.setFormulaId("formula-1");
        result.setEvidenceRole("FIGURE_CAPTION");
        result.setRetrievalQuery("experimental results");
        result.setRetrievalRoute("EXPANDED_HYBRID");
        result.setIntent("EXPERIMENT_RESULT");
        result.setRankReason("experiment-intent:FIGURE:FIGURE_CAPTION");

        ChatHandler.ReferenceInfo reference = ReflectionTestUtils.invokeMethod(
                handler,
                "buildReferenceInfo",
                result,
                "Parsed Paper Title",
                "有实验数据吗"
        );

        Map<String, Map<String, Object>> serialized = ReflectionTestUtils.invokeMethod(
                handler,
                "toSerializableReferenceMappings",
                Map.of(1, reference)
        );
        Map<Integer, ChatHandler.ReferenceInfo> restored = ReflectionTestUtils.invokeMethod(
                handler,
                "toReferenceInfoMap",
                serialized
        );

        ChatHandler.ReferenceInfo detail = restored.get(1);
        assertEquals("FIGURE", detail.sourceKind());
        assertEquals("figure-2", detail.figureId());
        assertEquals("formula-1", detail.formulaId());
        assertEquals("FIGURE_CAPTION", detail.evidenceRole());
        assertEquals("experimental results", detail.retrievalQuery());
        assertEquals("EXPANDED_HYBRID", detail.retrievalRoute());
        assertEquals("EXPERIMENT_RESULT", detail.intent());
        assertEquals("experiment-intent:FIGURE:FIGURE_CAPTION", detail.rankReason());
    }

    private static ChatHandler newHandler() {
        return new ChatHandler(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null
        );
    }
}
