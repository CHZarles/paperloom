package com.yizhaoqi.smartpai.service;

import java.util.List;

public record PlannerAction(
        PlannerActionType type,
        String query,
        String reason,
        List<String> paperIds,
        Integer referenceNumber,
        Integer pageNumber,
        Integer windowRadius
) {
    public PlannerAction {
        type = type == null ? PlannerActionType.ASK_CLARIFICATION : type;
        query = query == null ? "" : query.trim();
        reason = reason == null ? "" : reason.trim();
        paperIds = paperIds == null ? List.of() : paperIds.stream()
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .distinct()
                .toList();
        windowRadius = windowRadius == null ? null : Math.max(0, windowRadius);
    }

    public PlannerAction(PlannerActionType type,
                         String query,
                         String reason,
                         List<String> paperIds,
                         Integer referenceNumber) {
        this(type, query, reason, paperIds, referenceNumber, null, null);
    }

    public static PlannerAction clarify(String reason) {
        return new PlannerAction(PlannerActionType.ASK_CLARIFICATION, "", reason, List.of(), null, null, null);
    }
}
