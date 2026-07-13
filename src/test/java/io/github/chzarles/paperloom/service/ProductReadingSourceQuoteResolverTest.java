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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingSourceQuoteResolverTest {

    @Mock
    private ConversationSourceQuoteRepository conversationSourceQuoteRepository;
    @Mock
    private PaperSourceQuoteRepository sourceQuoteRepository;
    @Mock
    private PaperReadingModelRepository modelRepository;
    @Mock
    private PaperRepository paperRepository;

    private ProductReadingSourceQuoteResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resolver = new ProductReadingSourceQuoteResolver(
                conversationSourceQuoteRepository,
                sourceQuoteRepository,
                modelRepository,
                paperRepository
        );
    }

    @Test
    void resolvesRegisteredCurrentReadyQuoteWithPaperIdentity() {
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_abc"
        )).thenReturn(Optional.of(conversationQuote()));
        when(sourceQuoteRepository.findFirstBySourceQuoteRef("source_quote_abc"))
                .thenReturn(Optional.of(sourceQuote("model-v1")));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-1"))
                .thenReturn(Optional.of(model("model-v1", PaperReadingModelStatus.READING_MODEL_READY)));
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-1"))
                .thenReturn(Optional.of(paper()));

        ProductReadingSourceQuoteResolver.Resolution resolution =
                resolver.resolveRegisteredCurrentQuote("conversation-1", "source_quote_abc");

        assertTrue(resolution.ok());
        assertEquals(ProductReadingSourceQuoteResolver.STATUS_OK, resolution.status());
        assertTrue(resolution.sourceQuote().isPresent());
        assertTrue(resolution.paper().isPresent());
        assertEquals("paper-1", resolution.sourceQuote().get().getPaperId());
        assertEquals("Readable Paper", resolution.paper().get().getPaperTitle());
    }

    @Test
    void rejectsQuoteNotRegisteredForConversation() {
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_abc"
        )).thenReturn(Optional.empty());

        ProductReadingSourceQuoteResolver.Resolution resolution =
                resolver.resolveRegisteredCurrentQuote("conversation-1", "source_quote_abc");

        assertEquals(ProductReadingSourceQuoteResolver.STATUS_NOT_IN_CONVERSATION, resolution.status());
        assertTrue(resolution.sourceQuote().isEmpty());
        assertTrue(resolution.paper().isEmpty());
        verify(sourceQuoteRepository, never()).findFirstBySourceQuoteRef(anyString());
        verify(modelRepository, never()).findFirstByPaperIdAndIsCurrentTrue(anyString());
        verify(paperRepository, never()).findFirstByPaperIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void rejectsRegisteredQuoteMissingFromSourceQuoteTable() {
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_missing"
        )).thenReturn(Optional.of(conversationQuote()));
        when(sourceQuoteRepository.findFirstBySourceQuoteRef("source_quote_missing"))
                .thenReturn(Optional.empty());

        ProductReadingSourceQuoteResolver.Resolution resolution =
                resolver.resolveRegisteredCurrentQuote("conversation-1", "source_quote_missing");

        assertEquals(ProductReadingSourceQuoteResolver.STATUS_NOT_FOUND, resolution.status());
        assertTrue(resolution.sourceQuote().isEmpty());
        assertTrue(resolution.paper().isEmpty());
        verify(modelRepository, never()).findFirstByPaperIdAndIsCurrentTrue(anyString());
        verify(paperRepository, never()).findFirstByPaperIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void rejectsQuoteWhenCurrentReadyModelIsMissing() {
        registeredQuote("source_quote_abc", sourceQuote("model-v1"));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-1")).thenReturn(Optional.empty());

        ProductReadingSourceQuoteResolver.Resolution resolution =
                resolver.resolveRegisteredCurrentQuote("conversation-1", "source_quote_abc");

        assertEquals(ProductReadingSourceQuoteResolver.STATUS_UNAVAILABLE, resolution.status());
        assertTrue(resolution.sourceQuote().isEmpty());
        assertTrue(resolution.paper().isEmpty());
        verify(paperRepository, never()).findFirstByPaperIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void rejectsQuoteWhenCurrentModelIsNotReady() {
        registeredQuote("source_quote_abc", sourceQuote("model-v1"));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-1"))
                .thenReturn(Optional.of(model("model-v1", PaperReadingModelStatus.READING_MODEL_FAILED)));

        ProductReadingSourceQuoteResolver.Resolution resolution =
                resolver.resolveRegisteredCurrentQuote("conversation-1", "source_quote_abc");

        assertEquals(ProductReadingSourceQuoteResolver.STATUS_UNAVAILABLE, resolution.status());
        assertTrue(resolution.sourceQuote().isEmpty());
        assertTrue(resolution.paper().isEmpty());
        verify(paperRepository, never()).findFirstByPaperIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void rejectsQuoteWhenStoredQuoteVersionIsStale() {
        registeredQuote("source_quote_abc", sourceQuote("model-v1"));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-1"))
                .thenReturn(Optional.of(model("model-v2", PaperReadingModelStatus.READING_MODEL_READY)));

        ProductReadingSourceQuoteResolver.Resolution resolution =
                resolver.resolveRegisteredCurrentQuote("conversation-1", "source_quote_abc");

        assertEquals(ProductReadingSourceQuoteResolver.STATUS_UNAVAILABLE, resolution.status());
        assertTrue(resolution.sourceQuote().isEmpty());
        assertTrue(resolution.paper().isEmpty());
        verify(paperRepository, never()).findFirstByPaperIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void rejectsQuoteWhenPaperIdentityCannotBeLoaded() {
        registeredQuote("source_quote_abc", sourceQuote("model-v1"));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-1"))
                .thenReturn(Optional.of(model("model-v1", PaperReadingModelStatus.READING_MODEL_READY)));
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-1"))
                .thenReturn(Optional.empty());

        ProductReadingSourceQuoteResolver.Resolution resolution =
                resolver.resolveRegisteredCurrentQuote("conversation-1", "source_quote_abc");

        assertEquals(ProductReadingSourceQuoteResolver.STATUS_UNAVAILABLE, resolution.status());
        assertTrue(resolution.sourceQuote().isEmpty());
        assertTrue(resolution.paper().isEmpty());
    }

    private void registeredQuote(String sourceQuoteRef, PaperSourceQuote quote) {
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                sourceQuoteRef
        )).thenReturn(Optional.of(conversationQuote()));
        when(sourceQuoteRepository.findFirstBySourceQuoteRef(sourceQuoteRef)).thenReturn(Optional.of(quote));
    }

    private ConversationSourceQuote conversationQuote() {
        ConversationSourceQuote quote = new ConversationSourceQuote();
        quote.setConversationId("conversation-1");
        quote.setSourceQuoteRef("source_quote_abc");
        quote.setFirstSeenTurnId("generation-1");
        quote.setUserId("7");
        return quote;
    }

    private PaperSourceQuote sourceQuote(String modelVersion) {
        PaperSourceQuote quote = new PaperSourceQuote();
        quote.setSourceQuoteRef("source_quote_abc");
        quote.setPaperId("paper-1");
        quote.setModelVersion(modelVersion);
        quote.setLocationRef("page_ref_3");
        quote.setLocationType("PAGE");
        quote.setPageNumber(3);
        quote.setSectionTitle("Method");
        quote.setContentKind("TEXT");
        quote.setContent("A precise quote.");
        return quote;
    }

    private PaperReadingModel model(String modelVersion, PaperReadingModelStatus status) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-1");
        model.setModelVersion(modelVersion);
        model.setModelStatus(status);
        model.setCurrent(true);
        return model;
    }

    private Paper paper() {
        Paper paper = new Paper();
        paper.setPaperId("paper-1");
        paper.setPaperTitle("Readable Paper");
        paper.setOriginalFilename("readable.pdf");
        return paper;
    }
}
