package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperConversationReference;
import com.yizhaoqi.smartpai.repository.PaperConversationReferenceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationReferenceRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void savesOpaqueConversationReferenceAsIndependentProductFact() {
        PaperConversationReferenceRepository repository = mock(PaperConversationReferenceRepository.class);
        ConversationReferenceRegistry registry = new ConversationReferenceRegistry(repository, objectMapper);

        registry.save(new ConversationReferenceRegistry.ReferenceInput(
                "conversation-1",
                "scope-1",
                "generation-1",
                "ev_1",
                PaperConversationReference.RefType.EVIDENCE,
                "chunk-1",
                Map.of("paperId", "paper-1", "pageNumber", 3),
                Map.of("paperTitle", "LoRA", "snippet", "Low-rank adaptation")
        ));

        ArgumentCaptor<PaperConversationReference> captor =
                ArgumentCaptor.forClass(PaperConversationReference.class);
        verify(repository).save(captor.capture());
        PaperConversationReference saved = captor.getValue();
        assertEquals("conversation-1", saved.getConversationId());
        assertEquals("scope-1", saved.getScopeSnapshotId());
        assertEquals("generation-1", saved.getTurnId());
        assertEquals("ev_1", saved.getRefId());
        assertEquals(PaperConversationReference.RefType.EVIDENCE, saved.getRefType());
        assertEquals("chunk-1", saved.getSourceEntityId());
        assertNotNull(saved.getSourcePayloadJson());
        assertNotNull(saved.getDisplayPayloadJson());
    }

    @Test
    void resolvesPersistedOpaqueReferencePayload() throws Exception {
        PaperConversationReferenceRepository repository = mock(PaperConversationReferenceRepository.class);
        PaperConversationReference reference = new PaperConversationReference();
        reference.setConversationId("conversation-1");
        reference.setScopeSnapshotId("scope-1");
        reference.setTurnId("generation-1");
        reference.setRefId("citation_generation-1_1");
        reference.setRefType(PaperConversationReference.RefType.CITATION);
        reference.setSourceEntityId("ev_1");
        reference.setSourcePayloadJson(objectMapper.writeValueAsString(Map.of("evidenceRef", "ev_1")));
        reference.setDisplayPayloadJson(objectMapper.writeValueAsString(Map.of(
                "referenceNumber", 1,
                "paperTitle", "LoRA",
                "pageNumber", 3
        )));
        when(repository.findByConversationIdAndRefId("conversation-1", "citation_generation-1_1"))
                .thenReturn(Optional.of(reference));
        ConversationReferenceRegistry registry = new ConversationReferenceRegistry(repository, objectMapper);

        Optional<ConversationReferenceRegistry.ResolvedReference> resolved =
                registry.resolve("conversation-1", "scope-1", "citation_generation-1_1",
                        PaperConversationReference.RefType.CITATION);

        assertTrue(resolved.isPresent());
        assertEquals("citation_generation-1_1", resolved.get().refId());
        assertEquals(PaperConversationReference.RefType.CITATION, resolved.get().refType());
        assertEquals("LoRA", resolved.get().displayPayload().get("paperTitle"));
        assertEquals(1, resolved.get().displayPayload().get("referenceNumber"));
    }

    @Test
    void resolvesPayloadsWithNullMetadataFields() throws Exception {
        PaperConversationReferenceRepository repository = mock(PaperConversationReferenceRepository.class);
        PaperConversationReference reference = new PaperConversationReference();
        reference.setConversationId("conversation-1");
        reference.setScopeSnapshotId("scope-1");
        reference.setTurnId("generation-1");
        reference.setRefId("paper_1");
        reference.setRefType(PaperConversationReference.RefType.PAPER);
        reference.setSourceEntityId("paper-raw-id");
        reference.setSourcePayloadJson(objectMapper.writeValueAsString(Map.of(
                "paperRef", "paper_1",
                "paperId", "paper-raw-id"
        )));
        reference.setDisplayPayloadJson("""
                {
                  "paperRef": "paper_1",
                  "title": "LoRA",
                  "authors": null,
                  "metadata": {
                    "venue": null
                  }
                }
                """);
        when(repository.findByConversationIdAndRefId("conversation-1", "paper_1"))
                .thenReturn(Optional.of(reference));
        ConversationReferenceRegistry registry = new ConversationReferenceRegistry(repository, objectMapper);

        Optional<ConversationReferenceRegistry.ResolvedReference> resolved =
                registry.resolve("conversation-1", "scope-1", "paper_1",
                        PaperConversationReference.RefType.PAPER);

        assertTrue(resolved.isPresent());
        assertTrue(resolved.get().displayPayload().containsKey("authors"));
        assertEquals(null, resolved.get().displayPayload().get("authors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) resolved.get().displayPayload().get("metadata");
        assertTrue(metadata.containsKey("venue"));
        assertEquals(null, metadata.get("venue"));
    }
}
