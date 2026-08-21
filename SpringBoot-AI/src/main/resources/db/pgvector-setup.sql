-- One-time setup for the filing vector store.
-- Run against your existing Postgres instance before starting the application:
--
--   psql -U postgres -f pgvector-setup.sql
--
-- Requires the pgvector extension to be installed on the server. On Windows this comes
-- with the EDB installer's StackBuilder, or `choco install postgresql-pgvector`.
-- Verify availability first:  SELECT * FROM pg_available_extensions WHERE name = 'vector';

CREATE DATABASE jspringverse;

\connect jspringverse

-- pgvector: the vector column type and the HNSW / IVFFlat index methods.
CREATE EXTENSION IF NOT EXISTS vector;

-- Used by Spring AI's schema initializer to generate row IDs.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- The tables themselves are created by Spring AI on first start, because
-- spring.ai.vectorstore.pgvector.initialize-schema=true. It creates, per profile:
--
--   filing_vectors_openai (embedding vector(1536))   -- profile embed-openai
--   filing_vectors_ollama (embedding vector(768))    -- profile embed-ollama
--
-- with columns: id uuid, content text, metadata json, embedding vector(N).
--
-- Set initialize-schema=false in production and manage the DDL through your migration
-- tool instead; the statements below are what it would create.

-- CREATE TABLE IF NOT EXISTS filing_vectors_openai (
--     id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
--     content   text,
--     metadata  json,
--     embedding vector(1536)
-- );
--
-- CREATE INDEX ON filing_vectors_openai USING hnsw (embedding vector_cosine_ops);


-- Metadata indexes. Every filing query filters on ticker and form type before the
-- vector comparison, and without these Postgres scans the whole table to do it.
-- Run these AFTER the first application start has created the table.
--
-- CREATE INDEX IF NOT EXISTS idx_filing_openai_ticker
--     ON filing_vectors_openai ((metadata->>'ticker'));
-- CREATE INDEX IF NOT EXISTS idx_filing_openai_form
--     ON filing_vectors_openai ((metadata->>'form_type'));
-- CREATE INDEX IF NOT EXISTS idx_filing_openai_year
--     ON filing_vectors_openai ((metadata->>'fiscal_year'));
