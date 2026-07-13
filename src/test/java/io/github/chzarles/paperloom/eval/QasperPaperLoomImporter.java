package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.entity.PaperChunkDocument;
import io.github.chzarles.paperloom.entity.PaperSearchDocument;
import io.github.chzarles.paperloom.eval.model.EvalChunk;
import io.github.chzarles.paperloom.eval.model.EvalPaper;
import io.github.chzarles.paperloom.eval.repository.EvalChunkRepository;
import io.github.chzarles.paperloom.eval.repository.EvalPaperRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class QasperPaperLoomImporter {

    private static final String CORPUS = "qasper";
    private static final String DATASET_PREFIX = CORPUS + ":";
    private static final String PARSER_NAME = "qasper";
    private static final String PARSER_VERSION = "v0.3-structured-text";
    private static final String MODEL_VERSION = "eval:qasper:no-embedding";
    private static final int VECTOR_DIMS = 2048;

    private final EvalPaperRepository evalPaperRepository;
    private final EvalChunkRepository evalChunkRepository;
    private final EvalCorpusIndexService evalCorpusIndexService;

    public QasperPaperLoomImporter(EvalPaperRepository evalPaperRepository,
                                   EvalChunkRepository evalChunkRepository,
                                   EvalCorpusIndexService evalCorpusIndexService) {
        this.evalPaperRepository = evalPaperRepository;
        this.evalChunkRepository = evalChunkRepository;
        this.evalCorpusIndexService = evalCorpusIndexService;
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
            EvalPaper paper = toPaper(rawPaperId, importedPaperId, paperChunks, effectiveOptions);
            List<EvalChunk> storedChunks = toChunks(importedPaperId, paperChunks, effectiveOptions);
            evalPaperRepository.save(paper);
            evalChunkRepository.saveAll(storedChunks);
            paperDocuments.add(toPaperSearchDocument(paper));
            chunkDocuments.addAll(toChunkDocuments(paper, storedChunks));
            chunkCount += storedChunks.size();
        }
        if (!paperDocuments.isEmpty()) {
            evalCorpusIndexService.bulkIndexPaperSearch(CORPUS, paperDocuments);
        }
        if (!chunkDocuments.isEmpty()) {
            evalCorpusIndexService.bulkIndexChunks(CORPUS, chunkDocuments);
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

    private EvalPaper toPaper(String rawPaperId,
                              String importedPaperId,
                              List<PaperPageChunk> chunks,
                              Options options) {
        PaperPageChunk first = chunks.get(0);
        EvalPaper paper = new EvalPaper();
        paper.setCorpus(CORPUS);
        paper.setSplit(options.evalSplit());
        paper.setExternalPaperId(rawPaperId);
        paper.setPaperId(importedPaperId);
        paper.setTitle(first.paperTitle());
        paper.setAbstractText(abstractText(chunks));
        paper.setArxivId(rawPaperId);
        paper.setSourceJson(originalFilename(first, importedPaperId));
        return paper;
    }

    private List<EvalChunk> toChunks(String importedPaperId,
                                     List<PaperPageChunk> sourceChunks,
                                     Options options) {
        List<EvalChunk> chunks = new ArrayList<>();
        int fallbackChunkId = 1;
        for (PaperPageChunk source : sourceChunks) {
            EvalChunk chunk = new EvalChunk();
            chunk.setCorpus(CORPUS);
            chunk.setSplit(options.evalSplit());
            chunk.setPaperId(importedPaperId);
            chunk.setChunkId(source.chunkId() == null ? fallbackChunkId : source.chunkId());
            chunk.setTextContent(source.text());
            chunk.setRetrievalTextContent(retrievalText(source.paperTitle(), source.sectionTitle(), source.sourceKind(), evidenceRole(source.sectionTitle()), source.text()));
            chunk.setPageNumber(source.pageNumber());
            chunk.setSectionTitle(source.sectionTitle());
            chunk.setSourceKind(source.sourceKind());
            chunk.setEvidenceRole(evidenceRole(source.sectionTitle()));
            chunk.setSourceJson(originalFilename(source, importedPaperId));
            chunks.add(chunk);
            fallbackChunkId++;
        }
        return chunks;
    }

    private PaperSearchDocument toPaperSearchDocument(EvalPaper paper) {
        PaperSearchDocument document = new PaperSearchDocument();
        document.setId(paper.getPaperId());
        document.setPaperId(paper.getPaperId());
        document.setPaperTitle(paper.getTitle());
        document.setOriginalFilename(paper.getSourceJson());
        document.setAbstractText(paper.getAbstractText());
        document.setArxivId(paper.getArxivId());
        document.setSearchText(paperSearchText(paper));
        document.setUserId("eval-qasper-user");
        document.setOrgTag("eval-qasper");
        document.setPublic(true);
        return document;
    }

    private List<PaperChunkDocument> toChunkDocuments(EvalPaper paper, List<EvalChunk> chunks) {
        List<PaperChunkDocument> documents = new ArrayList<>();
        for (EvalChunk chunk : chunks) {
            PaperChunkDocument document = new PaperChunkDocument(
                    UUID.randomUUID().toString(),
                    chunk.getPaperId(),
                    chunk.getChunkId(),
                    chunk.getTextContent(),
                    chunk.getPageNumber(),
                    chunk.getSectionTitle(),
                    "PARAGRAPH",
                    chunk.getSectionTitle(),
                    1,
                    null,
                    PARSER_NAME,
                    PARSER_VERSION,
                    chunk.getSourceKind(),
                    null,
                    null,
                    null,
                    chunk.getEvidenceRole(),
                    placeholderVector(),
                    MODEL_VERSION,
                    "eval-qasper-user",
                    "eval-qasper",
                    true
            );
            document.setRetrievalTextContent(retrievalText(paper, chunk));
            documents.add(document);
        }
        return documents;
    }

    private void clearExistingPaper(String paperId) {
        evalCorpusIndexService.deleteByPaperId(CORPUS, paperId);
        evalChunkRepository.deleteByCorpusAndPaperId(CORPUS, paperId);
        evalPaperRepository.deleteByCorpusAndPaperId(CORPUS, paperId);
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

    private static String retrievalText(EvalPaper paper, EvalChunk chunk) {
        return retrievalText(paper.getTitle(), chunk.getSectionTitle(), chunk.getSourceKind(), chunk.getEvidenceRole(), chunk.getTextContent());
    }

    private static String retrievalText(String title,
                                        String sectionTitle,
                                        String sourceKind,
                                        String evidenceRole,
                                        String text) {
        List<String> parts = new ArrayList<>();
        append(parts, "title", title);
        append(parts, "section", sectionTitle);
        append(parts, "source", sourceKind);
        append(parts, "evidence", evidenceRole);
        append(parts, "text", text);
        return String.join("\n", parts);
    }

    private static String paperSearchText(EvalPaper paper) {
        List<String> parts = new ArrayList<>();
        append(parts, "title", paper.getTitle());
        append(parts, "filename", paper.getSourceJson());
        append(parts, "abstract", paper.getAbstractText());
        append(parts, "arxiv", paper.getArxivId());
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
            String evalSplit,
            String paperIdPrefix
    ) {
        public Options(String evalSplit) {
            this(evalSplit, DATASET_PREFIX);
        }

        public Options {
            evalSplit = evalSplit == null || evalSplit.isBlank() ? "dev" : evalSplit;
            paperIdPrefix = paperIdPrefix == null || paperIdPrefix.isBlank() ? DATASET_PREFIX : paperIdPrefix;
        }

        public static Options defaults() {
            return new Options("dev", DATASET_PREFIX);
        }
    }

    public record ImportSummary(
            int paperCount,
            int chunkCount
    ) {
    }
}
