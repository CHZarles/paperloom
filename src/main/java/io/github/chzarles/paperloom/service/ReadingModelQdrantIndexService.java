package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPassage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPassageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRetrievalControlRepository;
import io.github.chzarles.paperloom.service.embedding.EmbeddingProvider;
import io.github.chzarles.paperloom.service.embedding.EmbeddingProviderFactory;
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
import java.util.stream.Collectors;

@Service
public class ReadingModelQdrantIndexService {

    private static final Logger logger = LoggerFactory.getLogger(ReadingModelQdrantIndexService.class);
    private static final int MAX_INDEX_TEXT_CHARS = 12_000;

    private final PaperReadingModelRepository modelRepository;
    private final PaperReadingElementRepository elementRepository;
    private final PaperLocationRepository locationRepository;
    private final PaperPassageRepository passageRepository;
    private final QdrantClient qdrantClient;
    private final PaperRetrievalControlRepository controlRepository;
    private final RetrievalIndexContractService contractService;
    private final EmbeddingProviderFactory embeddingProviderFactory;

    public ReadingModelQdrantIndexService(PaperReadingModelRepository modelRepository,
                                          PaperReadingElementRepository elementRepository,
                                          PaperLocationRepository locationRepository,
                                          PaperPassageRepository passageRepository,
                                          QdrantClient qdrantClient,
                                          PaperRetrievalControlRepository controlRepository,
                                          RetrievalIndexContractService contractService,
                                          EmbeddingProviderFactory embeddingProviderFactory) {
        this.modelRepository = modelRepository;
        this.elementRepository = elementRepository;
        this.locationRepository = locationRepository;
        this.passageRepository = passageRepository;
        this.qdrantClient = qdrantClient;
        this.controlRepository = controlRepository;
        this.contractService = contractService;
        this.embeddingProviderFactory = embeddingProviderFactory;
    }

    public IndexResult indexCurrentModel(String paperId, String requesterId) {
        return indexCurrentModel(paperId, requesterId, () -> { });
    }

    public IndexResult indexCurrentModel(String paperId, String requesterId, Runnable beforeUpsert) {
        PaperReadingModel model = currentReadyModel(paperId);
        if (model.getRetrievalIndexStatus() == PaperRetrievalIndexStatus.READY) {
            return existingResult(model);
        }
        rejectDuringFullRebuild();
        String jobId = UUID.randomUUID().toString();
        int claimed = modelRepository.claimInitialIndex(
                paperId, model.getModelVersion(), jobId, LocalDateTime.now());
        if (claimed != 1) {
            throw new IllegalStateException("PAPER_INDEX_OPERATION_RUNNING");
        }
        return buildClaimed(model, requesterId, beforeUpsert,
                PaperRetrievalIndexStatus.BUILDING, jobId);
    }

    public IndexResult rebuildCurrentModel(String paperId, String requesterId) {
        PaperReadingModel model = currentReadyModel(paperId);
        rejectDuringFullRebuild();
        String jobId = UUID.randomUUID().toString();
        int claimed = modelRepository.claimRebuild(
                paperId, model.getModelVersion(), jobId, LocalDateTime.now());
        if (claimed != 1) {
            throw new IllegalStateException("PAPER_INDEX_OPERATION_RUNNING");
        }
        return buildClaimed(model, requesterId, () -> { },
                PaperRetrievalIndexStatus.REBUILDING, jobId);
    }

    IndexResult rebuildClaimedCurrentModel(String paperId, String requesterId, String jobId) {
        PaperReadingModel model = currentReadyModel(paperId);
        return buildClaimed(model, requesterId, () -> { },
                PaperRetrievalIndexStatus.REBUILDING, jobId);
    }

