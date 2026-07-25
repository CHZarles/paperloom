package io.github.chzarles.paperloom.service;

import java.util.Map;

public record RankedLocationCandidate(String locationRef,
                                       Map<String, Object> payload,
                                       double lexicalScore,
                                       double denseScore,
                                       double fusedScore) {
    public RankedLocationCandidate {
        locationRef = locationRef == null ? "" : locationRef;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
