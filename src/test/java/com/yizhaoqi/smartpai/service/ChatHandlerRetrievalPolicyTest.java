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
        assertFalse(ChatHandler.shouldUseInitialPaperSearch("hi"));
        assertFalse(ChatHandler.shouldUseInitialPaperSearch("在吗？"));
        assertFalse(ChatHandler.shouldUseInitialPaperSearch("OK."));
        assertFalse(ChatHandler.shouldUseInitialPaperSearch("好的"));
    }

    @Test
    void shouldOnlyClassifyExactSmalltalkAsSmalltalk() {
        assertTrue(ChatHandler.isSmalltalkMessage("hi"));
        assertTrue(ChatHandler.isSmalltalkMessage("你好！"));
        assertTrue(ChatHandler.isSmalltalkMessage("在吗？"));

        assertFalse(ChatHandler.isSmalltalkMessage("论文实验数据"));
        assertFalse(ChatHandler.isSmalltalkMessage("我说论文实验数据"));
        assertFalse(ChatHandler.isSmalltalkMessage("这篇论文讲了什么"));
        assertFalse(ChatHandler.isSmalltalkMessage("agent 相关内容"));
    }

    @Test
    void shouldBuildFocusedRetrievalQueriesFromCasualQuestion() {
        List<String> queries = ChatHandler.buildInitialPaperQueries("你懂单臂老虎机吗");

        assertEquals(List.of("单臂老虎机", "你懂单臂老虎机吗"), queries);
    }

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
