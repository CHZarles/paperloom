package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperChatRouterTest {

    private final PaperChatRouter router = new PaperChatRouter();

    @Test
    void routesSmalltalkAndSystemQuestionsWithoutRag() {
        assertEquals(PaperAnswerService.Intent.SMALLTALK, router.route("你好", null));
        assertEquals(PaperAnswerService.Intent.CLARIFY, router.route("现在的session id是什么", null));
    }

    @Test
    void routesLibraryManualAndReferenceScopes() {
        assertEquals(PaperAnswerService.Intent.LIBRARY_SEARCH, router.route("推荐一些 grep", null));
        assertEquals(PaperAnswerService.Intent.MANUAL_SOURCE_QA, router.route(
                "这篇论文讲了什么",
                new PaperAnswerService.AnswerScope(
                        List.of("paper-a"), List.of("Title paper-a"), null, null,
                        null, null, null, null, null, null, null, null
                )
        ));
        assertEquals(PaperAnswerService.Intent.REFERENCE_QA, router.route("解释 [1]", null));
    }

    @Test
    void routesLowInformationFollowUpSeparatelyFromAutoSourceQa() {
        assertEquals(PaperAnswerService.Intent.FOLLOW_UP, router.route("详细讲解", null));
        assertEquals(PaperAnswerService.Intent.AUTO_SOURCE_QA, router.route("agent harness 和 grep 有什么关系", null));
    }
}
