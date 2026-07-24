package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperSourceQuote;
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

    private final ProductReadingSourceQuoteResolver sourceQuoteResolver;
    private final ProductPaperHandleService handleService;

    public ProductReadingSourceQuoteTraceService(ProductReadingSourceQuoteResolver sourceQuoteResolver,
                                                ProductPaperHandleService handleService) {
        this.handleService = handleService;
        this.sourceQuoteResolver = sourceQuoteResolver;
    }

    @Transactional(readOnly = true)
    public TraceResult traceSourceQuotes(List<String> sourceQuoteRefs, ProductToolContext context) {
        ProductToolContext safeContext = context == null
                ? new ProductToolContext(null, "", "", SourceScope.auto())
                : context;
        List<String> refs = sanitizeSourceQuoteRefs(sourceQuoteRefs);
        List<Map<String, Object>> sourceQuotes = new ArrayList<>();
        List<Map<String, Object>> traceStatus = new ArrayList<>();

        for (String sourceQuoteRef : refs) {
            ProductReadingSourceQuoteResolver.Resolution resolution =
                    sourceQuoteResolver.resolveRegisteredCurrentQuote(safeContext.conversationId(), sourceQuoteRef);
            if (!resolution.ok()) {
                traceStatus.add(traceStatus(sourceQuoteRef, resolution.status()));
                continue;
            }
            PaperSourceQuote quote = resolution.sourceQuote().orElseThrow();
            if (!handleService.isPaperVisibleToUser(quote.getPaperId(), safeContext.userId(), safeContext.lockedScope())) {
                traceStatus.add(traceStatus(sourceQuoteRef, ProductReadingSourceQuoteResolver.STATUS_UNAVAILABLE));
                continue;
            }

            sourceQuotes.add(sourceQuoteOutput(quote, resolution.paper()));
            traceStatus.add(traceStatus(sourceQuoteRef, ProductReadingSourceQuoteResolver.STATUS_OK));
        }

        return new TraceResult(sourceQuotes, traceStatus);
    }

    private Map<String, Object> sourceQuoteOutput(PaperSourceQuote quote, Optional<Paper> paper) {
        Map<String, Object> output = new LinkedHashMap<>();
        put(output, "sourceQuoteRef", quote.getSourceQuoteRef());
        put(output, "paperId", quote.getPaperId());
        put(output, "paperVersion", quote.getModelVersion());
        put(output, "locationRef", quote.getLocationRef());
        put(output, "paperHandle", handleService.handleForPaperId(quote.getPaperId()));
        paper.map(Paper::getPaperTitle)
                .filter(title -> !isBlank(title))
                .ifPresent(title -> put(output, "paperTitle", title));
        put(output, "locationType", quote.getLocationType());
        put(output, "pageNumber", quote.getPageNumber());
        put(output, "pageEndNumber", quote.getPageEndNumber());
        put(output, "sectionTitle", quote.getSectionTitle());
        put(output, "contentKind", quote.getContentKind());
        put(output, "content", quote.getContent());
        put(output, "sourceSpanJson", quote.getSourceSpanJson());
        put(output, "visualRegions", SourceQuoteVisualRegionContract.visualRegions(quote));
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
