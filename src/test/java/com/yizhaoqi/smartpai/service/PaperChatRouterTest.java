package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperChatRouterTest {

    private final PaperChatRouter router = new PaperChatRouter();

    @Test
    void routesOnlyStructuralCasesBeforeLlmTaskRouting() {
        assertEquals(PaperAnswerService.Intent.CLARIFY, router.route("", null));
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route("你好", null));
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route("现在的session id是什么", null));
    }

    @Test
    void routesLibraryScopedRequestsWithoutSemanticGuessing() {
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route("推荐一些 grep", null));
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route("有什么agent相关的论文吗？", null));
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route(
                "这篇论文讲了什么",
                new PaperAnswerService.AnswerScope(
                        List.of("paper-a"), List.of("Title paper-a"), null, null,
                        null, null, null, null, null, null, null, null
                )
        ));
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route(
                "有多少论文可以检索",
                new PaperAnswerService.AnswerScope(
                        List.of("paper-a"), List.of("Title paper-a"), null, null,
                        null, null, null, null, null, null, null, null
                )
        ));
        assertEquals(PaperAnswerService.Intent.REFERENCE_QA, router.route("解释 [1]", null));
    }

    @Test
    void leavesFollowUpSemanticsToTaskRouter() {
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route("详细讲解", null));
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route("为什么", null));
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route("agent harness 和 grep 有什么关系", null));
    }

    @Test
    void explicitWhyQuestionRoutesToAutoSourceQaForIntentClassification() {
        assertEquals(
                PaperAnswerService.Intent.AUTO_SOURCE_QA,
                router.route("为什么 LoRA 可以用低秩更新替代全量微调？", null)
        );
    }
}
