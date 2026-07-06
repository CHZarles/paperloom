package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.ProductReadingReactProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class ProductReadingConversationService {

    private final ProductReadingReActHarness readingHarness;
    private final ProductReadingReactProperties properties;

    public ProductReadingConversationService(ProductReadingReActHarness readingHarness,
                                             ProductReadingReactProperties properties) {
        this.readingHarness = readingHarness;
        this.properties = properties == null ? new ProductReadingReactProperties() : properties;
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext) {
        return runTurn(userId, conversationId, generationId, userMessage, lockedScope, modelContext,
                (Consumer<ToolProgressEvent>) null);
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext,
                                     Map<String, Object> effectiveScope) {
        return runTurn(userId, conversationId, generationId, userMessage, lockedScope, modelContext, effectiveScope, null);
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext,
                                     Consumer<ToolProgressEvent> progressListener) {
        return runTurn(userId, conversationId, generationId, userMessage, lockedScope, modelContext, Map.of(), progressListener);
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext,
                                     Map<String, Object> effectiveScope,
                                     Consumer<ToolProgressEvent> progressListener) {
        if (!properties.isEnabled()) {
            return disabledResult();
        }
        if (readingHarness == null) {
            return failed("Product Reading ReAct Phase 1 harness is unavailable.");
        }
        return readingHarness.run(new ProductTurnRequest(
                userId,
                conversationId,
                generationId,
                userMessage,
                lockedScope,
                List.of(),
                Map.of(),
                modelContext,
                progressListener
        ));
    }

    private ProductTurnResult disabledResult() {
        return failed("Product Reading ReAct Phase 1 is disabled.");
    }

    private ProductTurnResult failed(String message) {
        return new ProductTurnResult(
                message,
                new AnswerEnvelope(
                        AnswerType.CLARIFICATION_NEEDED,
                        message,
                        List.of(),
                        List.of(),
                        List.of(message),
                        List.of(),
                        List.of(),
                        ProductStopReason.ANSWER_SCHEMA_INVALID.name()
                ),
                List.of(),
                List.of(),
                ProductStopReason.ANSWER_SCHEMA_INVALID,
                ProductResultStatus.FAILED
        );
    }
}
