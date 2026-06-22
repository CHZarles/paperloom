package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
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
                "2.4.7"
        );

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
                        "2.4.7"
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
                new ObjectMapper(),
                null
        );
    }
}
