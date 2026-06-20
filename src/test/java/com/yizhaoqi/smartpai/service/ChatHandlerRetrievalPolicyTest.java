package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHandlerRetrievalPolicyTest {

    @Test
    void shouldSearchPapersForDomainQuestionEvenWhenPhrasedCasually() {
        assertTrue(ChatHandler.shouldUseInitialPaperSearch("你懂单臂老虎机吗"));
        assertTrue(ChatHandler.shouldUseInitialPaperSearch("Bandits（老虎机问题）是什么"));
    }

    @Test
    void shouldSkipInitialPaperSearchForExplicitBypassAndGreeting() {
        assertFalse(ChatHandler.shouldUseInitialPaperSearch("不要查论文，直接回答你懂单臂老虎机吗"));
        assertFalse(ChatHandler.shouldUseInitialPaperSearch("你好"));
    }

    @Test
    void shouldBuildFocusedRetrievalQueriesFromCasualQuestion() {
        List<String> queries = ChatHandler.buildInitialPaperQueries("你懂单臂老虎机吗");

        assertEquals(List.of("单臂老虎机", "你懂单臂老虎机吗"), queries);
    }

    @Test
    void shouldKeepOnlyReferencesActuallyCitedInFinalResponse() {
        Map<Integer, ChatHandler.ReferenceInfo> mappings = Map.of(
                1, referenceInfo("one.pdf"),
                2, referenceInfo("two.pdf")
        );

        Map<Integer, ChatHandler.ReferenceInfo> filtered = ChatHandler.filterReferenceMappingsForResponse(
                mappings,
                "结论来自课程讲义 (来源#2: two.pdf)。"
        );

        assertEquals(1, filtered.size());
        assertTrue(filtered.containsKey(2));
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
                null,
                "",
                "hybrid",
                "混合召回",
                "query",
                "matched text",
                "snippet",
                1.0,
                1
        );
    }
}
