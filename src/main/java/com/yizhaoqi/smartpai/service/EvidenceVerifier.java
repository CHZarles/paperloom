package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class EvidenceVerifier {

    private static final Pattern EVIDENCE_TOKEN_PATTERN = Pattern.compile("\\{\\{E(\\d+)}}");
    private static final Pattern BRACKET_CITATION_PATTERN = Pattern.compile("\\[\\d+]");
    private static final Pattern LEGACY_SOURCE_PATTERN = Pattern.compile("(?:来源|source)\\s*#\\s*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_TITLE_PATTERN = Pattern.compile("《([^》]+)》");
    private static final Pattern STRONG_COMPARATIVE_CLAIM_PATTERN = Pattern.compile(
            "(优于|超越|显著|证明|更强|更高|outperform|superior|significantly)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COMPARATIVE_EVIDENCE_SIGNAL_PATTERN = Pattern.compile(
            "(对比|比较|实验|评估|准确率|指标|表\\s*\\d*|"
                    + "compare|comparison|versus|vs\\.?|experiment|evaluation|accuracy|benchmark|table|ablation|"
                    + "outperform|superior|significant|significantly)",
            Pattern.CASE_INSENSITIVE
    );

    public VerificationResult verify(String rawAnswer, EvidenceLedger ledger) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return VerificationResult.invalid("empty_answer");
        }
        if (BRACKET_CITATION_PATTERN.matcher(rawAnswer).find()) {
            return VerificationResult.invalid("naked_citation");
        }
        if (LEGACY_SOURCE_PATTERN.matcher(rawAnswer).find()) {
            return VerificationResult.invalid("legacy_citation");
        }
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        if (mentionsUnknownQuotedTitle(rawAnswer, safeLedger)) {
            return VerificationResult.invalid("unknown_paper_title");
        }
        Map<String, EvidenceItem> byId = safeLedger.evidence().stream()
                .collect(Collectors.toMap(EvidenceItem::evidenceId, Function.identity(), (left, ignored) -> left));

        Matcher matcher = EVIDENCE_TOKEN_PATTERN.matcher(rawAnswer);
        Set<String> usedEvidenceIds = new LinkedHashSet<>();
        while (matcher.find()) {
            String evidenceId = "E" + matcher.group(1);
            EvidenceItem item = byId.get(evidenceId);
            if (item == null || !EvidenceQuality.isUsable(item.matchedText())) {
                return VerificationResult.invalid("unknown_or_unusable_evidence:" + evidenceId);
            }
            usedEvidenceIds.add(evidenceId);
        }
        if (usedEvidenceIds.isEmpty()) {
            return VerificationResult.invalid("missing_evidence_token");
        }
        if (STRONG_COMPARATIVE_CLAIM_PATTERN.matcher(rawAnswer).find()
                && usedEvidenceIds.stream().noneMatch(id -> hasComparativeSignal(byId.get(id)))) {
        return VerificationResult.invalid("unsupported_comparative_claim");
        }
        return new VerificationResult(true, "");
    }

    private boolean mentionsUnknownQuotedTitle(String rawAnswer, EvidenceLedger ledger) {
        Set<String> allowedTitles = ledger.sourceSet().stream()
                .map(PaperSource::paperTitle)
                .filter(title -> title != null && !title.isBlank())
                .collect(Collectors.toSet());
        Matcher matcher = CHINESE_TITLE_PATTERN.matcher(rawAnswer);
        while (matcher.find()) {
            if (!allowedTitles.contains(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasComparativeSignal(EvidenceItem item) {
        if (item == null) {
            return false;
        }
        String text = String.join(" ",
                item.matchedText() == null ? "" : item.matchedText(),
                item.sourceKind() == null ? "" : item.sourceKind(),
                item.sectionTitle() == null ? "" : item.sectionTitle()
        );
        return COMPARATIVE_EVIDENCE_SIGNAL_PATTERN.matcher(text).find();
    }

    public record VerificationResult(boolean valid, String reason) {
        static VerificationResult invalid(String reason) {
            return new VerificationResult(false, reason);
        }
    }
}
