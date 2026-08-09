ALTER TABLE paper_pages
    MODIFY COLUMN source_span_json LONGTEXT NOT NULL;

ALTER TABLE paper_sections
    MODIFY COLUMN source_span_json LONGTEXT NOT NULL;

ALTER TABLE paper_passages
    MODIFY COLUMN source_span_json LONGTEXT NOT NULL;

ALTER TABLE paper_locations
    MODIFY COLUMN source_span_json LONGTEXT NOT NULL;

ALTER TABLE paper_source_quotes
    MODIFY COLUMN source_span_json LONGTEXT NOT NULL;
