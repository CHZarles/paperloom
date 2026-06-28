package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvidenceLedgerService {

    public EvidenceLedger fromSearchResults(List<SearchResult> results, RetrievalBudget budget) {
        RetrievalBudget effectiveBudget = budget == null ? RetrievalBudget.forQa() : budget;
        List<SearchResult> safeResults = results == null ? List.of() : results;
        Map<String, PaperSource> sources = new LinkedHashMap<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        int tokenEstimate = 0;

        for (SearchResult result : safeResults) {
            if (result == null
                    || result.getPaperId() == null
                    || result.getChunkId() == null
                    || !EvidenceQuality.isUsable(result, effectiveBudget.minScore())) {
                continue;
            }
            String matchedText = EvidenceQuality.bestEvidenceText(result);
            int nextTokens = estimateTokens(matchedText);
            if (!evidence.isEmpty() && tokenEstimate + nextTokens > effectiveBudget.contextTokenBudget()) {
                break;
            }
            sources.putIfAbsent(result.getPaperId(), new PaperSource(
                    result.getPaperId(),
                    displayTitle(result),
                    result.getOriginalFilename()
            ));
            evidence.add(new EvidenceItem(
                    "E" + (evidence.size() + 1),
                    result.getPaperId(),
                    displayTitle(result),
                    result.getOriginalFilename(),
                    result.getPageNumber(),
                    result.getChunkId(),
                    result.getSourceKind() == null || result.getSourceKind().isBlank() ? "TEXT" : result.getSourceKind(),
                    result.getSectionTitle(),
                    matchedText,
                    result.getBboxJson(),
                    result.getScore(),
                    result.getSourceType(),
                    result.getEvidenceAssetLevel(),
                    result.getPdfEvidenceAvailable(),
                    result.getPageScreenshotAvailable(),
                    result.getFigureScreenshotAvailable(),
                    result.getAssetWarnings(),
                    result.getTableId(),
                    result.getFigureId(),
                    result.getFormulaId(),
                    result.getEvidenceRole(),
                    result.getTableText(),
                    result.getTableMarkdown(),
                    result.getTableScreenshotAvailable()
            ));
            tokenEstimate += nextTokens;
        }

        String stopReason = evidence.isEmpty() ? "NO_USABLE_EVIDENCE" : "EXHAUSTED";
        return new EvidenceLedger(
                List.copyOf(sources.values()),
                List.copyOf(evidence),
                new LedgerDiagnostics(safeResults.size(), evidence.size(), sources.size(), stopReason)
        );
    }

    private String displayTitle(SearchResult result) {
        if (result.getPaperTitle() != null && !result.getPaperTitle().isBlank()) {
            return result.getPaperTitle();
        }
        return result.getOriginalFilename() == null ? "" : result.getOriginalFilename();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
