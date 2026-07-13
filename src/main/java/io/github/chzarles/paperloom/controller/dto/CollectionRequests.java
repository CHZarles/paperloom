package io.github.chzarles.paperloom.controller.dto;

import java.util.List;

public final class CollectionRequests {
    private CollectionRequests() {
    }

    public record CreateCollectionRequest(String name, String description, String visibility, String orgTag) {
    }

    public record UpdateCollectionRequest(String name, String description, String visibility, String orgTag) {
    }

    public record AddCollectionPapersRequest(List<String> paperIds) {
    }
}
