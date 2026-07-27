-- Remove the legacy parser-derived chunk projection.
-- Current retrieval indexes are built from the Current Reading Model, not paper_text_chunks.

DROP TABLE IF EXISTS paper_text_chunks;
