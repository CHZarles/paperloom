package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperSourceQuote;
import com.yizhaoqi.smartpai.repository.ConversationSourceQuoteRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperSourceQuoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ProductReadingSourceQuoteTraceService {

    private static final int MAX_SOURCE_QUOTES_PER_TRACE = 20;
    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");

    private final ConversationSourceQuoteRepository conversationSourceQuoteRepository;
    private final PaperSourceQuoteRepository sourceQuoteRepository;
    private final ProductPaperHandleService handleService;
    private final PaperRepository paperRepository;

    public ProductReadingSourceQuoteTraceService(ConversationSourceQuoteRepository conversationSourceQuoteRepository,
                                                PaperSourceQuoteRepository sourceQuoteRepository,
                                                ProductPaperHandleService handleService,
                                                PaperRepository paperRepository) {
        this.conversationSourceQuoteRepository = conversationSourceQuoteRepository;
        this.sourceQuoteRepository = sourceQuoteRepository;
        this.handleService = handleService;
        this.paperRepository = paperRepository;
    }

    @Transactional(readOnly = true)
    public TraceResult traceSourceQuotes(List<String> sourceQuoteRefs, ProductToolContext context) {
        ProductToolContext safeContext = context == null
                ? new ProductToolContext(null, "", "", SourceScope.auto())
                : context;
        List<String> refs = sanitizeSourceQuoteRefs(sourceQuoteRefs);
        List<Map<String, Object>> sourceQuotes = new ArrayList<>();
        List<Map<String, Object>> traceStatus = new ArrayList<>();

        if (isBlank(safeContext.conversationId())) {
            for (String sourceQuoteRef : refs) {
                traceStatus.add(traceStatus(sourceQuoteRef, "SOURCE_QUOTE_NOT_IN_CONVERSATION"));
            }
            return new TraceResult(sourceQuotes, traceStatus);
        }

        for (String sourceQuoteRef : refs) {
            if (conversationSourceQuoteRepository
                    .findFirstByConversationIdAndSourceQuoteRef(safeContext.conversationId(), sourceQuoteRef)
                    .isEmpty()) {
                traceStatus.add(traceStatus(sourceQuoteRef, "SOURCE_QUOTE_NOT_IN_CONVERSATION"));
                continue;
            }

            Optional<PaperSourceQuote> sourceQuote = sourceQuoteRepository.findFirstBySourceQuoteRef(sourceQuoteRef);
            if (sourceQuote.isEmpty()) {
                traceStatus.add(traceStatus(sourceQuoteRef, "SOURCE_QUOTE_NOT_FOUND"));
                continue;
            }

            PaperSourceQuote quote = sourceQuote.get();
            if (!handleService.isPaperVisibleToUser(quote.getPaperId(), safeContext.userId(), safeContext.lockedScope())) {
                traceStatus.add(traceStatus(sourceQuoteRef, "SOURCE_QUOTE_UNAVAILABLE"));
                continue;
            }

            sourceQuotes.add(sourceQuoteOutput(quote));
            traceStatus.add(traceStatus(sourceQuoteRef, "OK"));
        }

        return new TraceResult(sourceQuotes, traceStatus);
    }

    private Map<String, Object> sourceQuoteOutput(PaperSourceQuote quote) {
        Map<String, Object> output = new LinkedHashMap<>();
        put(output, "sourceQuoteRef", quote.getSourceQuoteRef());
        put(output, "locationRef", quote.getLocationRef());
        put(output, "paperHandle", handleService.handleForPaperId(quote.getPaperId()));
        put(output, "paperTitle", paperTitle(quote.getPaperId()));
        put(output, "locationType", quote.getLocationType());
        put(output, "pageNumber", quote.getPageNumber());
        put(output, "pageEndNumber", quote.getPageEndNumber());
        put(output, "sectionTitle", quote.getSectionTitle());
        put(output, "contentKind", quote.getContentKind());
        put(output, "content", quote.getContent());
        return output;
    }

    private List<String> sanitizeSourceQuoteRefs(List<String> sourceQuoteRefs) {
        if (sourceQuoteRefs == null) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (String rawRef : sourceQuoteRefs) {
            String sourceQuoteRef = trim(rawRef);
            if (SOURCE_QUOTE_REF_PATTERN.matcher(sourceQuoteRef).matches()) {
                refs.add(sourceQuoteRef);
            }
            if (refs.size() >= MAX_SOURCE_QUOTES_PER_TRACE) {
                break;
            }
        }
        return List.copyOf(refs);
    }

    private String paperTitle(String paperId) {
        return paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                .map(Paper::getPaperTitle)
                .filter(title -> !isBlank(title))
                .orElse("");
    }

    private Map<String, Object> traceStatus(String sourceQuoteRef, String status) {
        Map<String, Object> output = new LinkedHashMap<>();
        put(output, "sourceQuoteRef", sourceQuoteRef);
        put(output, "status", status);
        return output;
    }

    private void put(Map<String, Object> output, String key, Object value) {
        if (value != null) {
            output.put(key, value);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record TraceResult(
            List<Map<String, Object>> sourceQuotes,
            List<Map<String, Object>> traceStatus
    ) {
        public TraceResult {
            sourceQuotes = sourceQuotes == null ? List.of() : List.copyOf(sourceQuotes);
            traceStatus = traceStatus == null ? List.of() : List.copyOf(traceStatus);
        }
    }
}
