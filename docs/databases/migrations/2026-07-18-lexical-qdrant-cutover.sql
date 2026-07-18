-- Destructive development cutover to the sparse-only lexical Qdrant contract.
-- This migration intentionally does not preserve the old embedding/hybrid schema.

ALTER TABLE paper_reading_models
    DROP COLUMN retrieval_embedding_contract,
    DROP COLUMN retrieval_index_generation,
    ADD COLUMN retrieval_index_contract VARCHAR(255) NULL
        COMMENT 'Active lexical retrieval index contract'
        AFTER retrieval_index_job_id;

UPDATE paper_reading_models
SET retrieval_index_status = 'PENDING',
    retrieval_index_job_id = NULL,
    retrieval_index_contract = NULL,
    retrieval_indexed_location_count = NULL,
    retrieval_index_started_at = NULL,
    retrieval_indexed_at = NULL,
    retrieval_index_error_type = NULL,
    retrieval_index_error_message = NULL;

ALTER TABLE paper_retrieval_control
    DROP COLUMN target_embedding_contract,
    ADD COLUMN active_index_contract VARCHAR(255) NULL
        COMMENT 'Active lexical retrieval index contract'
        AFTER last_error,
    ADD COLUMN lexical_average_document_length DOUBLE NULL
        COMMENT 'Frozen BM25 average document length'
        AFTER active_index_contract;

DELETE FROM paper_retrieval_control;

INSERT INTO paper_retrieval_control (
    control_name,
    full_rebuild_status,
    snapshot_paper_count,
    completed_paper_count,
    failed_paper_count
) VALUES (
    'QDRANT_FULL_REBUILD',
    'IDLE',
    0,
    0,
    0
);
