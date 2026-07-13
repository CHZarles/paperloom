package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.entity.PaperChunkDocument;
import io.github.chzarles.paperloom.entity.PaperSearchDocument;
import io.github.chzarles.paperloom.eval.model.EvalChunk;
import io.github.chzarles.paperloom.eval.model.EvalPaper;
import io.github.chzarles.paperloom.eval.repository.EvalChunkRepository;
import io.github.chzarles.paperloom.eval.repository.EvalPaperRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LitSearchPaperLoomImporter {

    private static final String CORPUS = "litsearch";
    private static final String DATASET_PREFIX = CORPUS + ":";
    private static final String PARSER_NAME = "litsearch";
    private static final String PARSER_VERSION = "corpus_clean";
    private static final String MODEL_VERSION = "eval:litsearch:no-embedding";
    private static final int VECTOR_DIMS = 2048;
    private static final int DEFAULT_INDEX_BATCH_SIZE = 500;

    private final EvalPaperRepository evalPaperRepository;
    private final EvalChunkRepository evalChunkRepository;
    private final EvalCorpusIndexService evalCorpusIndexService;

    public LitSearchPaperLoomImporter(EvalPaperRepository evalPaperRepository,
                                      EvalChunkRepository evalChunkRepository,
                                      EvalCorpusIndexService evalCorpusIndexService) {
        this.evalPaperRepository = evalPaperRepository;
        this.evalChunkRepository = evalChunkRepository;
        this.evalCorpusIndexService = evalCorpusIndexService;
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
            EvalPaper paper = toEvalPaper(source, options);
            List<EvalChunk> chunks = toChunks(source, paper.getPaperId(), options);
            evalPaperRepository.save(paper);
            evalChunkRepository.saveAll(chunks);
            paperDocuments.add(toPaperSearchDocument(source, paper));
            chunkDocuments.addAll(toChunkDocuments(paper, chunks));
            paperCount++;
            chunkCount += chunks.size();
            flushPaperDocumentsIfNeeded(paperDocuments, options.indexBatchSize());
            flushChunkDocumentsIfNeeded(chunkDocuments, options.indexBatchSize());
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
        evalCorpusIndexService.bulkIndexPaperSearch(CORPUS, List.copyOf(documents));
        documents.clear();
    }

    private void flushChunkDocuments(List<PaperChunkDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        evalCorpusIndexService.bulkIndexChunks(CORPUS, List.copyOf(documents));
        documents.clear();
    }

    private EvalPaper toEvalPaper(LitSearchPaperDocument source, Options options) {
        EvalPaper paper = new EvalPaper();
        paper.setCorpus(CORPUS);
        paper.setSplit(options.evalSplit());
        paper.setExternalPaperId(source.paperId());
        paper.setPaperId(paperId(source));
        paper.setTitle(source.title());
        paper.setAbstractText(source.abstractText());
        paper.setFullText(source.fullPaperText());
        paper.setSourceJson("{\"source\":\"litsearch\",\"externalPaperId\":\"" + escapeJson(source.paperId()) + "\"}");
        return paper;
    }

    private PaperSearchDocument toPaperSearchDocument(LitSearchPaperDocument source, EvalPaper paper) {
        PaperSearchDocument document = new PaperSearchDocument();
        document.setId(paper.getPaperId());
        document.setPaperId(paper.getPaperId());
        document.setPaperTitle(source.title());
        document.setOriginalFilename(paper.getPaperId() + ".json");
        document.setAbstractText(source.abstractText());
        document.setSearchText(paperSearchText(source, paper.getPaperId()));
        document.setUserId("eval-litsearch-user");
        document.setOrgTag("eval-litsearch");
        document.setPublic(true);
        return document;
    }

    private void clearExistingPaper(String paperId) {
        evalCorpusIndexService.deleteByPaperId(CORPUS, paperId);
        evalChunkRepository.deleteByCorpusAndPaperId(CORPUS, paperId);
        evalPaperRepository.deleteByCorpusAndPaperId(CORPUS, paperId);
    }

    private List<EvalChunk> toChunks(LitSearchPaperDocument source, String paperId, Options options) {
        List<EvalChunk> chunks = new ArrayList<>();
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

    private EvalChunk chunk(String paperId,
                            int chunkId,
                            String text,
                            String sectionTitle,
                            String evidenceRole,
                            Options options) {
        EvalChunk chunk = new EvalChunk();
        chunk.setCorpus(CORPUS);
        chunk.setSplit(options.evalSplit());
        chunk.setPaperId(paperId);
        chunk.setChunkId(chunkId);
        chunk.setTextContent(text);
        chunk.setRetrievalTextContent(retrievalText(paperId, sectionTitle, evidenceRole, text));
        chunk.setPageNumber(chunkId);
        chunk.setSectionTitle(sectionTitle);
        chunk.setSourceKind("TEXT");
        chunk.setEvidenceRole(evidenceRole);
        chunk.setSourceJson("{\"source\":\"litsearch\"}");
        return chunk;
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
                    "eval-litsearch-user",
                    "eval-litsearch",
                    true
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

    private static String retrievalText(EvalPaper paper, EvalChunk chunk) {
        List<String> parts = new ArrayList<>();
        append(parts, "title", paper.getTitle());
        append(parts, "filename", paper.getPaperId() + ".json");
        append(parts, "abstract", paper.getAbstractText());
        append(parts, "section", chunk.getSectionTitle());
        append(parts, "source", chunk.getSourceKind());
        append(parts, "evidence", chunk.getEvidenceRole());
        append(parts, "text", chunk.getTextContent());
        return String.join("\n", parts);
    }

    private static String retrievalText(String paperId, String sectionTitle, String evidenceRole, String text) {
        List<String> parts = new ArrayList<>();
        append(parts, "paperId", paperId);
        append(parts, "section", sectionTitle);
        append(parts, "evidence", evidenceRole);
        append(parts, "text", text);
        return String.join("\n", parts);
    }

    private static String paperSearchText(LitSearchPaperDocument source, String paperId) {
        List<String> parts = new ArrayList<>();
        append(parts, "title", source.title());
        append(parts, "filename", paperId + ".json");
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

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static float[] placeholderVector() {
        float[] vector = new float[VECTOR_DIMS];
        vector[0] = 1.0f;
        return vector;
    }

    public record Options(
            String evalSplit,
            int maxChunkCharacters,
            int indexBatchSize
    ) {
        public Options {
            evalSplit = evalSplit == null || evalSplit.isBlank() ? "full" : evalSplit;
            maxChunkCharacters = maxChunkCharacters <= 0 ? 1800 : maxChunkCharacters;
            indexBatchSize = indexBatchSize <= 0 ? DEFAULT_INDEX_BATCH_SIZE : indexBatchSize;
        }

        public static Options defaults() {
            return new Options("full", 1800, DEFAULT_INDEX_BATCH_SIZE);
        }
    }

    public record ImportSummary(
            int paperCount,
            int chunkCount
    ) {
    }
}
