package io.github.chzarles.paperloom.service;

import java.util.List;

public record SourceScope(
        ScopeMode mode,
        List<String> paperIds,
        Integer referenceNumber,
        Long conversationRecordId,
        RetrievalBudgetProfile retrievalBudgetProfile
) {
    public SourceScope {
        mode = mode == null ? ScopeMode.AUTO_SOURCE : mode;
        paperIds = paperIds == null ? List.of() : paperIds.stream()
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .distinct()
                .toList();
        retrievalBudgetProfile = retrievalBudgetProfile == null ? RetrievalBudgetProfile.INTERACTIVE : retrievalBudgetProfile;
    }

    public SourceScope(ScopeMode mode,
                       List<String> paperIds,
                       Integer referenceNumber,
                       Long conversationRecordId) {
        this(mode, paperIds, referenceNumber, conversationRecordId, RetrievalBudgetProfile.INTERACTIVE);
    }

    public static SourceScope auto() {
        return new SourceScope(ScopeMode.AUTO_SOURCE, List.of(), null, null);
    }

    public static SourceScope auto(RetrievalBudgetProfile retrievalBudgetProfile) {
        return new SourceScope(ScopeMode.AUTO_SOURCE, List.of(), null, null, retrievalBudgetProfile);
    }

    public static SourceScope manual(List<String> paperIds) {
        return new SourceScope(ScopeMode.MANUAL_SOURCE, paperIds, null, null);
    }

    public static SourceScope manual(List<String> paperIds, RetrievalBudgetProfile retrievalBudgetProfile) {
        return new SourceScope(ScopeMode.MANUAL_SOURCE, paperIds, null, null, retrievalBudgetProfile);
    }

    public static SourceScope reference(Integer referenceNumber, Long conversationRecordId) {
        return new SourceScope(ScopeMode.REFERENCE_SOURCE, List.of(), referenceNumber, conversationRecordId);
    }

    public static SourceScope reference(Integer referenceNumber,
                                        Long conversationRecordId,
                                        RetrievalBudgetProfile retrievalBudgetProfile) {
        return new SourceScope(ScopeMode.REFERENCE_SOURCE, List.of(), referenceNumber, conversationRecordId,
                retrievalBudgetProfile);
    }

    public static SourceScope reference(Integer referenceNumber,
                                        Long conversationRecordId,
                                        List<String> paperIds,
                                        RetrievalBudgetProfile retrievalBudgetProfile) {
        return new SourceScope(ScopeMode.REFERENCE_SOURCE, paperIds, referenceNumber, conversationRecordId,
                retrievalBudgetProfile);
    }
}
