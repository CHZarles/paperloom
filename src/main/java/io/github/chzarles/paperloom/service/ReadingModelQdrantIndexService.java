package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.client.EmbeddingClient;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ReadingModelQdrantIndexService {

    private static final Logger logger = LoggerFactory.getLogger(ReadingModelQdrantIndexService.class);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final int MAX_EMBEDDING_TEXT_CHARS = 12_000;

    private final PaperReadingModelRepository modelRepository;
    private final PaperReadingElementRepository elementRepository;
    private final PaperLocationRepository locationRepository;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;

    public ReadingModelQdrantIndexService(PaperReadingModelRepository modelRepository,
                                          PaperReadingElementRepository elementRepository,
                                          PaperLocationRepository locationRepository,
                                          EmbeddingClient embeddingClient,
                                          QdrantClient qdrantClient) {
        this.modelRepository = modelRepository;
        this.elementRepository = elementRepository;
        this.locationRepository = locationRepository;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
    }

    public IndexResult indexCurrentModel(String paperId, String requesterId) {
        return indexCurrentModel(paperId, requesterId, () -> { });
    }

    public IndexResult indexCurrentModel(String paperId, String requesterId, Runnable beforeUpsert) {
        PaperReadingModel model = modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId)
                .filter(candidate -> candidate.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                .orElseThrow(() -> new IllegalStateException("Current READY Reading Model not found for paperId=" + paperId));
        String previousGeneration = blank(model.getRetrievalIndexGeneration())
                ? null
                : model.getRetrievalIndexGeneration().trim();

        List<IndexedLocation> locations = buildIndexedLocations(paperId, model.getModelVersion());
        if (locations.isEmpty()) {
            model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.UNAVAILABLE);
            model.setRetrievalIndexGeneration(null);
            model.setRetrievalEmbeddingContract(null);
            model.setRetrievalIndexedLocationCount(0);
            model.setRetrievalIndexedAt(LocalDateTime.now());
            modelRepository.save(model);
            qdrantClient.deleteByPaperId(paperId);
            return new IndexResult(0, 0, embeddingClient.currentModelVersion(), model.getModelVersion());
        }

        List<String> texts = locations.stream().map(IndexedLocation::searchableText).toList();
        EmbeddingClient.EmbeddingUsageResult embedding = embeddingClient.embedWithUsage(
                texts,
                requesterId,
                EmbeddingClient.UsageType.UPLOAD
        );
        if (embedding.vectors().size() != locations.size()) {
            throw new IllegalStateException("Embedding result count does not match indexed Reading Model locations");
        }
        int dimension = embedding.vectors().get(0).length;
        qdrantClient.ensureCollection(dimension);

        List<QdrantPoint> points = new ArrayList<>(locations.size());
        String indexGeneration = UUID.randomUUID().toString();
        for (int index = 0; index < locations.size(); index++) {
            IndexedLocation location = locations.get(index);
            Map<String, Object> payload = new LinkedHashMap<>(location.payload());
            payload.put("index_generation", indexGeneration);
            points.add(new QdrantPoint(
                    pointId(location.paperId(), location.modelVersion(), location.locationRef(), indexGeneration),
                    embedding.vectors().get(index),
                    sparseVector(location.searchableText()),
                    payload
            ));
        }

        beforeUpsert.run();
        String embeddingContract = qdrantClient.indexVersion() + "|" + embedding.modelVersion() + "|" + dimension;
        LocalDateTime indexedAt = LocalDateTime.now();
        boolean activated = false;
        try {
            qdrantClient.upsert(points);
            long written = qdrantClient.countByPaperIdAndGeneration(paperId, indexGeneration);
            if (written != points.size()) {
                throw new IllegalStateException("Qdrant indexed " + written + " of " + points.size()
                        + " expected Reading Model locations for paperId=" + paperId);
            }
            int updated = modelRepository.activateRetrievalIndex(
                    paperId,
                    model.getModelVersion(),
                    previousGeneration,
                    indexGeneration,
                    embeddingContract,
                    points.size(),
                    indexedAt
            );
            if (updated != 1) {
                throw new IllegalStateException(
                        "Current Reading Model or active generation changed while Qdrant indexing was in progress for paperId="
                                + paperId);
            }
            activated = true;
        } catch (RuntimeException activationError) {
            if (!activated) {
                deleteUnactivatedGeneration(paperId, indexGeneration, activationError);
            }
            throw activationError;
        }
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.READY);
        model.setRetrievalIndexGeneration(indexGeneration);
        model.setRetrievalEmbeddingContract(embeddingContract);
        model.setRetrievalIndexedLocationCount(points.size());
        model.setRetrievalIndexedAt(indexedAt);
        if (previousGeneration != null && !previousGeneration.equals(indexGeneration)) {
            try {
                qdrantClient.deleteByPaperIdAndGeneration(paperId, previousGeneration);
                long retained = qdrantClient.countByPaperIdAndGeneration(paperId, previousGeneration);
                if (retained != 0) {
                    throw new IllegalStateException("Qdrant retained " + retained
                            + " points from stale generation " + previousGeneration
                            + " for paperId=" + paperId);
                }
            } catch (RuntimeException cleanupError) {
                logger.warn("Activated Qdrant generation but stale-generation cleanup failed: paperId={}, generation={}",
                        paperId, indexGeneration, cleanupError);
            }
        }
        return new IndexResult(
                embedding.totalTokens(),
                points.size(),
                embedding.modelVersion(),
                model.getModelVersion()
        );
    }

    private void deleteUnactivatedGeneration(String paperId,
                                              String indexGeneration,
                                              RuntimeException activationError) {
        try {
            qdrantClient.deleteByPaperIdAndGeneration(paperId, indexGeneration);
        } catch (RuntimeException cleanupError) {
            activationError.addSuppressed(cleanupError);
            logger.warn("Failed to remove an unactivated Qdrant generation: paperId={}, generation={}",
                    paperId, indexGeneration, cleanupError);
        }
    }

    public void deleteByPaperId(String paperId) {
        qdrantClient.deleteByPaperId(paperId);
    }

    public long countByPaperId(String paperId) {
        return qdrantClient.countByPaperId(paperId);
    }

    public Set<String> indexedPaperIds(int limit) {
        return qdrantClient.indexedPaperIds(limit);
    }

    public List<IndexedLocation> buildIndexedLocations(String paperId, String modelVersion) {
        List<PaperReadingElement> elements = elementRepository
                .findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(paperId, modelVersion);
        List<PaperLocation> locations = locationRepository
                .findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(paperId, modelVersion);
        Map<String, PaperLocation> locationsByRef = locations.stream()
                .filter(location -> !blank(location.getLocationRef()))
                .collect(Collectors.toMap(PaperLocation::getLocationRef, Function.identity(), (left, right) -> left));
        Map<Integer, PaperLocation> pageLocations = locations.stream()
                .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                .filter(location -> location.getPageNumber() != null)
                .collect(Collectors.toMap(PaperLocation::getPageNumber, Function.identity(), (left, right) -> left));
        Map<String, PaperReadingElement> elementsById = elements.stream()
                .filter(element -> !blank(element.getReadingElementId()))
                .collect(Collectors.toMap(PaperReadingElement::getReadingElementId, Function.identity(), (left, right) -> left));

        Map<String, IndexedLocationBuilder> grouped = new LinkedHashMap<>();
        for (PaperReadingElement element : elements) {
            String text = firstNonBlank(element.getSearchableText(), element.getBodyText(), element.getCaptionText());
            if (blank(text)) {
                continue;
            }
            PaperLocation location = routedLocation(element, locationsByRef, pageLocations, elementsById);
            if (location == null || blank(location.getLocationRef())) {
                continue;
            }
            grouped.computeIfAbsent(location.getLocationRef(), ignored -> new IndexedLocationBuilder(location))
                    .add(element, text);
        }

        return grouped.values().stream()
                .map(IndexedLocationBuilder::build)
                .sorted(Comparator
                        .comparing((IndexedLocation location) -> location.pageNumber() == null
                                ? Integer.MAX_VALUE
                                : location.pageNumber())
                        .thenComparing(IndexedLocation::locationRef))
                .toList();
    }

    static String pointId(String paperId, String modelVersion, String locationRef, String indexGeneration) {
        byte[] digest = sha256Bytes(
                paperId + "\n" + modelVersion + "\n" + locationRef + "\n" + indexGeneration);
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    static QdrantSparseVector sparseVector(String text) {
        Map<Integer, Integer> termFrequency = new LinkedHashMap<>();
        Matcher matcher = TOKEN_PATTERN.matcher(SearchText.normalize(text));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() < 2) {
                continue;
            }
            termFrequency.merge(stableTokenIndex(token), 1, Integer::sum);
        }
        List<Integer> indices = termFrequency.keySet().stream().sorted().toList();
        List<Float> values = indices.stream()
                .map(index -> (float) (1.0 + Math.log(termFrequency.get(index))))
                .toList();
        return new QdrantSparseVector(indices, values);
    }

    private static int stableTokenIndex(String token) {
        byte[] digest = sha256Bytes(token);
        return ByteBuffer.wrap(digest).getInt() & Integer.MAX_VALUE;
    }

    private PaperLocation routedLocation(PaperReadingElement element,
                                         Map<String, PaperLocation> locationsByRef,
                                         Map<Integer, PaperLocation> pageLocations,
                                         Map<String, PaperReadingElement> elementsById) {
        PaperLocation own = locationsByRef.get(element.getLocationRef());
        if (own != null) {
            return own;
        }
        PaperReadingElement parent = blank(element.getParentReadingElementId())
                ? null
                : elementsById.get(element.getParentReadingElementId());
        PaperLocation parentLocation = parent == null ? null : locationsByRef.get(parent.getLocationRef());
        if (parentLocation != null) {
            return parentLocation;
        }
        return element.getPageNumber() == null ? null : pageLocations.get(element.getPageNumber());
    }

    private static String textHash(String value) {
        byte[] digest = sha256Bytes(value == null ? "" : value);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            builder.append(String.format(Locale.ROOT, "%02x", item));
        }
        return builder.toString();
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record IndexResult(int actualEmbeddingTokens,
                              int indexedLocationCount,
                              String embeddingModelVersion,
                              String readingModelVersion) {
    }

    public record IndexedLocation(String paperId,
                                  String modelVersion,
                                  String locationRef,
                                  Integer pageNumber,
                                  String searchableText,
                                  Map<String, Object> payload) {
    }

    private static final class IndexedLocationBuilder {
        private final PaperLocation location;
        private final LinkedHashSet<String> texts = new LinkedHashSet<>();
        private final LinkedHashSet<String> elementTypes = new LinkedHashSet<>();
        private final LinkedHashSet<String> readingElementIds = new LinkedHashSet<>();
        private String sourceObjectId = "";
        private String parserName = "";
        private String parserVersion = "";

        private IndexedLocationBuilder(PaperLocation location) {
            this.location = location;
        }

        private IndexedLocationBuilder add(PaperReadingElement element, String text) {
            texts.add(text.trim());
            if (!blank(element.getElementType())) {
                elementTypes.add(element.getElementType().trim().toLowerCase(Locale.ROOT));
            }
            if (!blank(element.getReadingElementId())) {
                readingElementIds.add(element.getReadingElementId().trim());
            }
            sourceObjectId = firstNonBlank(sourceObjectId, element.getSourceObjectId(), location.getSourceObjectId());
            parserName = firstNonBlank(parserName, element.getParserName());
            parserVersion = firstNonBlank(parserVersion, element.getParserVersion());
            return this;
        }

        private IndexedLocation build() {
            String searchableText = String.join("\n", texts);
            if (searchableText.length() > MAX_EMBEDDING_TEXT_CHARS) {
                searchableText = searchableText.substring(0, MAX_EMBEDDING_TEXT_CHARS);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            put(payload, "paper_id", location.getPaperId());
            put(payload, "model_version", location.getModelVersion());
            put(payload, "location_ref", location.getLocationRef());
            put(payload, "reading_element_id", readingElementIds.stream().findFirst().orElse(""));
            put(payload, "reading_element_ids", List.copyOf(readingElementIds));
            put(payload, "source_object_id", firstNonBlank(sourceObjectId, location.getSourceObjectId()));
            put(payload, "element_type", elementTypes.stream().findFirst().orElse(
                    location.getLocationType().name().toLowerCase(Locale.ROOT)));
            put(payload, "element_types", List.copyOf(elementTypes));
            put(payload, "location_type", location.getLocationType().name());
            put(payload, "page_number", location.getPageNumber());
            put(payload, "page_end_number", location.getPageEndNumber());
            put(payload, "section_path", location.getSectionTitle());
            put(payload, "text_hash", textHash(searchableText));
            put(payload, "parser_name", parserName);
            put(payload, "parser_version", parserVersion);
            put(payload, "owner_user_id", location.getUserId());
            put(payload, "org_tag", location.getOrgTag());
            put(payload, "is_public", location.isPublic());
            return new IndexedLocation(
                    location.getPaperId(),
                    location.getModelVersion(),
                    location.getLocationRef(),
                    location.getPageNumber(),
                    searchableText,
                    payload
            );
        }

        private static void put(Map<String, Object> target, String key, Object value) {
            if (value == null) {
                return;
            }
            if (value instanceof String text && text.isBlank()) {
                return;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                return;
            }
            target.put(key, value);
        }
    }
}
