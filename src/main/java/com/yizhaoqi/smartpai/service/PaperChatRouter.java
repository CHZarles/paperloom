package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class PaperChatRouter {

    private static final Pattern USER_REFERENCE_PATTERN = Pattern.compile("\\[(\\d+)]");

    public PaperAnswerService.Intent route(String userMessage, PaperAnswerService.AnswerScope scope) {
        if (scope != null && (scope.referenceNumber() != null || scope.hasReferenceSeed())) {
            return PaperAnswerService.Intent.REFERENCE_QA;
        }
        if (userMessage == null || userMessage.isBlank()) {
            return PaperAnswerService.Intent.CLARIFY;
        }
        if (USER_REFERENCE_PATTERN.matcher(userMessage).find()) {
            return PaperAnswerService.Intent.REFERENCE_QA;
        }
        return PaperAnswerService.Intent.AUTO_SOURCE_QA;
    }
}
