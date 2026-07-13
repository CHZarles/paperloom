package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperRetrievalServiceTest {

    @Test
    void pageBatchSizeIsOnlyTechnicalPagination() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = new RetrievalBudget(Duration.ofSeconds(3), 8_000, 0.3d, 0.03d, 1);
        when(hybridSearchService.adaptiveSearchWithPermission(eq("agent"), eq("u1"), eq(budget), eq(List.of()))).thenReturn(adaptiveResult(List.of(
                result("paper-a", 1, "Agent harnesses dynamically choose retrieval tools and inspect the results before answering.", 0.9),
                result("paper-b", 1, "Tool-using agents can combine keyword search with context-aware follow-up retrieval.", 0.8)
        ), 2, PaperRetrievalService.StopReason.EXHAUSTED));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.retrieve("agent", "u1", budget);

        assertEquals(2, retrieval.results().size());
        assertEquals(2, retrieval.diagnostics().acceptedEvidenceCount());
        verify(hybridSearchService).adaptiveSearchWithPermission("agent", "u1", budget, List.of());
    }

    @Test
    void autoScopeUsesProductPaperCorpusAllowlistBeforeHybridSearch() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        ProductPaperCorpus corpus = mock(ProductPaperCorpus.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        when(corpus.resolveAccessibleSearchablePaperIds(eq("u1"), eq(SourceScope.auto())))
                .thenReturn(new ProductPaperCorpus.ProductPaperSet(
                        List.of("paper-a"),
                        2,
                        1,
                        List.of()
                ));
        when(hybridSearchService.adaptiveSearchWithPermission(eq("agent"), eq("u1"), eq(budget), eq(List.of("paper-a"))))
                .thenReturn(adaptiveResult(List.of(
                        result("paper-a", 1, "Allowed product evidence about agent retrieval.", 0.9),
                        result("orphan-paper", 1, "Orphan chunk must not be product evidence.", 0.99)
                ), 2, PaperRetrievalService.StopReason.EXHAUSTED));
        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService, corpus);

        PaperRetrievalService.RetrievalResult retrieval = service.retrieve("agent", "u1", budget);

        assertEquals(List.of("paper-a"), retrieval.results().stream().map(SearchResult::getPaperId).toList());
        verify(hybridSearchService).adaptiveSearchWithPermission("agent", "u1", budget, List.of("paper-a"));
    }

    @Test
    void filtersUnusableEvidenceAfterHybridSearch() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        when(hybridSearchService.adaptiveSearchWithPermission(eq("agent"), eq("u1"), eq(budget), eq(List.of()))).thenReturn(adaptiveResult(List.of(
                result("paper-a", 1, "5", 0.9),
                result("paper-a", 2, "Agent harnesses can adapt retrieval strategy over multiple tool calls.", 0.9)
        ), 2, PaperRetrievalService.StopReason.EXHAUSTED));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.retrieve("agent", "u1", budget);

        assertEquals(1, retrieval.results().size());
        assertTrue(retrieval.results().get(0).getTextContent().contains("adapt retrieval"));
        assertEquals(2, retrieval.diagnostics().scannedCount());
        assertEquals(1, retrieval.diagnostics().acceptedEvidenceCount());
    }

    @Test
    void propagatesAdaptiveStopReasonFromHybridSearch() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        when(hybridSearchService.adaptiveSearchWithPermission(eq("agent"), eq("u1"), eq(budget), eq(List.of()))).thenReturn(adaptiveResult(List.of(
                result("paper-a", 1, "Agent harnesses can adapt retrieval strategy over multiple tool calls.", 0.9)
        ), 24, PaperRetrievalService.StopReason.CONTEXT_BUDGET));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.retrieve("agent", "u1", budget);

        assertEquals(24, retrieval.diagnostics().scannedCount());
        assertEquals(PaperRetrievalService.StopReason.CONTEXT_BUDGET, retrieval.diagnostics().stopReason());
    }

    @Test
    void passesManualScopePaperIdsToHybridSearch() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        List<String> scopePaperIds = List.of("paper-a");
        when(hybridSearchService.adaptiveSearchWithPermission(eq("agent"), eq("u1"), eq(budget), eq(scopePaperIds)))
                .thenReturn(adaptiveResult(List.of(
                        result("paper-a", 1, "Scoped paper evidence explains the target agent harness.", 0.9)
                ), 1, PaperRetrievalService.StopReason.EXHAUSTED));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.retrieve("agent", "u1", budget, scopePaperIds);

        assertEquals(1, retrieval.results().size());
        verify(hybridSearchService).adaptiveSearchWithPermission("agent", "u1", budget, scopePaperIds);
    }

    @Test
    void dropsHybridResultsOutsideManualScopePaperIds() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        List<String> scopePaperIds = List.of("paper-a");
        when(hybridSearchService.adaptiveSearchWithPermission(eq("agent"), eq("u1"), eq(budget), eq(scopePaperIds)))
                .thenReturn(adaptiveResult(List.of(
                        result("paper-a", 1, "Scoped paper evidence explains the target agent harness.", 0.9),
                        result("paper-leak", 1, "Out-of-scope evidence must not escape scoped retrieval.", 0.99)
                ), 2, PaperRetrievalService.StopReason.EXHAUSTED));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.retrieve("agent", "u1", budget, scopePaperIds);

        assertEquals(List.of("paper-a"), retrieval.results().stream().map(SearchResult::getPaperId).toList());
        assertEquals(1, retrieval.diagnostics().acceptedEvidenceCount());
        assertEquals(1, retrieval.diagnostics().sourceCount());
    }

    @Test
    void doesNotUseHardcodedEnglishExpansionWhenChineseQueryDoesNotMatchDirectly() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        when(hybridSearchService.adaptiveSearchWithPermission(eq("讲一讲高噪声场景"), eq("u1"), eq(budget), eq(List.of())))
                .thenReturn(adaptiveResult(List.of(), 0, PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.retrieve("讲一讲高噪声场景", "u1", budget);

        assertEquals(0, retrieval.results().size());
        assertEquals(List.of("讲一讲高噪声场景"), retrieval.attemptedQueries());
        verify(hybridSearchService, never()).adaptiveSearchWithPermission(eq("increasing noise"), anyString(), any(), any());
    }

    @Test
    void discoverPapersPrefersPaperLevelTitleAndAbstractCoverage() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        SearchResult decoy = result("paper-decoy", 1, "hallucination hallucination hallucination hallucination hallucination", 0.95);
        decoy.setPaperTitle("Hallucination in generation");
        SearchResult gold = result("paper-gold", 1, "Abstract: post-hoc hallucination detection at token and sentence level for neural sequence generation.", 0.8);
        gold.setPaperTitle("Token and sentence level hallucination detection");
        when(hybridSearchService.searchPaperCandidatesWithPermission(eq("post-hoc hallucination detection"), eq("u1"), eq(budget), eq(List.of())))
                .thenReturn(adaptiveResult(List.of(gold, decoy), 2, PaperRetrievalService.StopReason.EXHAUSTED));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.discoverPapers(
                "post-hoc hallucination detection",
                "u1",
                budget
        );

        assertEquals(PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH, retrieval.plan().intent());
        assertEquals("paper-gold", retrieval.results().get(0).getPaperId());
        assertEquals("PAPER_LEVEL", retrieval.results().get(0).getRetrievalRoute());
        assertTrue(retrieval.results().get(0).getRankReason().contains("literature-search"));
    }

    @Test
    void discoverPapersDoesNotDropDomainTermsWithAStaticStopwordList() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        SearchResult decoy = result("paper-decoy", 0, "Abstract: retrieval overview.", 0.9);
        decoy.setPaperTitle("Retrieval Survey");
        decoy.setSourceKind("PAPER");
        SearchResult gold = result("paper-gold", 0, "Abstract: method retrieval for paper evidence.", 0.7);
        gold.setPaperTitle("Method Retrieval");
        gold.setSourceKind("PAPER");
        when(hybridSearchService.searchPaperCandidatesWithPermission(eq("method retrieval"), eq("u1"), eq(budget), eq(List.of())))
                .thenReturn(adaptiveResult(List.of(decoy, gold), 2, PaperRetrievalService.StopReason.EXHAUSTED));
        when(hybridSearchService.adaptiveSearchWithPermission(eq("method retrieval"), eq("u1"), eq(budget), eq(List.of("paper-decoy", "paper-gold"))))
                .thenReturn(adaptiveResult(List.of(), 0, PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.discoverPapers("method retrieval", "u1", budget);

        assertEquals("paper-gold", retrieval.results().get(0).getPaperId());
    }

    @Test
    void literatureSearchKeepsOnlyBestEvidencePerPaperCandidate() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        SearchResult weakerSamePaper = result(
                "paper-a",
                1,
                "Agent harness paper mentions retrieval tools in passing.",
                0.8
        );
        weakerSamePaper.setPaperTitle("Agent Harness Retrieval");
        SearchResult strongerSamePaper = result(
                "paper-a",
                2,
                "Abstract: agent harness retrieval coordinates tool calls, paper search, and evidence inspection.",
                0.95
        );
        strongerSamePaper.setPaperTitle("Agent Harness Retrieval");
        SearchResult otherPaper = result(
                "paper-b",
                1,
                "Abstract: retrieval agents use query planning for paper recommendation.",
                0.7
        );
        otherPaper.setPaperTitle("Retrieval Agents for Papers");
        when(hybridSearchService.searchPaperCandidatesWithPermission(eq("agent harness retrieval"), eq("u1"), eq(budget), eq(List.of())))
                .thenReturn(adaptiveResult(List.of(weakerSamePaper, strongerSamePaper, otherPaper), 3, PaperRetrievalService.StopReason.EXHAUSTED));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.discoverPapers(
                "agent harness retrieval",
                "u1",
                budget
        );

        assertEquals(2, retrieval.results().size());
        assertEquals("paper-a", retrieval.results().get(0).getPaperId());
        assertEquals(2, retrieval.results().get(0).getChunkId());
        assertEquals("paper-b", retrieval.results().get(1).getPaperId());
        assertEquals(2, retrieval.diagnostics().acceptedEvidenceCount());
        assertEquals(2, retrieval.diagnostics().sourceCount());
    }

    @Test
    void literatureSearchPreservesPaperMetadataCandidatesWhenScopedEvidenceIsSparse() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        SearchResult goldCandidate = result(
                "paper-gold",
                0,
                "title: Post-hoc Hallucination Detection\nabstract: detects hallucinations after generation",
                3.2
        );
        SearchResult decoyCandidate = result(
                "paper-decoy",
                0,
                "title: Hallucination Survey\nabstract: broad generation survey",
                2.0
        );
        when(hybridSearchService.searchPaperCandidatesWithPermission(
                eq("post-hoc hallucination detection"),
                eq("u1"),
                eq(budget),
                eq(List.of())
        )).thenReturn(adaptiveResult(
                List.of(goldCandidate, decoyCandidate),
                2,
                PaperRetrievalService.StopReason.EXHAUSTED
        ));
        SearchResult goldEvidence = result(
                "paper-gold",
                7,
                "Abstract: post-hoc hallucination detection evaluates generated claims against evidence.",
                0.91
        );
        when(hybridSearchService.adaptiveSearchWithPermission(
                eq("post-hoc hallucination detection"),
                eq("u1"),
                eq(budget),
                eq(List.of("paper-gold", "paper-decoy"))
        )).thenReturn(adaptiveResult(
                List.of(goldEvidence),
                1,
                PaperRetrievalService.StopReason.EXHAUSTED
        ));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.discoverPapers(
                "post-hoc hallucination detection",
                "u1",
                budget
        );

        List<String> retrievedPaperIds = retrieval.results().stream().map(SearchResult::getPaperId).toList();
        assertEquals(2, retrievedPaperIds.size());
        assertTrue(retrievedPaperIds.contains("paper-gold"));
        assertTrue(retrievedPaperIds.contains("paper-decoy"));
        SearchResult goldResult = retrieval.results().stream()
                .filter(result -> "paper-gold".equals(result.getPaperId()))
                .findFirst()
                .orElseThrow();
        SearchResult decoyResult = retrieval.results().stream()
                .filter(result -> "paper-decoy".equals(result.getPaperId()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, goldResult.getChunkId());
        assertEquals(0, decoyResult.getChunkId());
        assertEquals("PAPER_LEVEL", goldResult.getRetrievalRoute());
        assertEquals("PAPER_LEVEL", decoyResult.getRetrievalRoute());
        assertEquals(3, retrieval.diagnostics().scannedCount());
        inOrder(hybridSearchService).verify(hybridSearchService)
                .searchPaperCandidatesWithPermission("post-hoc hallucination detection", "u1", budget, List.of());
        verify(hybridSearchService)
                .adaptiveSearchWithPermission("post-hoc hallucination detection", "u1", budget, List.of("paper-gold", "paper-decoy"));
    }

    @Test
    void discoverPapersForBareTopicForcesPaperLevelSearch() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        SearchResult candidate = result(
                "paper-agent",
                0,
                "Title: Agent Coordination\nAbstract: Multi-agent coordination with tool use.",
                3.4
        );
        candidate.setSourceKind("PAPER");
        candidate.setRetrievalMode("PAPER_METADATA");
        when(hybridSearchService.searchPaperCandidatesWithPermission(
                eq("agent"),
                eq("u1"),
                eq(budget),
                eq(List.of())
        )).thenReturn(adaptiveResult(
                List.of(candidate),
                1,
                PaperRetrievalService.StopReason.EXHAUSTED
        ));
        when(hybridSearchService.adaptiveSearchWithPermission(
                eq("agent"),
                eq("u1"),
                eq(budget),
                eq(List.of("paper-agent"))
        )).thenReturn(adaptiveResult(
                List.of(),
                0,
                PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
        ));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.discoverPapers("agent", "u1", budget);

        assertEquals(PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH, retrieval.plan().intent());
        assertTrue(retrieval.plan().paperLevelSearch());
        assertEquals("paper-agent", retrieval.results().get(0).getPaperId());
        assertEquals("PAPER_LEVEL", retrieval.results().get(0).getRetrievalRoute());
        verify(hybridSearchService).searchPaperCandidatesWithPermission("agent", "u1", budget, List.of());
    }

    @Test
    void literatureSearchFallsBackToChunkBridgeWhenPaperCandidateSearchIsEmpty() {
        PaperQueryPlanner planner = new PaperQueryPlanner();
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        when(hybridSearchService.searchPaperCandidatesWithPermission(
                eq("retrieval augmented generation"),
                eq("u1"),
                eq(budget),
                eq(List.of())
        )).thenReturn(adaptiveResult(
                List.of(),
                0,
                PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
        ));
        SearchResult fallbackEvidence = result(
                "paper-fallback",
                3,
                "Abstract: retrieval augmented generation combines retrieval and generation for grounded answers.",
                0.88
        );
        when(hybridSearchService.adaptiveSearchWithPermission(
                eq("retrieval augmented generation"),
                eq("u1"),
                eq(budget),
                eq(List.of())
        )).thenReturn(adaptiveResult(
                List.of(fallbackEvidence),
                1,
                PaperRetrievalService.StopReason.EXHAUSTED
        ));

        PaperRetrievalService service = new PaperRetrievalService(planner, hybridSearchService);

        PaperRetrievalService.RetrievalResult retrieval = service.discoverPapers(
                "retrieval augmented generation",
                "u1",
                budget
        );

        assertEquals(1, retrieval.results().size());
        assertEquals("paper-fallback", retrieval.results().get(0).getPaperId());
        assertEquals("PAPER_LEVEL", retrieval.results().get(0).getRetrievalRoute());
        assertEquals(1, retrieval.diagnostics().scannedCount());
    }

    private SearchResult result(String paperId, int chunkId, String text, double score) {
        SearchResult result = new SearchResult(paperId, chunkId, text, score);
        result.setPaperTitle("Title " + paperId);
        result.setOriginalFilename(paperId + ".pdf");
        result.setMatchedChunkText(text);
        result.setSourceKind("TEXT");
        return result;
    }

    private HybridSearchService.AdaptiveSearchResult adaptiveResult(List<SearchResult> results,
                                                                    int scannedCount,
                                                                    PaperRetrievalService.StopReason stopReason) {
        return new HybridSearchService.AdaptiveSearchResult(
                results,
                scannedCount,
                results.size(),
                (int) results.stream().map(SearchResult::getPaperId).distinct().count(),
                stopReason
        );
    }
}
