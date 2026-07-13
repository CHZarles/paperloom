package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHandlerRetrievalPolicyTest {

    @Test
    void shouldIgnoreLegacyChineseSourceReferencesInFinalResponse() {
        Map<Integer, ChatHandler.ReferenceInfo> mappings = Map.of(
                1, referenceInfo("one.pdf"),
                2, referenceInfo("two.pdf")
        );

        Map<Integer, ChatHandler.ReferenceInfo> filtered = ChatHandler.filterReferenceMappingsForResponse(
                mappings,
                "结论来自课程讲义 (来源#2: two.pdf)。"
        );

        assertTrue(filtered.isEmpty());
    }

    @Test
    void shouldKeepCompactBracketReferencesActuallyCitedInFinalResponse() {
        Map<Integer, ChatHandler.ReferenceInfo> mappings = Map.of(
                1, referenceInfo("one.pdf"),
                2, referenceInfo("two.pdf")
        );

        Map<Integer, ChatHandler.ReferenceInfo> filtered = ChatHandler.filterReferenceMappingsForResponse(
                mappings,
                "实验设置来自论文正文。[2]"
        );

        assertEquals(1, filtered.size());
        assertTrue(filtered.containsKey(2));
    }

    @Test
    void shouldIgnoreLegacyEnglishSourceReferencesInFinalResponse() {
        Map<Integer, ChatHandler.ReferenceInfo> mappings = Map.of(
                1, referenceInfo("one.pdf"),
                2, referenceInfo("two.pdf")
        );

        Map<Integer, ChatHandler.ReferenceInfo> filtered = ChatHandler.filterReferenceMappingsForResponse(
                mappings,
                "The claim is supported by source #1."
        );

        assertTrue(filtered.isEmpty());
    }

    @Test
    void shouldDropRetrievedReferencesWhenFinalResponseDoesNotCiteThem() {
        Map<Integer, ChatHandler.ReferenceInfo> mappings = Map.of(
                1, referenceInfo("one.pdf"),
                2, referenceInfo("two.pdf")
        );

        Map<Integer, ChatHandler.ReferenceInfo> filtered = ChatHandler.filterReferenceMappingsForResponse(
                mappings,
                "暂无相关信息。原因：当前论文库中未检索到可回答该问题的内容。"
        );

        assertTrue(filtered.isEmpty());
    }

    private static ChatHandler.ReferenceInfo referenceInfo(String paperTitle) {
        return new ChatHandler.ReferenceInfo(
                "md5-" + paperTitle,
                paperTitle,
                paperTitle,
                null,
                "",
                "hybrid",
                "混合召回",
                "query",
                "matched text",
                "snippet",
                1.0,
                1,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
