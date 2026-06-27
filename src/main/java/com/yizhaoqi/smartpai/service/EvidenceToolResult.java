package com.yizhaoqi.smartpai.service;

public record EvidenceToolResult(
        PlannerActionType actionType,
        EvidenceLedger ledger,
        String message
) {
    public EvidenceToolResult {
        actionType = actionType == null ? PlannerActionType.ASK_CLARIFICATION : actionType;
        ledger = ledger == null ? EvidenceLedger.empty() : ledger;
        message = message == null ? "" : message;
    }
}
