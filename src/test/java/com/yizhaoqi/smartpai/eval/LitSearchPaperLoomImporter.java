package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperTextChunk;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LitSearchPaperLoomImporter {

    private static final String DATASET_PREFIX = "litsearch:";
    private static final String SOURCE_DATASET = "litsearch";
    private static final String PARSER_NAME = "litsearch";
    private static final String PARSER_VERSION = "corpus_clean";
    private static final String MODEL_VERSION = "eval:litsearch:no-embedding";
    private static final int VECTOR_DIMS = 2048;
    private static final int DEFAULT_INDEX_BATCH_SIZE = 500;
    private static final int MYSQL_VARCHAR_DEFAULT_LIMIT = 255;

    private final PaperRepository paperRepository;
    private final PaperTextChunkRepository paperTextChunkRepository;
    private final ElasticsearchService elasticsearchService;

    public LitSearchPaperLoomImporter(PaperRepository paperRepository,
                                      PaperTextChunkRepository paperTextChunkRepository,
                                      ElasticsearchService elasticsearchService) {
        this.paperRepository = paperRepository;
        this.paperTextChunkRepository = paperTextChunkRepository;
        this.elasticsearchService = elasticsearchService;
    }

    public ImportSummary importPapers(List<LitSearchPaperDocument> papers, Options options) {
        ImportRun run = new ImportRun(options);
        for (LitSearchPaperDocument source : papers == null ? List.<LitSearchPaperDocument>of() : papers) {
            run.accept(source);
        }
        return run.finish();
    }

    public ImportSummary importJsonl(Path corpusPath, Options options) throws IOException {
        return importJsonl(corpusPath, options, 0);
    }

    public ImportSummary importJsonl(Path corpusPath, Options options, int maxPapers) throws IOException {
        return importJsonl(corpusPath, options, 0, maxPapers);
    }

    public ImportSummary importJsonl(Path corpusPath, Options options, int startOffset, int maxPapers) throws IOException {
        ImportRun run = new ImportRun(options);
        LitSearchPaperDocumentDataset.forEachUntil(corpusPath, startOffset, maxPapers, source -> {
            run.accept(source);
            return true;
        });
        return run.finish();
    }

    private class ImportRun {
        private final Options options;
        private final List<PaperChunkDocument> chunkDocuments = new ArrayList<>();
        private final List<PaperSearchDocument> paperDocuments = new ArrayList<>();
        private int paperCount;
        private int chunkCount;

        private ImportRun(Options options) {
            this.options = options == null ? Options.defaults() : options;
        }

        private void accept(LitSearchPaperDocument source) {
            if (source.paperId().isBlank()) {
                return;
            }
            String paperId = paperId(source);
            clearExistingPaper(paperId);
            Paper paper = toPaper(source, options);
            List<PaperTextChunk> chunks = toChunks(source, paper.getPaperId(), options);
            paper.setEstimatedChunkCount(chunks.size());
            paper.setActualChunkCount(chunks.size());
            paperRepository.save(paper);
            paperTextChunkRepository.saveAll(chunks);
            paperDocuments.add(toPaperSearchDocument(source, paper, options));
            chunkDocuments.addAll(toChunkDocuments(paper, chunks, options));
            paperCount++;
            chunkCount += chunks.size();
            flushPaperDocumentsIfNeeded(paperDocuments, options.indexBatchSize());
            flushChunkDocumentsIfNeeded(chunkDocuments, options.indexBatchSize());
        }

        private int paperCount() {
            return paperCount;
        }

        private ImportSummary finish() {
            flushPaperDocuments(paperDocuments);
            flushChunkDocuments(chunkDocuments);
            return new ImportSummary(paperCount, chunkCount);
        }
    }

    private void flushPaperDocumentsIfNeeded(List<PaperSearchDocument> documents, int batchSize) {
        if (documents.size() >= batchSize) {
            flushPaperDocuments(documents);
        }
    }

    private void flushChunkDocumentsIfNeeded(List<PaperChunkDocument> documents, int batchSize) {
        if (documents.size() >= batchSize) {
            flushChunkDocuments(documents);
        }
    }

    private void flushPaperDocuments(List<PaperSearchDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        elasticsearchService.bulkIndexPaperSearch(List.copyOf(documents));
        documents.clear();
    }

    private void flushChunkDocuments(List<PaperChunkDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        elasticsearchService.bulkIndex(List.copyOf(documents));
        documents.clear();
    }

    private Paper toPaper(LitSearchPaperDocument source, Options options) {
        String paperId = paperId(source);
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setOriginalFilename(paperId + ".json");
        paper.setPaperTitle(truncate(source.title(), MYSQL_VARCHAR_DEFAULT_LIMIT));
        paper.setAbstractText(source.abstractText());
        paper.setSourceDataset(SOURCE_DATASET);
        paper.setExternalCorpusId(source.paperId());
        paper.setEvalSplit(options.evalSplit());
        paper.setEval(true);
        paper.setUserId(options.userId());
        paper.setOrgTag(options.orgTag());
        paper.setPublic(options.isPublic());
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        paper.setVectorizationErrorMessage(null);
        paper.setActualEmbeddingTokens(0L);
        return paper;
    }

    private PaperSearchDocument toPaperSearchDocument(LitSearchPaperDocument source, Paper paper, Options options) {
        PaperSearchDocument document = PaperSearchDocument.from(
                paper,
                options.userId(),
                options.orgTag(),
                options.isPublic()
        );
        document.setPaperTitle(source.title());
        document.setSearchText(paperSearchText(source, paper));
        return document;
    }

    private void clearExistingPaper(String paperId) {
        elasticsearchService.deleteByPaperId(paperId);
        paperTextChunkRepository.deleteByPaperId(paperId);
        paperRepository.deleteByPaperId(paperId);
    }

    private List<PaperTextChunk> toChunks(LitSearchPaperDocument source, String paperId, Options options) {
        List<PaperTextChunk> chunks = new ArrayList<>();
        chunks.add(chunk(
                paperId,
                1,
                abstractChunkText(source),
                "Abstract",
                "PAPER_METADATA",
                options
        ));
        int chunkId = 2;
        for (String bodyChunk : splitBody(source.fullPaperText(), options.maxChunkCharacters())) {
            chunks.add(chunk(
                    paperId,
                    chunkId++,
                    bodyChunk,
                    "Full Text",
                    "FULL_TEXT",
                    options
            ));
        }
        return chunks;
    }

    private PaperTextChunk chunk(String paperId,
                                 int chunkId,
                                 String text,
                                 String sectionTitle,
                                 String evidenceRole,
                                 Options options) {
        PaperTextChunk chunk = new PaperTextChunk();
        chunk.setPaperId(paperId);
        chunk.setChunkId(chunkId);
        chunk.setTextContent(text);
        chunk.setPageNumber(chunkId);
        chunk.setAnchorText(sectionTitle);
        chunk.setElementType("PARAGRAPH");
        chunk.setSectionTitle(sectionTitle);
        chunk.setSectionLevel(1);
        chunk.setParserName(PARSER_NAME);
        chunk.setParserVersion(PARSER_VERSION);
        chunk.setSourceKind("TEXT");
        chunk.setEvidenceRole(evidenceRole);
        chunk.setModelVersion(MODEL_VERSION);
        chunk.setUserId(options.userId());
        chunk.setOrgTag(options.orgTag());
        chunk.setPublic(options.isPublic());
        return chunk;
    }

    private List<PaperChunkDocument> toChunkDocuments(Paper paper, List<PaperTextChunk> chunks, Options options) {
        List<PaperChunkDocument> documents = new ArrayList<>();
        for (PaperTextChunk chunk : chunks) {
            PaperChunkDocument document = new PaperChunkDocument(
                    UUID.randomUUID().toString(),
                    chunk.getPaperId(),
                    chunk.getChunkId(),
                    chunk.getTextContent(),
                    chunk.getPageNumber(),
                    chunk.getAnchorText(),
                    chunk.getElementType(),
                    chunk.getSectionTitle(),
                    chunk.getSectionLevel(),
                    chunk.getBboxJson(),
                    chunk.getParserName(),
                    chunk.getParserVersion(),
                    chunk.getSourceKind(),
                    chunk.getTableId(),
                    chunk.getFigureId(),
                    chunk.getFormulaId(),
                    chunk.getEvidenceRole(),
                    placeholderVector(),
                    MODEL_VERSION,
                    options.userId(),
                    options.orgTag(),
                    options.isPublic()
            );
            document.setRetrievalTextContent(retrievalText(paper, chunk));
            documents.add(document);
        }
        return documents;
    }

    private static String abstractChunkText(LitSearchPaperDocument source) {
        return "Title: " + source.title() + "\nAbstract: " + source.abstractText();
    }

    private static List<String> splitBody(String text, int maxCharacters) {
        String safeText = text == null ? "" : text.trim();
        if (safeText.isBlank()) {
            return List.of();
        }
        int chunkSize = Math.max(1, maxCharacters);
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < safeText.length(); start += chunkSize) {
            int end = Math.min(safeText.length(), start + chunkSize);
            chunks.add(safeText.substring(start, end).trim());
        }
        return chunks;
    }

    private static String retrievalText(Paper paper, PaperTextChunk chunk) {
        List<String> parts = new ArrayList<>();
        append(parts, "title", paper.getPaperTitle());
        append(parts, "filename", paper.getOriginalFilename());
        append(parts, "abstract", paper.getAbstractText());
        append(parts, "section", chunk.getSectionTitle());
        append(parts, "source", chunk.getSourceKind());
        append(parts, "evidence", chunk.getEvidenceRole());
        append(parts, "text", chunk.getTextContent());
        return String.join("\n", parts);
    }

    private static String paperSearchText(LitSearchPaperDocument source, Paper paper) {
        List<String> parts = new ArrayList<>();
        append(parts, "title", source.title());
        append(parts, "filename", paper.getOriginalFilename());
        append(parts, "abstract", source.abstractText());
        append(parts, "corpusid", source.paperId());
        return String.join("\n", parts);
    }

    private static void append(List<String> parts, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        parts.add(label + ": " + value.trim());
    }

    private static String paperId(LitSearchPaperDocument source) {
        return DATASET_PREFIX + source.paperId();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static float[] placeholderVector() {
        float[] vector = new float[VECTOR_DIMS];
        vector[0] = 1.0f;
        return vector;
    }

    public record Options(
            String userId,
            String orgTag,
            boolean isPublic,
            int maxChunkCharacters,
            String evalSplit,
            int indexBatchSize
    ) {
        public Options(String userId, String orgTag, boolean isPublic, int maxChunkCharacters) {
            this(userId, orgTag, isPublic, maxChunkCharacters, "full", DEFAULT_INDEX_BATCH_SIZE);
        }

        public Options(String userId,
                       String orgTag,
                       boolean isPublic,
                       int maxChunkCharacters,
                       String evalSplit) {
            this(userId, orgTag, isPublic, maxChunkCharacters, evalSplit, DEFAULT_INDEX_BATCH_SIZE);
        }

        public Options {
            userId = userId == null || userId.isBlank() ? "eval-litsearch-user" : userId;
            orgTag = orgTag == null || orgTag.isBlank() ? "eval-litsearch" : orgTag;
            maxChunkCharacters = maxChunkCharacters <= 0 ? 1800 : maxChunkCharacters;
            evalSplit = evalSplit == null || evalSplit.isBlank() ? "full" : evalSplit;
            indexBatchSize = indexBatchSize <= 0 ? DEFAULT_INDEX_BATCH_SIZE : indexBatchSize;
        }

        public static Options defaults() {
            return new Options(
                    "eval-litsearch-user",
                    "eval-litsearch",
                    true,
                    1800,
                    "full",
                    DEFAULT_INDEX_BATCH_SIZE
            );
        }
    }

    public record ImportSummary(
            int paperCount,
            int chunkCount
    ) {
    }
}
