package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceLedgerServiceTest {

    @Test
    void buildsLedgerWithUniqueSourcesAndOnlyUsableEvidence() {
        EvidenceLedgerService service = new EvidenceLedgerService();

        EvidenceLedger ledger = service.fromSearchResults(List.of(
                result("paper-a", 1, "5", 0.95),
                result("paper-a", 2, "Agent harnesses dynamically choose search tools and inspect intermediate retrieval results.", 0.9),
                result("paper-a", 3, "Keywords: Agentic Search; Semantic Search; Lexical Search; Agent Harnesses; Grep", 0.8),
                result("paper-b", 1, "Grep can be exposed as a native command that agents use to construct lexical search strategies.", 0.7)
        ), RetrievalBudget.forQa());

        assertEquals(2, ledger.sourceSet().size());
        assertEquals(List.of("paper-a", "paper-b"), ledger.sourceSet().stream().map(PaperSource::paperId).toList());
        assertEquals(2, ledger.evidence().size());
        assertEquals(List.of("E1", "E2"), ledger.evidence().stream().map(EvidenceItem::evidenceId).toList());
        assertTrue(ledger.evidence().stream().noneMatch(item -> item.matchedText().equals("5")));
        assertEquals(4, ledger.diagnostics().scannedCount());
        assertEquals(2, ledger.diagnostics().acceptedEvidenceCount());
    }

    private SearchResult result(String paperId, int chunkId, String text, double score) {
        SearchResult result = new SearchResult(paperId, chunkId, text, score);
        result.setPaperTitle("Title " + paperId);
        result.setOriginalFilename(paperId + ".pdf");
        result.setMatchedChunkText(text);
        result.setSourceKind("TEXT");
        result.setSectionTitle("Section " + chunkId);
        result.setPageNumber(chunkId);
        return result;
    }
}