    private IndexResult buildClaimed(PaperReadingModel model,
                                     String requesterId,
                                     Runnable beforeUpsert,
                                     PaperRetrievalIndexStatus runningStatus,
                                     String jobId) {
        String paperId = model.getPaperId();
        try {
            qdrantClient.deleteByPaperId(paperId);
            List<IndexedLocation> locations = buildIndexedLocations(paperId, model.getModelVersion());
            if (locations.isEmpty()) {
                throw new IllegalStateException("Current Reading Model contains no indexable locations");
            }

            List<String> texts = locations.stream().map(IndexedLocation::searchableText).toList();
            double suggestedAverage = LexicalBm25Encoder.averageDocumentLength(texts);
            if (suggestedAverage <= 0) {
                throw new IllegalStateException("Current Reading Model contains no lexical tokens");
            }
            String indexContract = contractService.ensureActiveContract(suggestedAverage);
            double averageDocumentLength = contractService.activeAverageDocumentLength();
            qdrantClient.ensureCollection();

            List<QdrantPoint> points = new ArrayList<>(locations.size());
            int indexedTokens = 0;
            int hashCollisions = 0;
            List<IndexedLocation> encodable = new ArrayList<>();
            List<String> encodableTexts = new ArrayList<>();
            for (IndexedLocation location : locations) {
                LexicalBm25Encoder.EncodedDocument encoded = LexicalBm25Encoder.encodeDocument(
                        location.searchableText(), averageDocumentLength);
                if (encoded.vector().indices().isEmpty()) {
                    continue;
                }
                indexedTokens += encoded.tokenCount();
                hashCollisions += encoded.hashCollisions();
                Map<String, Object> payload = new LinkedHashMap<>(location.payload());
                payload.put("lexical_contract", indexContract);
                payload.put("lexical_token_count", encoded.tokenCount());
                points.add(new QdrantPoint(
                        pointId(location.paperId(), location.modelVersion(), location.locationRef()),
                        encoded.vector(),
                        null,
                        payload
                ));
                encodable.add(location);
                encodableTexts.add(location.searchableText());
            }
            if (qdrantClient.isHybridContract()) {
                EmbeddingProvider embeddingProvider = embeddingProviderFactory.activeProvider();
                List<float[]> vectors = embeddingProvider.embed(encodableTexts).block();
                if (vectors == null || vectors.size() != encodable.size()) {
                    throw new IllegalStateException("Embedding provider returned mismatched vector count for paperId=" + paperId);
                }
                for (int i = 0; i < points.size(); i++) {
                    QdrantPoint sparsePoint = points.get(i);
                    points.set(i, new QdrantPoint(
                            sparsePoint.id(),
                            sparsePoint.lexicalVector(),
                            vectors.get(i),
                            sparsePoint.payload()
                    ));
                }
            }
            if (points.isEmpty()) {
                throw new IllegalStateException("Current Reading Model contains no lexical index points");
            }
            if (hashCollisions > 0) {
                throw new IllegalStateException(
                        "Lexical term hash collision detected; full term-id contract rebuild required: "
                                + hashCollisions);
            }

            logger.info(
                    "Built lexical Qdrant points: paperId={}, modelVersion={}, points={}, tokens={}, "
                            + "hashCollisions={}, contract={}",
                    paperId, model.getModelVersion(), points.size(), indexedTokens, hashCollisions, indexContract);

            beforeUpsert.run();
            qdrantClient.upsert(points);
            long written = qdrantClient.countByPaperIdAndModelVersion(paperId, model.getModelVersion());
            if (written != points.size()) {
                throw new IllegalStateException("Qdrant indexed " + written + " of " + points.size()
                        + " expected Reading Model locations for paperId=" + paperId);
            }

            int finalized = modelRepository.finishRetrievalIndexReady(
                    paperId,
                    model.getModelVersion(),
                    runningStatus.name(),
                    jobId,
                    indexContract,
                    points.size(),
                    LocalDateTime.now()
            );
            if (finalized != 1) {
                qdrantClient.deleteByPaperId(paperId);
                throw new IllegalStateException("Current Reading Model changed while indexing paperId=" + paperId);
            }
            return new IndexResult(
                    indexedTokens, points.size(), hashCollisions, indexContract, model.getModelVersion());
        } catch (RuntimeException error) {
            try {
                qdrantClient.deleteByPaperId(paperId);
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            modelRepository.finishRetrievalIndexFailed(
                    paperId,
                    model.getModelVersion(),
                    runningStatus.name(),
                    jobId,
                    error.getClass().getSimpleName(),
                    boundedMessage(error)
            );
            throw error;
        }
    }

    private PaperReadingModel currentReadyModel(String paperId) {
        return modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId)
                .filter(candidate -> candidate.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                .orElseThrow(() -> new IllegalStateException(
                        "Current READY Reading Model not found for paperId=" + paperId));
    }

    private IndexResult existingResult(PaperReadingModel model) {
        return new IndexResult(
                0,
                model.getRetrievalIndexedLocationCount() == null ? 0 : model.getRetrievalIndexedLocationCount(),
                0,
                model.getRetrievalIndexContract() == null ? "" : model.getRetrievalIndexContract(),
                model.getModelVersion()
        );
    }

    private void rejectDuringFullRebuild() {
        if (controlRepository.existsByControlNameAndFullRebuildStatus(
                io.github.chzarles.paperloom.model.PaperRetrievalControl.FULL_REBUILD,
                io.github.chzarles.paperloom.model.PaperRetrievalControl.RUNNING)) {
            throw new IllegalStateException("RETRIEVAL_MAINTENANCE_RUNNING");
        }
    }

    private String boundedMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
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
        Map<String, PaperPassage> passagesByRef = passageRepository
                .findByPaperIdAndModelVersionOrderByDocumentOrdinalAsc(paperId, modelVersion).stream()
                .collect(Collectors.toMap(PaperPassage::getPassageRef, Function.identity(), (left, right) -> left));
        Map<String, PaperReadingElement> elementsById = elements.stream()
                .filter(element -> !blank(element.getReadingElementId()))
                .collect(Collectors.toMap(PaperReadingElement::getReadingElementId, Function.identity(), (left, right) -> left));
        Map<Integer, String> pageLocationRefs = locations.stream()
                .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                .filter(location -> location.getPageNumber() != null)
                .filter(location -> !blank(location.getLocationRef()))
                .collect(Collectors.toMap(PaperLocation::getPageNumber, PaperLocation::getLocationRef,
                        (left, right) -> left));

        List<IndexedLocation> indexed = new ArrayList<>();
        for (PaperLocation location : locations) {
            if (blank(location.getLocationRef()) || !isEvidenceLocation(location)) {
                continue;
            }
            ResolvedLocation resolved = resolveLocationText(location, passagesByRef, elementsById);
            if (resolved == null || blank(resolved.indexText())) {
                continue;
            }
            String searchableText = resolved.indexText().trim();
            if (searchableText.length() > MAX_INDEX_TEXT_CHARS) {
                searchableText = searchableText.substring(0, MAX_INDEX_TEXT_CHARS);
            }
            List<PaperReadingElement> relatedElements = relatedElements(location, elementsById);
            indexed.add(new IndexedLocation(
                    location.getPaperId(),
                    location.getModelVersion(),
                    location.getLocationRef(),
                    location.getPageNumber(),
                    searchableText,
                    payload(location, searchableText, resolved, relatedElements, pageLocationRefs)
            ));
        }

        return indexed.stream()
                .sorted(Comparator
                        .comparing((IndexedLocation location) -> location.pageNumber() == null
                                ? Integer.MAX_VALUE
                                : location.pageNumber())
                        .thenComparing(IndexedLocation::locationRef))
                .toList();
    }

