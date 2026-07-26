ALTER TABLE conversations
    ADD COLUMN generation_id VARCHAR(64) NULL,
    ADD COLUMN answer_slot_id BIGINT NULL,
    ADD COLUMN answer_revision INT NOT NULL DEFAULT 1,
    ADD COLUMN current_revision BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN forked_from_conversation_record_id BIGINT NULL,
    ADD COLUMN retry_kind VARCHAR(64) NULL,
    ADD COLUMN retry_reason VARCHAR(255) NULL,
    ADD COLUMN retry_of_generation_id VARCHAR(64) NULL;

UPDATE conversations
SET answer_slot_id = id,
    answer_revision = 1,
    current_revision = TRUE
WHERE answer_slot_id IS NULL;

CREATE INDEX idx_conversations_answer_slot
    ON conversations(answer_slot_id, answer_revision);

CREATE INDEX idx_conversations_current_revision
    ON conversations(user_id, conversation_id, current_revision, timestamp);

CREATE INDEX idx_conversations_generation
    ON conversations(generation_id);
