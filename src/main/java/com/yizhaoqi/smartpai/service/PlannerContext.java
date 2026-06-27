package com.yizhaoqi.smartpai.service;

public record PlannerContext(
        String requesterId,
        String userQuery,
        PaperAnswerService.Intent route,
        SourceScope scope,
        EvidenceLedger ledger
) {
    public PlannerContext {
        requesterId = requesterId == null ? "" : requesterId;
        userQuery = userQuery == null ? "" : userQuery;
        route = route == null ? PaperAnswerService.Intent.AUTO_SOURCE_QA : route;
        scope = scope == null ? SourceScope.auto() : scope;
        ledger = ledger == null ? EvidenceLedger.empty() : ledger;
    }
}
