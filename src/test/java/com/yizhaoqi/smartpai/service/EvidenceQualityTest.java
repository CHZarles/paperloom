package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceQualityTest {

    @Test
    void rejectsPageNumberFragmentsAndParserArtifacts() {
        assertFalse(EvidenceQuality.isUsable(null));
        assertFalse(EvidenceQuality.isUsable(""));
        assertFalse(EvidenceQuality.isUsable("5"));
        assertFalse(EvidenceQuality.isUsable("2.3"));
        assertFalse(EvidenceQuality.isUsable("..."));
        assertFalse(EvidenceQuality.isUsable("Page 5"));
        assertFalse(EvidenceQuality.isUsable("第 7 页"));
        assertFalse(EvidenceQuality.isUsable("www.example.org"));
    }

    @Test
    void rejectsKeywordOnlyListsAsCitationEvidence() {
        assertFalse(EvidenceQuality.isUsable(
                "Agentic Search, Semantic Search, Lexical Search, Context Engineering, Agent Harnesses, LLM Evaluation, Grep"));
        assertFalse(EvidenceQuality.isUsable(
                "Keywords: Agentic Search; Semantic Search; Lexical Search; Context Engineering; Agent Harnesses"));
    }

    @Test
    void acceptsMeaningfulPaperEvidence() {
        assertTrue(EvidenceQuality.isUsable(
                "Agent harnesses can issue follow-up retrieval calls and inspect intermediate results before deciding how to answer."));
        assertTrue(EvidenceQuality.isUsable(
                "Table 2 reports accuracy, recall, and latency for grep-only and vector-only retrieval strategies."));
        assertTrue(EvidenceQuality.isUsable(
                "Figure 3: The retrieval controller progressively narrows the source set as evidence accumulates."));
    }
}
