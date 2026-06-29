package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceVerifierTest {

    @Test
    void acceptsOnlyKnownEvidenceTokens() {
        EvidenceVerifier verifier = new EvidenceVerifier();
        EvidenceLedger ledger = ledger(evidence("E1", "paper-a", "Agent harnesses adapt retrieval strategy over multiple tool calls."));

        assertTrue(verifier.verify("Agent Harness 会改变检索行为。{{E1}}", ledger).valid());
        assertFalse(verifier.verify("Agent Harness 会改变检索行为。{{E99}}", ledger).valid());
    }

    @Test
    void rejectsNakedAndLegacyCitations() {
        EvidenceVerifier verifier = new EvidenceVerifier();
        EvidenceLedger ledger = ledger(evidence("E1", "paper-a", "Agent harnesses adapt retrieval strategy over multiple tool calls."));

        assertFalse(verifier.verify("Agent Harness 会改变检索行为。[1]", ledger).valid());
        assertFalse(verifier.verify("Agent Harness 会改变检索行为。来源#1", ledger).valid());
    }

    @Test
    void doesNotUseHardcodedComparativePhraseListsForSemanticJudgment() {
        EvidenceVerifier verifier = new EvidenceVerifier();
        EvidenceLedger ledger = ledger(evidence("E1", "paper-a", "Agent harnesses adapt retrieval strategy over multiple tool calls."));

        assertTrue(verifier.verify("Agent Harness 显著优于 Grep。{{E1}}", ledger).valid());
    }

    @Test
    void rejectsQuotedPaperTitleOutsideLedgerSourceSet() {
        EvidenceVerifier verifier = new EvidenceVerifier();
        EvidenceLedger ledger = ledger(evidence("E1", "paper-a", "Agent harnesses adapt retrieval strategy over multiple tool calls."));

        assertFalse(verifier.verify("《Fake Agent Paper》也很相关。{{E1}}", ledger).valid());
    }

    private EvidenceLedger ledger(EvidenceItem item) {
        return new EvidenceLedger(
                List.of(new PaperSource(item.paperId(), item.paperTitle(), item.originalFilename())),
                List.of(item),
                new LedgerDiagnostics(1, 1, 1, "EXHAUSTED")
        );
    }

    private EvidenceItem evidence(String evidenceId, String paperId, String text) {
        return new EvidenceItem(
                evidenceId,
                paperId,
                "Title " + paperId,
                paperId + ".pdf",
                1,
                1,
                "TEXT",
                "Method",
                text,
                null,
                0.9d
        );
    }
}
