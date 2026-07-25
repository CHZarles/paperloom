package io.github.chzarles.paperloom.service;

public interface ReadingLocationRetriever {

    RetrievalCandidates retrieve(LocationRetrievalRequest request);
}
