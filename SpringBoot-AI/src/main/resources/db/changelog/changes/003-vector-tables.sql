--liquibase formatted sql

-- Column layout must match exactly what Spring AI's PgVectorStore expects:
--   id uuid, content text, metadata json, embedding vector(N)
-- The app runs with schema-validation=true, so it will refuse to start if these drift.
--
-- Both tables are created regardless of the active profile. An unused empty table costs
-- nothing, and creating both keeps the schema independent of which embedding profile
-- happens to start first.

--changeset jspringverse:003-vectors-openai
--comment: OpenAI text-embedding-3-small, 1536 dimensions. Profile embed-openai.
CREATE TABLE filing_vectors_openai
(
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1536)
);

-- HNSW with cosine distance: matches spring.ai.vectorstore.pgvector.distance-type.
-- Built on an empty table so it is instant here; building it after a large ingest is not.
CREATE INDEX idx_filing_vectors_openai_hnsw
    ON filing_vectors_openai USING hnsw (embedding vector_cosine_ops);

--rollback DROP TABLE filing_vectors_openai;

--changeset jspringverse:003-vectors-ollama
--comment: Ollama nomic-embed-text, 768 dimensions. Profile embed-ollama.
CREATE TABLE filing_vectors_ollama
(
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(768)
);

CREATE INDEX idx_filing_vectors_ollama_hnsw
    ON filing_vectors_ollama USING hnsw (embedding vector_cosine_ops);

--rollback DROP TABLE filing_vectors_ollama;
