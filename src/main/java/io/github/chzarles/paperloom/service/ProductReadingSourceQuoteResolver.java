package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.ConversationSourceQuote;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSourceQuote;
import io.github.chzarles.paperloom.repository.ConversationSourceQuoteRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperSourceQuoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ProductReadingSourceQuoteResolver {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NOT_IN_CONVERSATION = "SOURCE_QUOTE_NOT_IN_CONVERSATION";
    public static final String STATUS_NOT_FOUND = "SOURCE_QUOTE_NOT_FOUND";
    public static final String STATUS_UNAVAILABLE = "SOURCE_QUOTE_UNAVAILABLE";

    private final ConversationSourceQuoteRepository conversationSourceQuoteRepository;
    private final PaperSourceQuoteRepository sourceQuoteRepository;
    private final PaperReadingModelRepository modelRepository;
    private final PaperRepository paperRepository;

    public ProductReadingSourceQuoteResolver(ConversationSourceQuoteRepository conversationSourceQuoteRepository,
                                             PaperSourceQuoteRepository sourceQuoteRepository,
                                             PaperReadingModelRepository modelRepository,
                                             PaperRepository paperRepository) {
        this.conversationSourceQuoteRepository = conversationSourceQuoteRepository;
        this.sourceQuoteRepository = sourceQuoteRepository;
        this.modelRepository = modelRepository;
        this.paperRepository = paperRepository;
    }

    @Transactional(readOnly = true)
    public Resolution resolveRegisteredCurrentQuote(String conversationId, String sourceQuoteRef) {
        String safeConversationId = trim(conversationId);
        String safeSourceQuoteRef = trim(sourceQuoteRef);
        if (safeConversationId.isBlank() || safeSourceQuoteRef.isBlank()) {
            return Resolution.status(STATUS_NOT_IN_CONVERSATION);
        }
        Optional<ConversationSourceQuote> registryRow =
                conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                        safeConversationId,
                        safeSourceQuoteRef
                );
        if (registryRow.isEmpty()) {
            return Resolution.status(STATUS_NOT_IN_CONVERSATION);
        }
        Optional<PaperSourceQuote> quote = sourceQuoteRepository.findFirstBySourceQuoteRef(safeSourceQuoteRef);
        if (quote.isEmpty()) {
            return Resolution.status(STATUS_NOT_FOUND);
        }
        if (!isCurrentReadyQuote(quote.get())) {
            return Resolution.status(STATUS_UNAVAILABLE);
        }
        Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(quote.get().getPaperId());
        if (paper.isEmpty()) {
            return Resolution.status(STATUS_UNAVAILABLE);
        }
        return new Resolution(STATUS_OK, quote, paper);
    }

    private boolean isCurrentReadyQuote(PaperSourceQuote quote) {
        if (quote == null || trim(quote.getPaperId()).isBlank() || trim(quote.getModelVersion()).isBlank()) {
            return false;
        }
        Optional<PaperReadingModel> currentModel = modelRepository.findFirstByPaperIdAndIsCurrentTrue(quote.getPaperId());
        return currentModel
                .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                .filter(model -> trim(model.getModelVersion()).equals(trim(quote.getModelVersion())))
                .isPresent();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record Resolution(
            String status,
            Optional<PaperSourceQuote> sourceQuote,
            Optional<Paper> paper
    ) {
        public Resolution {
            status = status == null || status.isBlank() ? STATUS_UNAVAILABLE : status.trim();
            sourceQuote = sourceQuote == null ? Optional.empty() : sourceQuote;
            paper = paper == null ? Optional.empty() : paper;
        }

        static Resolution status(String status) {
            return new Resolution(status, Optional.empty(), Optional.empty());
        }

        public boolean ok() {
            return STATUS_OK.equals(status) && sourceQuote.isPresent();
        }
    }
}
