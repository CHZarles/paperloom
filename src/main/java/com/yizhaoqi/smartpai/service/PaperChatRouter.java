package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PaperChatRouter {

    private static final Pattern USER_REFERENCE_PATTERN = Pattern.compile("\\[(\\d+)]");

    public PaperAnswerService.Intent route(String userMessage, PaperAnswerService.AnswerScope scope) {
        String normalized = normalize(userMessage);
        if (Set.of("hi", "hello", "hey", "你好", "您好", "谢谢", "thanks", "ok", "好的", "在吗").contains(normalized)) {
            return PaperAnswerService.Intent.SMALLTALK;
        }
        if (scope != null && (scope.referenceNumber() != null || scope.hasReferenceSeed())) {
            return PaperAnswerService.Intent.REFERENCE_QA;
        }
        if (scope != null && !scope.paperIds().isEmpty()) {
            return PaperAnswerService.Intent.MANUAL_SOURCE_QA;
        }
        String lower = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (isNonPaperSystemQuestion(lower, normalized)) {
            return PaperAnswerService.Intent.CLARIFY;
        }
        if (USER_REFERENCE_PATTERN.matcher(userMessage == null ? "" : userMessage).find()
                || lower.contains("这个引用")
                || lower.contains("第一个引用")
                || lower.contains("第二个引用")) {
            return PaperAnswerService.Intent.REFERENCE_QA;
        }
        if (lower.contains("进一步解释")
                || lower.contains("继续")
                || lower.contains("展开说")
                || lower.contains("再详细点")
                || lower.contains("为什么")
                || lower.contains("什么意思")
                || lower.contains("详细讲解")
                || lower.contains("展开")
                || lower.contains("讲第一个")
                || lower.contains("这篇呢")) {
            return PaperAnswerService.Intent.FOLLOW_UP;
        }
        return PaperAnswerService.Intent.AUTO_SOURCE_QA;
    }

    private boolean isNonPaperSystemQuestion(String lower, String normalized) {
        return lower.contains("session")
                || normalized.contains("会话id")
                || normalized.contains("当前会话")
                || normalized.contains("我的id");
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s!！?？。,.，;；:：、\"'“”‘’()（）\\[\\]{}<>《》]+", "");
    }
}
