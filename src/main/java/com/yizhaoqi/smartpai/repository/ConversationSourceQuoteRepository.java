package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ConversationSourceQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationSourceQuoteRepository extends JpaRepository<ConversationSourceQuote, Long> {
    Optional<ConversationSourceQuote> findFirstByConversationIdAndSourceQuoteRef(String conversationId,
                                                                                 String sourceQuoteRef);
}
