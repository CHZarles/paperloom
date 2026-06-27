package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;

import java.util.List;
import java.util.Map;

@Service
public class EvidenceAnswerGenerator {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceAnswerGenerator.class);
    private static final int ANSWER_EVIDENCE_TOKEN_BUDGET = 1_400;
    private static final int ANSWER_MAX_COMPLETION_TOKENS = 500;

    private final LlmProviderRouter llmProviderRouter;
    private final EvidenceVerifier evidenceVerifier;

    public EvidenceAnswerGenerator(LlmProviderRouter llmProviderRouter, EvidenceVerifier evidenceVerifier) {
        this.llmProviderRouter = llmProviderRouter;
        this.evidenceVerifier = evidenceVerifier;
    }

    public GeneratedAnswer generate(String requesterId, String userMessage, EvidenceLedger ledger) {
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        EvidenceLedger answerLedger = packForAnswer(safeLedger);
        try {
            logger.info("evidence answer generation started: requesterId={}, queryLength={}, evidenceCount={}, packedEvidenceCount={}, sourceCount={}",
                    requesterId,
                    userMessage == null ? 0 : userMessage.length(),
                    safeLedger.evidence().size(),
                    answerLedger.evidence().size(),
                    answerLedger.sourceSet().size());
            LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                    requesterId,
                    buildEvidenceMessages(userMessage, answerLedger),
                    List.of(),
                    ANSWER_MAX_COMPLETION_TOKENS
            );
            EvidenceVerifier.VerificationResult verification = evidenceVerifier.verify(turn.content(), answerLedger);
            logger.info("evidence answer generation finished: requesterId={}, contentLength={}, valid={}, verifierReason={}, promptTokens={}, completionTokens={}",
                    requesterId,
                    turn.content() == null ? 0 : turn.content().length(),
                    verification.valid(),
                    verification.reason(),
                    turn.promptTokens(),
                    turn.completionTokens());
            return new GeneratedAnswer(
                    turn.content(),
                    verification.valid(),
                    verification.reason(),
                    turn.promptTokens(),
                    turn.completionTokens()
            );
        } catch (RateLimitExceededException exception) {
            logger.warn("evidence answer generation quota exceeded: requesterId={}, queryLength={}, evidenceCount={}, sourceCount={}, message={}",
                    requesterId,
                    userMessage == null ? 0 : userMessage.length(),
                    safeLedger.evidence().size(),
                    answerLedger.sourceSet().size(),
                    exception.getMessage());
            return new GeneratedAnswer("", false, "llm_quota_exceeded", 0, 0);
        } catch (Exception exception) {
            logger.warn("evidence answer generation failed: requesterId={}, queryLength={}, evidenceCount={}, sourceCount={}",
                    requesterId,
                    userMessage == null ? 0 : userMessage.length(),
                    safeLedger.evidence().size(),
                    answerLedger.sourceSet().size(),
                    exception);
            return new GeneratedAnswer("", false, "llm_failed", 0, 0);
        }
    }

    private EvidenceLedger packForAnswer(EvidenceLedger ledger) {
        if (ledger.evidence().isEmpty()) {
            return ledger;
        }
        int tokenEstimate = 0;
        List<EvidenceItem> packed = new java.util.ArrayList<>();
        for (EvidenceItem item : ledger.evidence()) {
            int itemTokens = estimateEvidenceTokens(item);
            if (!packed.isEmpty() && tokenEstimate + itemTokens > ANSWER_EVIDENCE_TOKEN_BUDGET) {
                break;
            }
            packed.add(item);
            tokenEstimate += itemTokens;
            if (tokenEstimate >= ANSWER_EVIDENCE_TOKEN_BUDGET) {
                break;
            }
        }
        if (packed.size() == ledger.evidence().size()) {
            return ledger;
        }
        java.util.LinkedHashMap<String, PaperSource> sources = new java.util.LinkedHashMap<>();
        for (EvidenceItem item : packed) {
            sources.putIfAbsent(item.paperId(), new PaperSource(item.paperId(), item.paperTitle(), item.originalFilename()));
        }
        return new EvidenceLedger(
                List.copyOf(sources.values()),
                List.copyOf(packed),
                new LedgerDiagnostics(
                        ledger.diagnostics().scannedCount(),
                        packed.size(),
                        sources.size(),
                        "CONTEXT_BUDGET"
                )
        );
    }

    private List<Map<String, Object>> buildEvidenceMessages(String userMessage, EvidenceLedger ledger) {
        StringBuilder system = new StringBuilder()
                .append("你是 CiteWeave 论文阅读助手。只能基于给定 evidence 回答。\n")
                .append("引用只能写 {{E1}}、{{E2}} 这种 token，禁止写 [1]、来源#1、paperId、chunk 或 References 列表。\n")
                .append("不要编造论文标题；如果要写论文标题，只能写 evidence 中出现的 paperTitle。\n")
                .append("Evidence 已按可信度从高到低排序，优先使用编号小的 evidence。\n")
                .append("回答使用 **结论** / **依据** / **限制**，每条依据最多 1-2 个 evidence token。\n\n");
        for (EvidenceItem item : ledger.evidence()) {
            system.append(item.evidenceId()).append("\n")
                    .append("paperTitle: ").append(item.paperTitle()).append("\n");
            if (item.pageNumber() != null) {
                system.append("page: ").append(item.pageNumber()).append("\n");
            }
            system.append("sourceKind: ").append(item.sourceKind()).append("\n")
                    .append("sectionTitle: ").append(item.sectionTitle() == null ? "" : item.sectionTitle()).append("\n")
                    .append("matchedText: ").append(shortText(item.matchedText(), 900)).append("\n\n");
        }
        return List.of(
                Map.of("role", "system", "content", system.toString()),
                Map.of("role", "user", "content", userMessage == null ? "" : userMessage)
        );
    }

    private String shortText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, maxChars) + "...";
    }

    private int estimateEvidenceTokens(EvidenceItem item) {
        if (item == null) {
            return 0;
        }
        String content = String.join("\n",
                item.evidenceId() == null ? "" : item.evidenceId(),
                "paperTitle: " + (item.paperTitle() == null ? "" : item.paperTitle()),
                "page: " + (item.pageNumber() == null ? "" : item.pageNumber()),
                "sourceKind: " + (item.sourceKind() == null ? "" : item.sourceKind()),
                "sectionTitle: " + (item.sectionTitle() == null ? "" : item.sectionTitle()),
                "matchedText: " + shortText(item.matchedText(), 900)
        );
        return estimateTextTokens(content);
    }

    private int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        int ascii = 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch <= 0x7F) {
                ascii++;
            } else if (isCjk(ch)) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, (int) Math.ceil(ascii * 0.30d + cjk * 0.95d + other * 0.55d + 12));
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    public record GeneratedAnswer(
            String rawMarkdown,
            boolean valid,
            String verifierReason,
            int promptTokens,
            int completionTokens
    ) {
    }
}
