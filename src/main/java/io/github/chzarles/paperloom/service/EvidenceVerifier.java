package io.github.chzarles.paperloom.service;

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

    public record VerificationResult(boolean valid, String reason) {
        static VerificationResult invalid(String reason) {
            return new VerificationResult(false, reason);
        }
    }
}
