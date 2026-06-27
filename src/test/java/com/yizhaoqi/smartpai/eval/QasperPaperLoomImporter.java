package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperTextChunk;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class QasperPaperLoomImporter {

    private static final String DATASET_PREFIX = "qasper:";
    private static final String SOURCE_DATASET = "qasper";
    private static final String PARSER_NAME = "qasper";
    private static final String PARSER_VERSION = "v0.3-structured-text";
    private static final String MODEL_VERSION = "eval:qasper:no-embedding";
    private static final int VECTOR_DIMS = 2048;

    private final PaperRepository paperRepository;
    private final PaperTextChunkRepository paperTextChunkRepository;
    private final ElasticsearchService elasticsearchService;

    public QasperPaperLoomImporter(PaperRepository paperRepository,
                                   PaperTextChunkRepository paperTextChunkRepository,
                                   ElasticsearchService elasticsearchService) {
        this.paperRepository = paperRepository;
        this.paperTextChunkRepository = paperTextChunkRepository;
        this.elasticsearchService = elasticsearchService;
    }

    public ImportSummary importChunks(List<PaperPageChunk> chunks, Options options) {
        Options effectiveOptions = options == null ? Options.defaults() : options;
        Map<String, List<PaperPageChunk>> chunksByPaper = chunksByPaper(chunks);
        List<PaperSearchDocument> paperDocuments = new ArrayList<>();
        List<PaperChunkDocument> chunkDocuments = new ArrayList<>();
        int chunkCount = 0;
        for (Map.Entry<String, List<PaperPageChunk>> entry : chunksByPaper.entrySet()) {
            String rawPaperId = rawPaperId(entry.getKey());
            String importedPaperId = importedPaperId(rawPaperId, effectiveOptions);
            List<PaperPageChunk> paperChunks = entry.getValue();
            clearExistingPaper(importedPaperId);
            Paper paper = toPaper(rawPaperId, importedPaperId, paperChunks, effectiveOptions);
            List<PaperTextChunk> storedChunks = toChunks(importedPaperId, paperChunks, effectiveOptions);
            paper.setEstimatedChunkCount(storedChunks.size());
            paper.setActualChunkCount(storedChunks.size());
            paperRepository.save(paper);
            paperTextChunkRepository.saveAll(storedChunks);
            paperDocuments.add(PaperSearchDocument.from(
                    paper,
                    effectiveOptions.userId(),
                    effectiveOptions.orgTag(),
                    effectiveOptions.isPublic()
            ));
            chunkDocuments.addAll(toChunkDocuments(paper, storedChunks, effectiveOptions));
            chunkCount += storedChunks.size();
        }
        if (!paperDocuments.isEmpty()) {
            elasticsearchService.bulkIndexPaperSearch(paperDocuments);
        }
        if (!chunkDocuments.isEmpty()) {
            elasticsearchService.bulkIndex(chunkDocuments);
        }
        return new ImportSummary(paperDocuments.size(), chunkCount);
    }

    public static List<RagBenchmarkCase> rewriteCasesToImportedPaperIds(List<RagBenchmarkCase> cases,
                                                                        Options options) {
        Options effectiveOptions = options == null ? Options.defaults() : options;
        return (cases == null ? List.<RagBenchmarkCase>of() : cases).stream()
                .map(testCase -> rewriteCase(testCase, effectiveOptions))
                .toList();
    }

    static List<RagBenchmarkCase> rewriteCasesToImportedPaperIds(List<RagBenchmarkCase> cases,
                                                                 Options options,
                                                                 Set<String> allowedRawPaperIds) {
        Set<String> allowed = allowedRawPaperIds == null ? Set.of() : allowedRawPaperIds;
        return (cases == null ? List.<RagBenchmarkCase>of() : cases).stream()
                .filter(testCase -> allowed.isEmpty() || allowed.containsAll(rawPaperIds(testCase)))
                .map(testCase -> rewriteCase(testCase, options == null ? Options.defaults() : options))
                .toList();
    }

    private static RagBenchmarkCase rewriteCase(RagBenchmarkCase testCase, Options options) {
        List<String> scopePaperIds = testCase.scope() == null
                ? List.of()
                : testCase.scope().paperIds().stream()
                .map(paperId -> importedPaperId(rawPaperId(paperId), options))
                .toList();
        List<String> expectedPaperIds = testCase.expectedPaperIds().stream()
                .map(paperId -> importedPaperId(rawPaperId(paperId), options))
                .toList();
        RagBenchmarkCase.Scope scope = new RagBenchmarkCase.Scope(
                scopePaperIds,
                testCase.scope() == null ? List.of() : testCase.scope().paperTitles()
        );
        return new RagBenchmarkCase(
                testCase.id(),
                testCase.query(),
                testCase.language(),
                testCase.taskType(),
                testCase.scopeMode(),
                scope,
                testCase.expectedRoute(),
                testCase.requiredAnswerRegex(),
                testCase.requiredEvidenceRegex(),
                testCase.forbiddenAnswerRegex(),
                testCase.forbiddenEvidenceRegex(),
                expectedPaperIds,
                testCase.requiresCitation()
        );
    }

    private static Set<String> rawPaperIds(RagBenchmarkCase testCase) {
        LinkedHashSet<String> paperIds = new LinkedHashSet<>();
        if (testCase.scope() != null) {
            testCase.scope().paperIds().stream()
                    .map(QasperPaperLoomImporter::rawPaperId)
                    .forEach(paperIds::add);
        }
        testCase.expectedPaperIds().stream()
                .map(QasperPaperLoomImporter::rawPaperId)
                .forEach(paperIds::add);
        return paperIds;
    }

    private Map<String, List<PaperPageChunk>> chunksByPaper(List<PaperPageChunk> chunks) {
        Map<String, List<PaperPageChunk>> chunksByPaper = new LinkedHashMap<>();
        for (PaperPageChunk chunk : chunks == null ? List.<PaperPageChunk>of() : chunks) {
            if (chunk.paperId().isBlank() || chunk.text().isBlank()) {
                continue;
            }
            chunksByPaper.computeIfAbsent(rawPaperId(chunk.paperId()), ignored -> new ArrayList<>()).add(chunk);
        }
        return chunksByPaper;
    }

    private Paper toPaper(String rawPaperId,
                          String importedPaperId,
                          List<PaperPageChunk> chunks,
                          Options options) {
        PaperPageChunk first = chunks.get(0);
        Paper paper = new Paper();
        paper.setPaperId(importedPaperId);
        paper.setOriginalFilename(originalFilename(first, importedPaperId));
        paper.setPaperTitle(first.paperTitle());
        paper.setAbstractText(abstractText(chunks));
        paper.setArxivId(rawPaperId);
        paper.setSourceDataset(SOURCE_DATASET);
        paper.setExternalCorpusId(rawPaperId);
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

    private List<PaperTextChunk> toChunks(String importedPaperId,
                                          List<PaperPageChunk> sourceChunks,
                                          Options options) {
        List<PaperTextChunk> chunks = new ArrayList<>();
        int fallbackChunkId = 1;
        for (PaperPageChunk source : sourceChunks) {
            PaperTextChunk chunk = new PaperTextChunk();
            chunk.setPaperId(importedPaperId);
            chunk.setChunkId(source.chunkId() == null ? fallbackChunkId : source.chunkId());
            chunk.setTextContent(source.text());
            chunk.setPageNumber(source.pageNumber());
            chunk.setAnchorText(source.sectionTitle());
            chunk.setElementType("PARAGRAPH");
            chunk.setSectionTitle(source.sectionTitle());
            chunk.setSectionLevel(1);
            chunk.setParserName(PARSER_NAME);
            chunk.setParserVersion(PARSER_VERSION);
            chunk.setSourceKind(source.sourceKind());
            chunk.setTableId(source.tableId());
            chunk.setFigureId(source.figureId());
            chunk.setEvidenceRole(evidenceRole(source.sectionTitle()));
            chunk.setModelVersion(MODEL_VERSION);
            chunk.setUserId(options.userId());
            chunk.setOrgTag(options.orgTag());
            chunk.setPublic(options.isPublic());
            chunks.add(chunk);
            fallbackChunkId++;
        }
        return chunks;
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

    private void clearExistingPaper(String paperId) {
        elasticsearchService.deleteByPaperId(paperId);
        paperTextChunkRepository.deleteByPaperId(paperId);
        paperRepository.deleteByPaperId(paperId);
    }

    private static String originalFilename(PaperPageChunk chunk, String importedPaperId) {
        if (chunk.originalFilename() != null && !chunk.originalFilename().isBlank()) {
            return chunk.originalFilename();
        }
        return importedPaperId + ".json";
    }

    private static String abstractText(List<PaperPageChunk> chunks) {
        String abstractText = chunks.stream()
                .filter(chunk -> "abstract".equalsIgnoreCase(chunk.sectionTitle()))
                .map(PaperPageChunk::text)
                .reduce("", (left, right) -> (left + " " + right).trim());
        if (!abstractText.isBlank()) {
            return truncate(abstractText, 2000);
        }
        return truncate(chunks.isEmpty() ? "" : chunks.get(0).text(), 2000);
    }

    private static String evidenceRole(String sectionTitle) {
        return "abstract".equalsIgnoreCase(sectionTitle) ? "PAPER_METADATA" : "FULL_TEXT";
    }

    private static String retrievalText(Paper paper, PaperTextChunk chunk) {
        List<String> parts = new ArrayList<>();
        append(parts, "title", paper.getPaperTitle());
        append(parts, "filename", paper.getOriginalFilename());
        append(parts, "abstract", paper.getAbstractText());
        append(parts, "arxiv", paper.getArxivId());
        append(parts, "section", chunk.getSectionTitle());
        append(parts, "source", chunk.getSourceKind());
        append(parts, "evidence", chunk.getEvidenceRole());
        append(parts, "text", chunk.getTextContent());
        return String.join("\n", parts);
    }

    private static void append(List<String> parts, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        parts.add(label + ": " + value.trim());
    }

    private static String importedPaperId(String rawPaperId, Options options) {
        String raw = rawPaperId(rawPaperId);
        return options.paperIdPrefix() + raw;
    }

    private static String rawPaperId(String paperId) {
        if (paperId == null) {
            return "";
        }
        return paperId.startsWith(DATASET_PREFIX) ? paperId.substring(DATASET_PREFIX.length()) : paperId;
    }

    private static float[] placeholderVector() {
        float[] vector = new float[VECTOR_DIMS];
        vector[0] = 1.0f;
        return vector;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record Options(
            String userId,
            String orgTag,
            boolean isPublic,
            String evalSplit,
            String paperIdPrefix
    ) {
        public Options(String userId, String orgTag, boolean isPublic, String evalSplit) {
            this(userId, orgTag, isPublic, evalSplit, DATASET_PREFIX);
        }

        public Options {
            userId = userId == null || userId.isBlank() ? "eval-qasper-user" : userId;
            orgTag = orgTag == null || orgTag.isBlank() ? "eval-qasper" : orgTag;
            evalSplit = evalSplit == null || evalSplit.isBlank() ? "dev" : evalSplit;
            paperIdPrefix = paperIdPrefix == null || paperIdPrefix.isBlank() ? DATASET_PREFIX : paperIdPrefix;
        }

        public static Options defaults() {
            return new Options("eval-qasper-user", "eval-qasper", true, "dev", DATASET_PREFIX);
        }
    }

    public record ImportSummary(
            int paperCount,
            int chunkCount
    ) {
    }
}
