package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.PaperConversationReference;
import io.github.chzarles.paperloom.repository.PaperConversationReferenceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ConversationReferenceRegistry {

    private final PaperConversationReferenceRepository referenceRepository;
    private final ObjectMapper objectMapper;

    public ConversationReferenceRegistry(PaperConversationReferenceRepository referenceRepository,
                                         ObjectMapper objectMapper) {
        this.referenceRepository = referenceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaperConversationReference save(ReferenceInput input) {
        ReferenceInput safeInput = input == null
                ? new ReferenceInput("", "", "", "", null, "", Map.of(), Map.of())
                : input;
        String conversationId = required(safeInput.conversationId(), "conversationId");
        String refId = required(safeInput.refId(), "refId");
        PaperConversationReference reference = referenceRepository.findByConversationIdAndRefId(conversationId, refId)
                .orElseGet(PaperConversationReference::new);
        reference.setConversationId(conversationId);
        reference.setScopeSnapshotId(blankToNull(safeInput.scopeSnapshotId()));
        reference.setTurnId(blankToNull(safeInput.turnId()));
        reference.setRefId(refId);
        reference.setRefType(safeInput.refType() == null
                ? PaperConversationReference.RefType.EVIDENCE
                : safeInput.refType());
        reference.setSourceEntityId(blankToNull(safeInput.sourceEntityId()));
        reference.setSourcePayloadJson(writeJson(safeInput.sourcePayload()));
        reference.setDisplayPayloadJson(writeJson(safeInput.displayPayload()));
        return referenceRepository.save(reference);
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedReference> resolve(String conversationId,
                                               String scopeSnapshotId,
                                               String refId,
                                               PaperConversationReference.RefType... allowedTypes) {
        String safeConversationId = blankToNull(conversationId);
        String safeRefId = blankToNull(refId);
        if (safeConversationId == null || safeRefId == null) {
            return Optional.empty();
        }
        return referenceRepository.findByConversationIdAndRefId(safeConversationId, safeRefId)
                .filter(reference -> scopeMatches(reference, scopeSnapshotId))
                .filter(reference -> typeAllowed(reference.getRefType(), allowedTypes))
                .map(this::toResolvedReference);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception exception) {
            throw new CustomException("Reference payload serialization failed", HttpStatus.BAD_REQUEST);
        }
    }

    private ResolvedReference toResolvedReference(PaperConversationReference reference) {
        return new ResolvedReference(
                reference.getConversationId(),
                reference.getScopeSnapshotId(),
                reference.getTurnId(),
                reference.getRefId(),
                reference.getRefType(),
                reference.getSourceEntityId(),
                readJson(reference.getSourcePayloadJson()),
                readJson(reference.getDisplayPayloadJson())
        );
    }

    private Map<String, Object> readJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            return payload == null ? Map.of() : payload;
        } catch (Exception exception) {
            throw new CustomException("Reference payload is invalid", HttpStatus.CONFLICT);
        }
    }

    private static Map<String, Object> immutableMapAllowingNullValues(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        payload.forEach((key, value) -> copied.put(key, immutableValueAllowingNullValues(value)));
        return Collections.unmodifiableMap(copied);
    }

    private static Object immutableValueAllowingNullValues(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, nestedValue) ->
                    copied.put(String.valueOf(key), immutableValueAllowingNullValues(nestedValue)));
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            for (Object item : list) {
                copied.add(immutableValueAllowingNullValues(item));
            }
            return Collections.unmodifiableList(copied);
        }
        return value;
    }

    private boolean scopeMatches(PaperConversationReference reference, String scopeSnapshotId) {
        String requested = blankToNull(scopeSnapshotId);
        String stored = blankToNull(reference.getScopeSnapshotId());
        return requested == null || stored == null || requested.equals(stored);
    }

    private boolean typeAllowed(PaperConversationReference.RefType refType,
                                PaperConversationReference.RefType[] allowedTypes) {
        if (allowedTypes == null || allowedTypes.length == 0) {
            return true;
        }
        for (PaperConversationReference.RefType allowedType : allowedTypes) {
            if (allowedType == refType) {
                return true;
            }
        }
        return false;
    }

    private String required(String value, String name) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            throw new CustomException("Missing reference field: " + name, HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ReferenceInput(
            String conversationId,
            String scopeSnapshotId,
            String turnId,
            String refId,
            PaperConversationReference.RefType refType,
            String sourceEntityId,
            Map<String, Object> sourcePayload,
            Map<String, Object> displayPayload
    ) {
    }

    public record ResolvedReference(
            String conversationId,
            String scopeSnapshotId,
            String turnId,
            String refId,
            PaperConversationReference.RefType refType,
            String sourceEntityId,
            Map<String, Object> sourcePayload,
            Map<String, Object> displayPayload
    ) {
        public ResolvedReference {
            conversationId = conversationId == null ? "" : conversationId.trim();
            scopeSnapshotId = scopeSnapshotId == null ? "" : scopeSnapshotId.trim();
            turnId = turnId == null ? "" : turnId.trim();
            refId = refId == null ? "" : refId.trim();
            refType = refType == null ? PaperConversationReference.RefType.EVIDENCE : refType;
            sourceEntityId = sourceEntityId == null ? "" : sourceEntityId.trim();
            sourcePayload = immutableMapAllowingNullValues(sourcePayload);
            displayPayload = immutableMapAllowingNullValues(displayPayload);
        }
    }
}