    private ResolvedLocation resolveLocationText(PaperLocation location,
                                                  Map<String, PaperPassage> passagesByRef,
                                                  Map<String, PaperReadingElement> elementsById) {
        if (location.getLocationType() == PaperLocationType.PASSAGE) {
            PaperPassage passage = passagesByRef.get(location.getSourceObjectId());
            return passage == null || blank(passage.getContentText())
                    ? null
                    : new ResolvedLocation(
                            passage.getIndexText(), passage.getContentText(), passage.getContentHash(),
                            passage.getSourceSpanJson(), null, null, passage.getPassageRef(), passage);
        }
        PaperReadingElement element = elementsById.get(location.getSourceObjectId());
        if (element == null) {
            return null;
        }
        String text = firstNonBlank(
                element.getSearchableText(), element.getBodyText(), element.getCaptionText());
        return blank(text)
                ? null
                : new ResolvedLocation(
                        text, text, textHash(text), location.getSourceSpanJson(), element.getParserName(),
                        element.getParserVersion(), element.getReadingElementId(), null);
    }

    private List<PaperReadingElement> relatedElements(
            PaperLocation location,
            Map<String, PaperReadingElement> elementsById) {
        PaperReadingElement element = elementsById.get(location.getSourceObjectId());
        return element == null ? List.of() : List.of(element);
    }

