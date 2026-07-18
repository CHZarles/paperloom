package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.QdrantProperties;
import io.github.chzarles.paperloom.model.PaperRetrievalControl;
import io.github.chzarles.paperloom.repository.PaperRetrievalControlRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class RetrievalIndexContractService {

    static final double DEFAULT_AVERAGE_DOCUMENT_LENGTH = 256.0;
    private static final String SCHEMA_VERSION = "sparse-only-v1";
    private static final String PROJECTION_VERSION = "canonical-location-v2";
    private static final String ANALYZER_VERSION = "unicode-nfkc-lower-min2-v1";
    private static final String TERM_ID_VERSION = "sha256-int31-v1";
    private static final String SCORER_VERSION = "bm25-tf-norm-v1";
    private static final String QDRANT_VERSION = "1.15.5";

    private final QdrantProperties properties;
    private final PaperRetrievalControlRepository controlRepository;

    public RetrievalIndexContractService(QdrantProperties properties,
                                         PaperRetrievalControlRepository controlRepository) {
        this.properties = properties;
        this.controlRepository = controlRepository;
    }

    public String activeContract() {
        return controlRepository.findById(PaperRetrievalControl.FULL_REBUILD)
                .map(PaperRetrievalControl::getActiveIndexContract)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> contractFor(DEFAULT_AVERAGE_DOCUMENT_LENGTH));
    }

    public double activeAverageDocumentLength() {
        return controlRepository.findById(PaperRetrievalControl.FULL_REBUILD)
                .map(PaperRetrievalControl::getLexicalAverageDocumentLength)
                .filter(value -> value != null && value > 0)
                .orElse(DEFAULT_AVERAGE_DOCUMENT_LENGTH);
    }

    public synchronized String ensureActiveContract(double suggestedAverageDocumentLength) {
        PaperRetrievalControl control = ensureControlRow();
        if (control.getActiveIndexContract() != null
                && !control.getActiveIndexContract().isBlank()
                && control.getLexicalAverageDocumentLength() != null
                && control.getLexicalAverageDocumentLength() > 0) {
            return control.getActiveIndexContract();
        }
        return activate(control, suggestedAverageDocumentLength);
    }

    public synchronized String activateContract(double averageDocumentLength) {
        return activate(ensureControlRow(), averageDocumentLength);
    }

    public boolean isActive(String contract) {
        return contract != null && contract.equals(activeContract());
    }

    String contractFor(double averageDocumentLength) {
        double safeAverage = averageDocumentLength > 0
                ? averageDocumentLength
                : DEFAULT_AVERAGE_DOCUMENT_LENGTH;
        String average = BigDecimal.valueOf(safeAverage)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
        return properties.getCollection()
                + "|schema=" + SCHEMA_VERSION
                + "|projection=" + PROJECTION_VERSION
                + "|analyzer=" + ANALYZER_VERSION
                + "|term-id=" + TERM_ID_VERSION
                + "|scorer=" + SCORER_VERSION
                + "|k1=" + LexicalBm25Encoder.K1
                + "|b=" + LexicalBm25Encoder.B
                + "|avgdl=" + average
                + "|qdrant=" + QDRANT_VERSION
                + "|modifier=idf";
    }

    private String activate(PaperRetrievalControl control, double averageDocumentLength) {
        double safeAverage = averageDocumentLength > 0
                ? averageDocumentLength
                : DEFAULT_AVERAGE_DOCUMENT_LENGTH;
        String contract = contractFor(safeAverage);
        control.setLexicalAverageDocumentLength(safeAverage);
        control.setActiveIndexContract(contract);
        controlRepository.saveAndFlush(control);
        return contract;
    }

    private PaperRetrievalControl ensureControlRow() {
        return controlRepository.findById(PaperRetrievalControl.FULL_REBUILD).orElseGet(() -> {
            PaperRetrievalControl control = new PaperRetrievalControl();
            control.setControlName(PaperRetrievalControl.FULL_REBUILD);
            control.setFullRebuildStatus(PaperRetrievalControl.IDLE);
            try {
                return controlRepository.saveAndFlush(control);
            } catch (DataIntegrityViolationException ignored) {
                return controlRepository.findById(PaperRetrievalControl.FULL_REBUILD).orElseThrow();
            }
        });
    }
}
