package com.yizhaoqi.smartpai.service;

record ReadingToolCallValidation(boolean isAllowed, String reason) {

    static ReadingToolCallValidation allowed() {
        return new ReadingToolCallValidation(true, "");
    }

    static ReadingToolCallValidation rejected(String reason) {
        return new ReadingToolCallValidation(false, reason);
    }
}
