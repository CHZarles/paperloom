-- Destructive cleanup after the sparse-only lexical Qdrant cutover.
-- Product retrieval no longer has an embedding provider or embedding configuration scope.

DELETE FROM model_provider_configs
WHERE config_scope <> 'llm';

ALTER TABLE model_provider_configs
    DROP COLUMN embedding_dimension;

ALTER TABLE file_upload
    DROP COLUMN estimated_embedding_tokens,
    DROP COLUMN estimated_chunk_count,
    CHANGE COLUMN actual_embedding_tokens retrieval_indexed_token_count BIGINT NULL
        COMMENT 'Lexical retrieval indexed token count',
    CHANGE COLUMN actual_chunk_count retrieval_indexed_location_count INT NULL
        COMMENT 'Lexical retrieval indexed location count';

-- Old values represented embedding/chunk usage and cannot be relabeled as lexical metrics.
UPDATE file_upload
SET retrieval_indexed_token_count = NULL,
    retrieval_indexed_location_count = NULL;
