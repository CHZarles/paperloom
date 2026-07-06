package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.ConversationSourceQuote;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingElement;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.model.PaperSection;
import com.yizhaoqi.smartpai.model.PaperSourceQuote;
import com.yizhaoqi.smartpai.repository.ConversationSourceQuoteRepository;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperSectionRepository;
import com.yizhaoqi.smartpai.repository.PaperSourceQuoteRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProductReadingLocationReadService {

    private static final String SPLIT_POLICY_VERSION = "read_locations_v1";
    private static final int MAX_LOCATIONS_PER_CALL = 10;
    private static final int MAX_QUOTES_PER_LOCATION = 8;
    private static final int MAX_QUOTE_CHARS = 1200;
    private static final int MAX_TOTAL_QUOTE_CHARS = 6000;

    private final PaperLocationRepository locationRepository;
    private final PaperReadingModelRepository modelRepository;
    private final PaperPageRepository pageRepository;
    private final PaperSectionRepository sectionRepository;
    private final PaperReadingElementRepository elementRepository;
    private final PaperSourceQuoteRepository sourceQuoteRepository;
    private final ConversationSourceQuoteRepository conversationSourceQuoteRepository;
    private final ProductPaperHandleService handleService;
    private final PaperRepository paperRepository;
    private final ObjectMapper objectMapper;

    public ProductReadingLocationReadService(PaperLocationRepository locationRepository,
                                             PaperReadingModelRepository modelRepository,
                                             PaperPageRepository pageRepository,
                                             PaperSectionRepository sectionRepository,
                                             PaperReadingElementRepository elementRepository,
                                             PaperSourceQuoteRepository sourceQuoteRepository,
                                             ConversationSourceQuoteRepository conversationSourceQuoteRepository,
                                             ProductPaperHandleService handleService,
                                             PaperRepository paperRepository,
                                             ObjectMapper objectMapper) {
        this.locationRepository = locationRepository;
        this.modelRepository = modelRepository;
        this.pageRepository = pageRepository;
        this.sectionRepository = sectionRepository;
        this.elementRepository = elementRepository;
        this.sourceQuoteRepository = sourceQuoteRepository;
        this.conversationSourceQuoteRepository = conversationSourceQuoteRepository;
        this.handleService = handleService;
        this.paperRepository = paperRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReadResult readLocations(List<String> locationRefs, ProductToolContext context) {
        ProductToolContext safeContext = context == null
                ? new ProductToolContext(null, "", "", SourceScope.auto())
                : context;
        List<String> refs = sanitizeLocationRefs(locationRefs);
        List<Map<String, Object>> sourceQuotes = new ArrayList<>();
        List<Map<String, Object>> readStatus = new ArrayList<>();
        int totalChars = 0;
        for (String locationRef : refs) {
            if (sourceQuotes.size() >= MAX_LOCATIONS_PER_CALL * MAX_QUOTES_PER_LOCATION
                    || totalChars >= MAX_TOTAL_QUOTE_CHARS) {
                readStatus.add(readStatus(locationRef, "CONTENT_TRUNCATED"));
                continue;
            }
            LocationReadAttempt attempt = resolveReadableContent(locationRef, safeContext);
            if (!"OK".equals(attempt.status())) {
                readStatus.add(readStatus(locationRef, attempt.status()));
                continue;
            }
            List<String> splits = splitContent(attempt.content());
            if (splits.isEmpty()) {
                readStatus.add(readStatus(locationRef, "EMPTY_LOCATION"));
                continue;
            }
            boolean truncated = false;
            int splitIndex = 0;
            int emittedForLocation = 0;
            for (String split : splits) {
                if (emittedForLocation >= MAX_QUOTES_PER_LOCATION || totalChars >= MAX_TOTAL_QUOTE_CHARS) {
                    truncated = true;
                    break;
                }
                String safeSplit = split;
                if (safeSplit.length() > MAX_QUOTE_CHARS) {
                    safeSplit = safeSplit.substring(0, MAX_QUOTE_CHARS).trim();
                    truncated = true;
                }
                if (safeSplit.isBlank()) {
                    splitIndex++;
                    continue;
                }
                PaperSourceQuote quote = findOrCreateSourceQuote(attempt, safeSplit, splitIndex);
                registerConversationQuote(quote, safeContext);
                sourceQuotes.add(sourceQuoteOutput(quote, attempt));
                totalChars += safeSplit.length();
                emittedForLocation++;
                splitIndex++;
            }
            readStatus.add(readStatus(locationRef, truncated ? "CONTENT_TRUNCATED" : "OK"));
        }
        return new ReadResult(List.copyOf(sourceQuotes), List.copyOf(readStatus));
    }

    private LocationReadAttempt resolveReadableContent(String locationRef, ProductToolContext context) {
        Optional<PaperLocation> location = locationRepository.findFirstByLocationRef(locationRef);
        if (location.isEmpty()) {
            return LocationReadAttempt.status(locationRef, "CURRENT_LOCATION_NOT_FOUND");
        }
        PaperLocation selected = location.get();
        Optional<PaperReadingModel> currentModel = modelRepository.findFirstByPaperIdAndIsCurrentTrue(selected.getPaperId())
                .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                .filter(model -> equalsValue(model.getModelVersion(), selected.getModelVersion()));
        if (currentModel.isEmpty()) {
            return LocationReadAttempt.status(locationRef, "CURRENT_LOCATION_NOT_FOUND");
        }
        if (!handleService.isPaperVisibleToUser(selected.getPaperId(), context.userId(), context.lockedScope())) {
            return LocationReadAttempt.status(locationRef, "LOCATION_UNAVAILABLE");
        }
        ReadableContent readableContent = contentForLocation(selected);
        if (!"OK".equals(readableContent.status())) {
            return LocationReadAttempt.status(locationRef, readableContent.status());
        }
        return new LocationReadAttempt(
                "OK",
                selected,
                readableContent.content(),
                contentKind(selected.getLocationType()),
                paperTitle(selected.getPaperId()),
                handleService.handleForPaperId(selected.getPaperId())
        );
    }

    private ReadableContent contentForLocation(PaperLocation location) {
        PaperLocationType type = location.getLocationType();
        if (type == PaperLocationType.PAGE) {
            Optional<PaperPage> page = pageRepository.findFirstByPaperIdAndModelVersionAndPageNumber(
                    location.getPaperId(),
                    location.getModelVersion(),
                    location.getPageNumber()
            );
            if (page.isEmpty()) {
                return ReadableContent.status("CURRENT_LOCATION_NOT_FOUND");
            }
            if (PaperPage.TEXT_STATUS_TEXTLESS.equals(page.get().getTextStatus())
                    || isBlank(page.get().getPageText())) {
                return ReadableContent.status("EMPTY_LOCATION");
            }
            return ReadableContent.content(page.get().getPageText());
        }
        if (type == PaperLocationType.SECTION) {
            Optional<PaperSection> section = sectionRepository.findFirstByPaperIdAndModelVersionAndSectionId(
                    location.getPaperId(),
                    location.getModelVersion(),
                    trim(location.getSourceObjectId())
            );
            if (section.isEmpty()) {
                return ReadableContent.status("CURRENT_LOCATION_NOT_FOUND");
            }
            if (isBlank(section.get().getSectionText())) {
                return ReadableContent.status("EMPTY_LOCATION");
            }
            return ReadableContent.content(section.get().getSectionText());
        }
        if (type == PaperLocationType.TABLE || type == PaperLocationType.FIGURE) {
            Optional<PaperReadingElement> element = elementRepository.findFirstByPaperIdAndModelVersionAndReadingElementId(
                    location.getPaperId(),
                    location.getModelVersion(),
                    trim(location.getSourceObjectId())
            );
            if (element.isEmpty()) {
                return ReadableContent.status("CURRENT_LOCATION_NOT_FOUND");
            }
            String text = type == PaperLocationType.TABLE
                    ? firstNonBlank(element.get().getBodyText(), structuredPayloadText(element.get().getStructuredPayloadJson()))
                    : firstNonBlank(element.get().getCaptionText(), element.get().getBodyText());
            if (isBlank(text)) {
                return ReadableContent.status("EMPTY_LOCATION");
            }
            return ReadableContent.content(text);
        }
        return ReadableContent.status("UNREADABLE_LOCATION");
    }

    private PaperSourceQuote findOrCreateSourceQuote(LocationReadAttempt attempt, String content, int splitIndex) {
        PaperLocation location = attempt.location();
        String contentHash = sha256(content);
        Optional<PaperSourceQuote> existing =
                sourceQuoteRepository.findFirstByPaperIdAndModelVersionAndLocationRefAndSplitPolicyVersionAndSplitIndexAndContentHash(
                        location.getPaperId(),
                        location.getModelVersion(),
                        location.getLocationRef(),
                        SPLIT_POLICY_VERSION,
                        splitIndex,
                        contentHash
                );
        if (existing.isPresent()) {
            return existing.get();
        }
        PaperSourceQuote quote = new PaperSourceQuote();
        quote.setSourceQuoteRef(newSourceQuoteRef());
        quote.setPaperId(location.getPaperId());
        quote.setModelVersion(location.getModelVersion());
        quote.setLocationRef(location.getLocationRef());
        quote.setLocationType(location.getLocationType().name());
        quote.setPageNumber(location.getPageNumber());
        quote.setPageEndNumber(location.getPageEndNumber());
        quote.setSectionTitle(trim(location.getSectionTitle()));
        quote.setContentKind(attempt.contentKind());
        quote.setContent(content);
        quote.setContentHash(contentHash);
        quote.setSplitPolicyVersion(SPLIT_POLICY_VERSION);
        quote.setSplitIndex(splitIndex);
        quote.setSourceSpanJson(isBlank(location.getSourceSpanJson()) ? "{}" : location.getSourceSpanJson());
        try {
            return sourceQuoteRepository.saveAndFlush(quote);
        } catch (DataIntegrityViolationException exception) {
            return sourceQuoteRepository.findFirstByPaperIdAndModelVersionAndLocationRefAndSplitPolicyVersionAndSplitIndexAndContentHash(
                    location.getPaperId(),
                    location.getModelVersion(),
                    location.getLocationRef(),
                    SPLIT_POLICY_VERSION,
                    splitIndex,
                    contentHash
            ).orElseThrow(() -> exception);
        }
    }

    private void registerConversationQuote(PaperSourceQuote quote, ProductToolContext context) {
        if (quote == null || isBlank(quote.getSourceQuoteRef()) || isBlank(context.conversationId())) {
            return;
        }
        if (conversationSourceQuoteRepository
                .findFirstByConversationIdAndSourceQuoteRef(context.conversationId(), quote.getSourceQuoteRef())
                .isPresent()) {
            return;
        }
        ConversationSourceQuote registryRow = new ConversationSourceQuote();
        registryRow.setConversationId(context.conversationId());
        registryRow.setSourceQuoteRef(quote.getSourceQuoteRef());
        registryRow.setFirstSeenTurnId(isBlank(context.generationId()) ? "unknown" : context.generationId());
        registryRow.setUserId(context.userId() == null ? "" : String.valueOf(context.userId()));
        try {
            conversationSourceQuoteRepository.save(registryRow);
        } catch (DataIntegrityViolationException ignored) {
            conversationSourceQuoteRepository
                    .findFirstByConversationIdAndSourceQuoteRef(context.conversationId(), quote.getSourceQuoteRef());
        }
    }

    private Map<String, Object> sourceQuoteOutput(PaperSourceQuote quote, LocationReadAttempt attempt) {
        Map<String, Object> output = new LinkedHashMap<>();
        put(output, "sourceQuoteRef", quote.getSourceQuoteRef());
        put(output, "locationRef", quote.getLocationRef());
        put(output, "paperHandle", attempt.paperHandle());
        put(output, "paperTitle", attempt.paperTitle());
        put(output, "locationType", quote.getLocationType());
        put(output, "pageNumber", quote.getPageNumber());
        put(output, "pageEndNumber", quote.getPageEndNumber());
        put(output, "sectionTitle", quote.getSectionTitle());
        put(output, "contentKind", quote.getContentKind());
        put(output, "content", quote.getContent());
        return output;
    }

    private List<String> splitContent(String content) {
        String normalized = trim(content).replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] paragraphs = normalized.split("\\n\\s*\\n");
        List<String> splits = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String text = trim(paragraph);
            if (text.isBlank()) {
                continue;
            }
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(text.length(), start + MAX_QUOTE_CHARS);
                splits.add(text.substring(start, end).trim());
                start = end;
            }
        }
        return splits;
    }

    private List<String> sanitizeLocationRefs(List<String> locationRefs) {
        if (locationRefs == null) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (String locationRef : locationRefs) {
            String ref = trim(locationRef);
            if (!ref.isBlank()) {
                refs.add(ref);
            }
            if (refs.size() >= MAX_LOCATIONS_PER_CALL) {
                break;
            }
        }
        return List.copyOf(refs);
    }

    private String structuredPayloadText(String structuredPayloadJson) {
        if (isBlank(structuredPayloadJson)) {
            return "";
        }
        try {
            List<String> values = new ArrayList<>();
            collectText(objectMapper.readTree(structuredPayloadJson), values);
            return String.join("\n", values).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void collectText(JsonNode node, List<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String value = trim(node.asText());
            if (!value.isBlank()) {
                values.add(value);
            }
            return;
        }
        if (node.isArray() || node.isObject()) {
            node.forEach(child -> collectText(child, values));
        }
    }

    private String paperTitle(String paperId) {
        return paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                .map(Paper::getPaperTitle)
                .filter(title -> !isBlank(title))
                .orElse("");
    }

    private String contentKind(PaperLocationType locationType) {
        if (locationType == PaperLocationType.TABLE) {
            return "TABLE";
        }
        if (locationType == PaperLocationType.FIGURE) {
            return "FIGURE_CAPTION";
        }
        return "TEXT";
    }

    private String newSourceQuoteRef() {
        return "source_quote_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(trim(value).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Map<String, Object> readStatus(String locationRef, String status) {
        Map<String, Object> output = new LinkedHashMap<>();
        put(output, "locationRef", locationRef);
        put(output, "status", status);
        return output;
    }

    private void put(Map<String, Object> output, String key, Object value) {
        if (value != null) {
            output.put(key, value);
        }
    }

    private boolean equalsValue(String left, String right) {
        return trim(left).equals(trim(right));
    }

    private String firstNonBlank(String first, String second) {
        return isBlank(first) ? trim(second) : trim(first);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record ReadResult(
            List<Map<String, Object>> sourceQuotes,
            List<Map<String, Object>> readStatus
    ) {
        public ReadResult {
            sourceQuotes = sourceQuotes == null ? List.of() : List.copyOf(sourceQuotes);
            readStatus = readStatus == null ? List.of() : List.copyOf(readStatus);
        }
    }

    private record ReadableContent(String status, String content) {
        static ReadableContent status(String status) {
            return new ReadableContent(status, "");
        }

        static ReadableContent content(String content) {
            return new ReadableContent("OK", content == null ? "" : content.trim());
        }
    }

    private record LocationReadAttempt(
            String status,
            PaperLocation location,
            String content,
            String contentKind,
            String paperTitle,
            String paperHandle
    ) {
        static LocationReadAttempt status(String locationRef, String status) {
            PaperLocation placeholder = new PaperLocation();
            placeholder.setLocationRef(locationRef);
            return new LocationReadAttempt(status, placeholder, "", "", "", "");
        }
    }
}