    private Map<String, Object> payload(PaperLocation location,
                                        String searchableText,
                                        ResolvedLocation resolved,
                                        List<PaperReadingElement> elements,
                                        Map<Integer, String> pageLocationRefs) {
        LinkedHashSet<String> elementTypes = elements.stream()
                .map(PaperReadingElement::getElementType)
                .filter(value -> !blank(value))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String canonicalElementType = switch (location.getLocationType()) {
            case PASSAGE -> "passage";
            case TABLE -> "table";
            case FIGURE -> "figure";
            default -> location.getLocationType().name().toLowerCase(Locale.ROOT);
        };
        elementTypes.remove(canonicalElementType);
        LinkedHashSet<String> canonicalFirst = new LinkedHashSet<>();
        canonicalFirst.add(canonicalElementType);
        canonicalFirst.addAll(elementTypes);
        elementTypes = canonicalFirst;
        if (elementTypes.isEmpty()) {
            elementTypes.add(canonicalElementType);
        }
        LinkedHashSet<String> readingElementIds = elements.stream()
                .map(PaperReadingElement::getReadingElementId)
                .filter(value -> !blank(value))
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "paper_id", location.getPaperId());
        put(payload, "model_version", location.getModelVersion());
        put(payload, "location_ref", location.getLocationRef());
        put(payload, "reading_element_id", readingElementIds.stream().findFirst().orElse(""));
        put(payload, "reading_element_ids", List.copyOf(readingElementIds));
        put(payload, "source_object_id", firstNonBlank(location.getSourceObjectId(), resolved.sourceObjectId()));
        put(payload, "element_type", elementTypes.iterator().next());
        put(payload, "element_types", List.copyOf(elementTypes));
        put(payload, "location_type", location.getLocationType().name());
        put(payload, "page_number", location.getPageNumber());
        put(payload, "page_end_number", location.getPageEndNumber() == null
                ? location.getPageNumber()
                : location.getPageEndNumber());
        put(payload, "section_path", location.getSectionTitle());
        put(payload, "text_hash", resolved.contentHash());
        put(payload, "content_text", resolved.contentText());
        put(payload, "content_hash", resolved.contentHash());
        put(payload, "source_span_json", resolved.sourceSpanJson());
        put(payload, "parser_name", resolved.parserName());
        put(payload, "parser_version", resolved.parserVersion());
        if (resolved.passage() != null) {
            PaperPassage passage = resolved.passage();
            put(payload, "parent_section_ref", passage.getParentSectionRef());
            put(payload, "section_ordinal", passage.getSectionOrdinal());
            put(payload, "document_ordinal", passage.getDocumentOrdinal());
            put(payload, "estimated_token_count", passage.getEstimatedTokenCount());
            put(payload, "page_location_refs", pageLocationRefs(location.getPageNumber(), location.getPageEndNumber(), pageLocationRefs));
        }
        return payload;
    }

    private boolean isEvidenceLocation(PaperLocation location) {
        return location.getLocationType() == PaperLocationType.PASSAGE
                || location.getLocationType() == PaperLocationType.TABLE
                || location.getLocationType() == PaperLocationType.FIGURE;
    }

    private List<String> pageLocationRefs(Integer from, Integer to, Map<Integer, String> refsByPage) {
        if (from == null) {
            return List.of();
        }
        int end = to == null ? from : to;
        List<String> refs = new ArrayList<>();
        for (int page = from; page <= end; page++) {
            String ref = refsByPage.get(page);
            if (!blank(ref)) {
                refs.add(ref);
            }
        }
        return refs;
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

    static String pointId(String paperId, String modelVersion, String locationRef) {
        byte[] digest = sha256Bytes(paperId + "\n" + modelVersion + "\n" + locationRef);
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
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

    public record IndexResult(int indexedTokenCount,
                              int indexedLocationCount,
                              int hashCollisionCount,
                              String retrievalIndexContract,
                              String readingModelVersion) {
    }

    public record IndexedLocation(String paperId,
                                  String modelVersion,
                                  String locationRef,
                                  Integer pageNumber,
                                  String searchableText,
                                  Map<String, Object> payload) {
    }

    private record ResolvedLocation(String indexText,
                                    String contentText,
                                    String contentHash,
                                    String sourceSpanJson,
                                    String parserName,
                                    String parserVersion,
                                    String sourceObjectId,
                                    PaperPassage passage) {
    }
}
