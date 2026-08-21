--liquibase formatted sql

--changeset jspringverse:002-filing-document
--comment: Registry of ingested filings. One row per (document content, embedding model).
CREATE TABLE filing_document
(
    id               UUID PRIMARY KEY     DEFAULT uuid_generate_v4(),

    -- SHA-256 of the raw uploaded bytes. The duplicate-detection key: re-uploading the
    -- same file is a no-op instead of a second full embedding run, which is both the
    -- expensive part of ingestion and the thing that silently doubles retrieval hits.
    content_sha256   CHAR(64)    NOT NULL,

    -- Which embedding model produced the vectors for this document. Part of the uniqueness
    -- key because the same PDF legitimately exists twice when both profiles are in use:
    -- 1536-dimension OpenAI vectors and 768-dimension Ollama vectors cannot share a table.
    embedding_model  VARCHAR(64) NOT NULL,
    vector_table     VARCHAR(64) NOT NULL,

    ticker           VARCHAR(12) NOT NULL,
    company_name     VARCHAR(255),
    form_type        VARCHAR(16) NOT NULL,

    fiscal_year      INTEGER,
    fiscal_period    VARCHAR(8),
    period_end_date  DATE,
    filing_date      DATE,

    source_filename  VARCHAR(512),
    source_uri       VARCHAR(1024),
    byte_size        BIGINT,
    extracted_chars  BIGINT,
    section_count    INTEGER,
    chunk_count      INTEGER,

    ingested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_filing_document_content UNIQUE (content_sha256, embedding_model)
);

--rollback DROP TABLE filing_document;

--changeset jspringverse:002-filing-document-indexes
--comment: Supports latest-version resolution, which runs on nearly every filing query.
CREATE INDEX idx_filing_document_lookup
    ON filing_document (embedding_model, ticker, form_type);

CREATE INDEX idx_filing_document_recency
    ON filing_document (ticker, form_type, fiscal_year DESC NULLS LAST, ingested_at DESC);

--rollback DROP INDEX idx_filing_document_lookup;
--rollback DROP INDEX idx_filing_document_recency;
