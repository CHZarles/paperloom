package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPassage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperSection;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class StructuralPassageBuilder {

    static final int MINIMUM_ESTIMATED_TOKENS = 120;
    static final int TARGET_ESTIMATED_TOKENS = 450;
    static final int SOFT_MAXIMUM_ESTIMATED_TOKENS = 650;
    static final int HARD_MAXIMUM_ESTIMATED_TOKENS = 800;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PassageBuildResult build(String paperId,
                                    String modelVersion,
                                    List<PaperSection> sections,
                                    List<PaperLocation> locations,
                                    List<PaperReadingElement> elements,
                                    String userId) {
        List<SectionContext> sectionContexts = sectionContexts(sections, locations);
        List<PaperReadingElement> ordered = safe(elements).stream()
                .filter(Objects::nonNull)
                .filter(this::hasReadableTextOrBarrier)
                .sorted(Comparator
                        .comparing(PaperReadingElement::getReadingOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PaperReadingElement::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        List<PassageDraft> drafts = new ArrayList<>();
        PassageDraft current = null;
        int segment = 0;
        Integer previousReadingOrder = null;

        for (PaperReadingElement element : ordered) {
            if (isBarrier(element)) {
                current = flush(drafts, current);
                segment++;
                continue;
            }
            if (!isEligibleText(element)) {
                continue;
            }
            requireLocation(element);
            if (previousReadingOrder != null && element.getReadingOrder() < previousReadingOrder) {
                throw new IllegalArgumentException("PASSAGE_READING_ORDER_REGRESSION");
            }
            previousReadingOrder = element.getReadingOrder();

            SectionContext section = sectionFor(element, sectionContexts);
            if (current != null && !sameSection(current.section(), section)) {
                current = flush(drafts, current);
                segment++;
            }
            for (Fragment fragment : fragmentsFor(element)) {
                if (current != null && (!current.canFollow(fragment)
                        || current.estimatedTokenCount() >= TARGET_ESTIMATED_TOKENS
                        || current.estimatedTokenCount() + fragment.estimatedTokenCount() > SOFT_MAXIMUM_ESTIMATED_TOKENS)) {
                    current = flush(drafts, current);
                }
                if (current == null) {
                    current = new PassageDraft(section, segment);
                }
                current.add(fragment);
            }
        }
        flush(drafts, current);

        List<PassageDraft> normalized = mergeShortTails(drafts);
        List<PaperPassage> passages = new ArrayList<>();
        List<PaperLocation> passageLocations = new ArrayList<>();
        Map<String, Integer> sectionOrdinals = new LinkedHashMap<>();
        int documentOrdinal = 1;
        for (PassageDraft draft : normalized) {
            String content = draft.contentText();
            String passageRef = passageRef(paperId, modelVersion, draft);
            SectionContext section = draft.section();
            Integer sectionOrdinal = section.sectionId() == null
                    ? null
                    : sectionOrdinals.merge(section.sectionId(), 1, Integer::sum);
            PaperPassage passage = new PaperPassage();
            passage.setPassageRef(passageRef);
            passage.setPaperId(paperId);
            passage.setModelVersion(modelVersion);
            passage.setParentSectionId(section.sectionId());
            passage.setParentSectionRef(section.sectionRef());
            passage.setSectionTitle(section.sectionTitle());
            passage.setPageNumberFrom(draft.pageNumberFrom());
            passage.setPageNumberTo(draft.pageNumberTo());
            passage.setReadingOrderFrom(draft.readingOrderFrom());
            passage.setReadingOrderTo(draft.readingOrderTo());
            passage.setDocumentOrdinal(documentOrdinal++);
            passage.setSectionOrdinal(sectionOrdinal);
            passage.setContentText(content);
            passage.setIndexText(indexText(section.sectionTitle(), content));
            passage.setContentHash(sha256(content));
            passage.setEstimatedTokenCount(estimateTokens(content));
            passage.setSourceSpanJson(sourceSpanJson(passageRef, draft));
            passages.add(passage);

            PaperLocation location = new PaperLocation();
            location.setLocationRef(passageRef);
            location.setPaperId(paperId);
            location.setModelVersion(modelVersion);
            location.setLocationType(PaperLocationType.PASSAGE);
            location.setPageNumber(draft.pageNumberFrom());
            location.setPageEndNumber(draft.pageNumberTo());
            location.setSectionTitle(section.sectionTitle());
            location.setSourceObjectId(passageRef);
            location.setDisplayOrder(passage.getDocumentOrdinal());
            location.setSourceSpanJson(passage.getSourceSpanJson());
            location.setContentKind("PASSAGE_TEXT");
            location.setUserId(userId);
            passageLocations.add(location);
        }
        return new PassageBuildResult(passages, passageLocations);
    }

    private List<SectionContext> sectionContexts(List<PaperSection> sections, List<PaperLocation> locations) {
        Map<String, String> refsBySectionId = safe(locations).stream()
                .filter(location -> location.getLocationType() == PaperLocationType.SECTION)
                .filter(location -> !blank(location.getSourceObjectId()))
                .collect(java.util.stream.Collectors.toMap(
                        PaperLocation::getSourceObjectId,
                        PaperLocation::getLocationRef,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return safe(sections).stream()
                .filter(Objects::nonNull)
                .filter(section -> !blank(section.getSectionId()))
                .filter(section -> section.getReadingOrderFrom() != null && section.getReadingOrderTo() != null)
                .sorted(Comparator.comparing(PaperSection::getReadingOrderFrom)
                        .thenComparing(PaperSection::getDisplayOrder, Comparator.nullsLast(Integer::compareTo)))
                .map(section -> new SectionContext(
                        section.getSectionId(),
                        refsBySectionId.get(section.getSectionId()),
                        section.getSectionTitle(),
                        section.getReadingOrderFrom(),
                        section.getReadingOrderTo()
                ))
                .toList();
    }

    private SectionContext sectionFor(PaperReadingElement element, List<SectionContext> sections) {
        return sections.stream()
                .filter(section -> element.getReadingOrder() >= section.readingOrderFrom())
                .filter(section -> element.getReadingOrder() <= section.readingOrderTo())
                .findFirst()
                .orElse(SectionContext.UNSECTIONED);
    }

    private boolean hasReadableTextOrBarrier(PaperReadingElement element) {
        return isBarrier(element) || isEligibleText(element);
    }

    private boolean isBarrier(PaperReadingElement element) {
        return "TABLE".equals(element.getElementType())
                || "IMAGE".equals(element.getElementType())
                || "CHART".equals(element.getElementType());
    }

    private boolean isEligibleText(PaperReadingElement element) {
        if (isBarrier(element)
                || "HEADER".equals(element.getElementType())
                || "FOOTER".equals(element.getElementType())
                || ("TABLE_CAPTION".equals(element.getAttachmentRole())
                && "ATTACHED".equals(element.getAssociationStatus()))) {
            return false;
        }
        return !blank(contentFor(element));
    }

    private String contentFor(PaperReadingElement element) {
        return firstNonBlank(element.getBodyText(), element.getSearchableText());
    }

    private void requireLocation(PaperReadingElement element) {
        if (element.getPageNumber() == null || element.getPageNumber() <= 0) {
            throw new IllegalArgumentException("PASSAGE_ELEMENT_MISSING_PAGE");
        }
        if (element.getReadingOrder() == null) {
            throw new IllegalArgumentException("PASSAGE_ELEMENT_MISSING_READING_ORDER");
        }
    }

    private List<Fragment> fragmentsFor(PaperReadingElement element) {
        String content = contentFor(element);
        List<Fragment> fragments = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = hardLimit(content, start);
            String part = content.substring(start, end);
            if (!part.isBlank()) {
                fragments.add(new Fragment(element, part, start, end));
            }
            start = end;
        }
        return fragments;
    }

    private int hardLimit(String text, int start) {
        int end = start;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            if (estimateTokens(text.substring(start, next)) > HARD_MAXIMUM_ESTIMATED_TOKENS) {
                break;
            }
            end = next;
        }
        if (end == text.length()) {
            return end;
        }
        int preferred = preferredBoundary(text, start, end);
        return preferred > start ? preferred : end;
    }

    private int preferredBoundary(String text, int start, int end) {
        for (int index = end; index > start; index--) {
            char value = text.charAt(index - 1);
            if (Character.isWhitespace(value) || ".,;:!?。；：！？".indexOf(value) >= 0) {
                return index;
            }
        }
        return end;
    }

    private PassageDraft flush(List<PassageDraft> drafts, PassageDraft current) {
        if (current != null && !current.fragments().isEmpty()) {
            drafts.add(current);
        }
        return null;
    }

    private List<PassageDraft> mergeShortTails(List<PassageDraft> drafts) {
        List<PassageDraft> merged = new ArrayList<>();
        for (PassageDraft draft : drafts) {
            PassageDraft previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (previous != null
                    && previous.segment() == draft.segment()
                    && sameSection(previous.section(), draft.section())
                    && draft.estimatedTokenCount() < MINIMUM_ESTIMATED_TOKENS
                    && previous.estimatedTokenCount() + draft.estimatedTokenCount() <= HARD_MAXIMUM_ESTIMATED_TOKENS
                    && previous.canFollow(draft.fragments().get(0))) {
                previous.addAll(draft.fragments());
            } else {
                merged.add(draft);
            }
        }
        return merged;
    }

    private boolean sameSection(SectionContext left, SectionContext right) {
        return Objects.equals(left.sectionId(), right.sectionId());
    }

    private String passageRef(String paperId, String modelVersion, PassageDraft draft) {
        String source = draft.fragments().stream()
                .map(fragment -> String.join(":",
                        firstNonBlank(fragment.element().getReadingElementId(), ""),
                        Integer.toString(fragment.charFrom()),
                        Integer.toString(fragment.charTo())))
                .collect(java.util.stream.Collectors.joining("|"));
        return "passage_ref_" + sha256(String.join("|", paperId, modelVersion, source, draft.contentText()));
    }

    private String indexText(String sectionTitle, String content) {
        return blank(sectionTitle) ? content : sectionTitle.trim() + "\n\n" + content;
    }

    private String sourceSpanJson(String passageRef, PassageDraft draft) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("locationType", PaperLocationType.PASSAGE.name());
        source.put("sourceObjectId", passageRef);
        source.put("pageNumberFrom", draft.pageNumberFrom());
        source.put("pageNumberTo", draft.pageNumberTo());
        source.put("readingOrderFrom", draft.readingOrderFrom());
        source.put("readingOrderTo", draft.readingOrderTo());
        int contentOffset = 0;
        List<Map<String, Object>> spans = new ArrayList<>();
        for (Fragment fragment : draft.fragments()) {
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("readingElementId", fragment.element().getReadingElementId());
            span.put("parserElementId", fragment.element().getParserElementId());
            span.put("pageNumber", fragment.element().getPageNumber());
            span.put("readingOrder", fragment.element().getReadingOrder());
            span.put("char_from", fragment.charFrom());
            span.put("char_to", fragment.charTo());
            span.put("content_char_from", contentOffset);
            contentOffset += fragment.text().length();
            span.put("content_char_to", contentOffset);
            span.put("elementSourceSpan", elementSourceSpan(fragment.element()));
            spans.add(span);
            contentOffset += 2;
        }
        source.put("spans", spans);
        try {
            return objectMapper.writeValueAsString(source);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("PASSAGE_SOURCE_SPAN_SERIALIZATION_FAILED", error);
        }
    }

    private JsonNode elementSourceSpan(PaperReadingElement element) {
        if (blank(element.getSourceSpanJson())) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(element.getSourceSpanJson());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("PASSAGE_ELEMENT_SOURCE_SPAN_INVALID", error);
        }
    }

    static int estimateTokens(String text) {
        if (blank(text)) {
            return 0;
        }
        long visibleCodePoints = text.codePoints().filter(value -> !Character.isWhitespace(value)).count();
        return Math.max(1, (int) Math.ceil(visibleCodePoints / 4.0d));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                hex.append(String.format("%02x", valueByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return "";
    }

    private record SectionContext(String sectionId,
                                  String sectionRef,
                                  String sectionTitle,
                                  int readingOrderFrom,
                                  int readingOrderTo) {
        private static final SectionContext UNSECTIONED = new SectionContext(null, null, null, 0, 0);
    }

    private record Fragment(PaperReadingElement element, String text, int charFrom, int charTo) {
        private int estimatedTokenCount() {
            return estimateTokens(text);
        }
    }

    private static final class PassageDraft {
        private final SectionContext section;
        private final int segment;
        private final List<Fragment> fragments = new ArrayList<>();

        private PassageDraft(SectionContext section, int segment) {
            this.section = section;
            this.segment = segment;
        }

        private void add(Fragment fragment) {
            fragments.add(fragment);
        }

        private void addAll(List<Fragment> additional) {
            fragments.addAll(additional);
        }

        private boolean canFollow(Fragment fragment) {
            if (fragments.isEmpty()) {
                return true;
            }
            int lastPage = fragments.get(fragments.size() - 1).element().getPageNumber();
            return fragment.element().getPageNumber() >= lastPage
                    && fragment.element().getPageNumber() <= lastPage + 1;
        }

        private List<Fragment> fragments() {
            return fragments;
        }

        private SectionContext section() {
            return section;
        }

        private int segment() {
            return segment;
        }

        private int estimatedTokenCount() {
            return estimateTokens(contentText());
        }

        private String contentText() {
            return fragments.stream().map(Fragment::text).collect(java.util.stream.Collectors.joining("\n\n"));
        }

        private int pageNumberFrom() {
            return fragments.get(0).element().getPageNumber();
        }

        private int pageNumberTo() {
            return fragments.get(fragments.size() - 1).element().getPageNumber();
        }

        private int readingOrderFrom() {
            return fragments.get(0).element().getReadingOrder();
        }

        private int readingOrderTo() {
            return fragments.get(fragments.size() - 1).element().getReadingOrder();
        }
    }
}
