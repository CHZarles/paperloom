package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperSourceQuote;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperSourceQuoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaperSourceQuoteReadService {

    private static final String PASSAGE_PAGE_POLICY = "passage-page-v1";
    private static final String LOCATION_POLICY = "location-page-v1";

    private final PaperSourceQuoteRepository quoteRepository;
    private final PaperLocationRepository locationRepository;
    private final PaperReadingElementRepository elementRepository;
    private final ObjectMapper objectMapper;

    public PaperSourceQuoteReadService(PaperSourceQuoteRepository quoteRepository,
                                       PaperLocationRepository locationRepository,
                                       PaperReadingElementRepository elementRepository,
                                       ObjectMapper objectMapper) {
        this.quoteRepository = quoteRepository;
        this.locationRepository = locationRepository;
        this.elementRepository = elementRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReadResult createQuotes(CanonicalReadingLocationService.ReadBatch readBatch) {
        List<CanonicalReadingLocationService.CanonicalLocation> locations = readBatch.items();
        Map<String, PaperLocation> sourceLocations = locationRepository.findByLocationRefIn(
                        locations.stream().map(CanonicalReadingLocationService.CanonicalLocation::locationRef).toList())
                .stream()
                .collect(Collectors.toMap(PaperLocation::getLocationRef, Function.identity(), (left, right) -> left));
        List<ReadItem> items = new ArrayList<>();
        for (CanonicalReadingLocationService.CanonicalLocation location : locations) {
            PaperLocation source = sourceLocations.get(location.locationRef());
            if (source == null) {
                continue;
            }
            List<PaperSourceQuote> quotes = source.getLocationType() == PaperLocationType.PASSAGE
                    ? passageQuotes(location, source)
                    : source.getLocationType() == PaperLocationType.SECTION
                    ? sectionQuotes(location, source)
                    : List.of(locationQuote(location, source));
            items.add(new ReadItem(location, quotes.stream().map(PaperSourceQuoteReadService::view).toList()));
        }
        return new ReadResult(items, readBatch.missingLocationRefs());
    }

    private List<PaperSourceQuote> passageQuotes(CanonicalReadingLocationService.CanonicalLocation location,
                                                  PaperLocation source) {
        JsonNode root;
        try {
            root = objectMapper.readTree(source.getSourceSpanJson());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("PASSAGE_SOURCE_SPAN_INVALID", error);
        }
        if (root == null || !root.path("spans").isArray()) {
            throw new IllegalArgumentException("PASSAGE_SOURCE_SPAN_INVALID");
        }
        Map<Integer, PageDraft> pages = new LinkedHashMap<>();
        int fallbackOffset = 0;
        int sourceIndex = 0;
        for (JsonNode span : root.path("spans")) {
            int page = span.path("pageNumber").asInt(0);
            int from = span.has("content_char_from") ? span.path("content_char_from").asInt(-1) : fallbackOffset;
            int to = span.has("content_char_to")
                    ? span.path("content_char_to").asInt(-1)
                    : fallbackOffset + Math.max(0, span.path("char_to").asInt() - span.path("char_from").asInt());
            if (page <= 0 || from < 0 || to < from || to > location.spanText().length()) {
                throw new IllegalArgumentException("PASSAGE_SOURCE_SPAN_INVALID");
            }
            fallbackOffset = to + 2;
            PageDraft draft = pages.computeIfAbsent(page, ignored -> new PageDraft(page));
            draft.parts().add(location.spanText().substring(from, to));
            draft.spans().add(span);
            draft.readingOrderFrom = draft.readingOrderFrom == null ? span.path("readingOrder").asInt() : draft.readingOrderFrom;
            draft.readingOrderTo = span.path("readingOrder").asInt();
            sourceIndex++;
        }
        if (pages.isEmpty() || sourceIndex == 0) {
            throw new IllegalArgumentException("PASSAGE_SOURCE_SPAN_INVALID");
        }
        List<PaperSourceQuote> quotes = new ArrayList<>();
        int splitIndex = 0;
        for (PageDraft page : pages.values()) {
            String content = String.join("\n\n", page.parts());
            String spanJson = passagePageSpan(source, page);
            quotes.add(getOrCreate(source, content, spanJson, page.pageNumber(), splitIndex++, PASSAGE_PAGE_POLICY));
        }
        return quotes;
    }

    private PaperSourceQuote locationQuote(CanonicalReadingLocationService.CanonicalLocation location,
                                           PaperLocation source) {
        if (source.getPageEndNumber() != null && !source.getPageEndNumber().equals(source.getPageNumber())) {
            throw new IllegalArgumentException("MULTI_PAGE_LOCATION_REQUIRES_PAGE_QUOTE_SPLIT");
        }
        return getOrCreate(source, location.spanText(), source.getSourceSpanJson(), source.getPageNumber(), 0, LOCATION_POLICY);
    }

    private List<PaperSourceQuote> sectionQuotes(CanonicalReadingLocationService.CanonicalLocation location,
                                                  PaperLocation source) {
        if (source.getPageEndNumber() == null || source.getPageEndNumber().equals(source.getPageNumber())) {
            return List.of(locationQuote(location, source));
        }
        JsonNode sourceSpan;
        try {
            sourceSpan = objectMapper.readTree(source.getSourceSpanJson());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("SECTION_SOURCE_SPAN_INVALID", error);
        }
        List<String> parserIds = new ArrayList<>();
        sourceSpan.path("elementIds").forEach(node -> {
            String id = node.asText("").trim();
            if (!id.isBlank()) {
                parserIds.add(id);
            }
        });
        if (parserIds.isEmpty()) {
            throw new IllegalArgumentException("SECTION_SOURCE_SPAN_INVALID");
        }
        Map<Integer, PageDraft> pages = new LinkedHashMap<>();
        int cursor = 0;
        for (PaperReadingElement element : elementRepository
                .findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(
                        source.getPaperId(), source.getModelVersion())) {
            if (!parserIds.contains(element.getParserElementId()) || element.getPageNumber() == null) {
                continue;
            }
            String text = firstNonBlank(element.getBodyText(), element.getSearchableText());
            int start = location.spanText().indexOf(text, cursor);
            if (text.isBlank() || start < 0) {
                throw new IllegalArgumentException("SECTION_SOURCE_SPAN_INVALID");
            }
            cursor = start + text.length();
            PageDraft page = pages.computeIfAbsent(element.getPageNumber(), PageDraft::new);
            page.parts().add(text);
            page.readingOrderFrom = page.readingOrderFrom == null ? element.getReadingOrder() : page.readingOrderFrom;
            page.readingOrderTo = element.getReadingOrder();
            try {
                page.spans().add(objectMapper.readTree(element.getSourceSpanJson()));
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("SECTION_SOURCE_SPAN_INVALID", error);
            }
        }
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("SECTION_SOURCE_SPAN_INVALID");
        }
        List<PaperSourceQuote> quotes = new ArrayList<>();
        int splitIndex = 0;
        for (PageDraft page : pages.values()) {
            quotes.add(getOrCreate(source, String.join("\n\n", page.parts()), sectionPageSpan(source, page),
                    page.pageNumber(), splitIndex++, LOCATION_POLICY));
        }
        return quotes;
    }

    private PaperSourceQuote getOrCreate(PaperLocation source,
                                         String content,
                                         String sourceSpanJson,
                                         Integer pageNumber,
                                         int splitIndex,
                                         String policy) {
        String hash = sha256(content);
        return quoteRepository.findFirstByPaperIdAndModelVersionAndLocationRefAndSplitPolicyVersionAndSplitIndexAndContentHash(
                        source.getPaperId(), source.getModelVersion(), source.getLocationRef(), policy, splitIndex, hash)
                .orElseGet(() -> {
                    PaperSourceQuote quote = new PaperSourceQuote();
                    quote.setSourceQuoteRef("source_quote_" + sha256(String.join("|", source.getPaperId(),
                            source.getModelVersion(), source.getLocationRef(), policy, Integer.toString(splitIndex), hash)));
                    quote.setPaperId(source.getPaperId());
                    quote.setModelVersion(source.getModelVersion());
                    quote.setLocationRef(source.getLocationRef());
                    quote.setLocationType(source.getLocationType().name());
                    quote.setPageNumber(pageNumber);
                    quote.setPageEndNumber(pageNumber);
                    quote.setSectionTitle(source.getSectionTitle());
                    quote.setContentKind(source.getContentKind());
                    quote.setContent(content);
                    quote.setContentHash(hash);
                    quote.setSplitPolicyVersion(policy);
                    quote.setSplitIndex(splitIndex);
                    quote.setSourceSpanJson(sourceSpanJson);
                    return quoteRepository.save(quote);
                });
    }

    private String passagePageSpan(PaperLocation source, PageDraft page) {
        return pageSpan(source, page, PaperLocationType.PASSAGE.name());
    }

    private String sectionPageSpan(PaperLocation source, PageDraft page) {
        return pageSpan(source, page, PaperLocationType.SECTION.name());
    }

    private String pageSpan(PaperLocation source, PageDraft page, String locationType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("locationType", locationType);
        result.put("sourceObjectId", source.getLocationRef());
        result.put("pageNumber", page.pageNumber());
        result.put("pageNumberFrom", page.pageNumber());
        result.put("pageNumberTo", page.pageNumber());
        result.put("readingOrderFrom", page.readingOrderFrom);
        result.put("readingOrderTo", page.readingOrderTo);
        result.put("spans", page.spans());
        List<JsonNode> boxes = page.spans().stream()
                .map(span -> span.path("elementSourceSpan").isMissingNode()
                        ? span.path("bbox")
                        : span.path("elementSourceSpan").path("bbox"))
                .filter(box -> !box.isMissingNode() && !box.isNull())
                .flatMap(box -> box.isArray()
                        ? java.util.stream.StreamSupport.stream(box.spliterator(), false)
                        : java.util.stream.Stream.of(box))
                .toList();
        result.put("bbox", boxes);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("PASSAGE_SOURCE_SPAN_SERIALIZATION_FAILED", error);
        }
    }

    private static SourceQuote view(PaperSourceQuote quote) {
        return new SourceQuote(quote.getSourceQuoteRef(), quote.getPaperId(), quote.getModelVersion(),
                quote.getLocationRef(), quote.getLocationType(), quote.getPageNumber(), quote.getPageEndNumber(),
                quote.getSectionTitle(), quote.getContentKind(), quote.getContent(), quote.getSourceSpanJson());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                hex.append(String.format("%02x", valueByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public record ReadResult(List<ReadItem> items, List<String> missingLocationRefs) {
        public ReadResult {
            items = items == null ? List.of() : List.copyOf(items);
            missingLocationRefs = missingLocationRefs == null ? List.of() : List.copyOf(missingLocationRefs);
        }
    }

    public record ReadItem(CanonicalReadingLocationService.CanonicalLocation location, List<SourceQuote> sourceQuotes) {
        public ReadItem {
            sourceQuotes = sourceQuotes == null ? List.of() : List.copyOf(sourceQuotes);
        }
    }

    public record SourceQuote(String sourceQuoteRef, String paperId, String modelVersion, String locationRef,
                              String locationType, Integer pageNumber, Integer pageEndNumber, String sectionTitle,
                              String contentKind, String content, String sourceSpanJson) {
    }

    private static final class PageDraft {
        private final int pageNumber;
        private final List<String> parts = new ArrayList<>();
        private final List<JsonNode> spans = new ArrayList<>();
        private Integer readingOrderFrom;
        private Integer readingOrderTo;

        private PageDraft(int pageNumber) {
            this.pageNumber = pageNumber;
        }

        private int pageNumber() {
            return pageNumber;
        }

        private List<String> parts() {
            return parts;
        }

        private List<JsonNode> spans() {
            return spans;
        }
    }
}
