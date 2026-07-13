package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperConversationReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperConversationReferenceRepository extends JpaRepository<PaperConversationReference, Long> {

    Optional<PaperConversationReference> findByConversationIdAndRefId(String conversationId, String refId);

    List<PaperConversationReference> findByConversationIdAndRefTypeOrderByCreatedAtAsc(
            String conversationId,
            PaperConversationReference.RefType refType
    );
}
