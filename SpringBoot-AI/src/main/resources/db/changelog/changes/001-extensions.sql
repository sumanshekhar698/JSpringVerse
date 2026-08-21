--liquibase formatted sql

--changeset jspringverse:001-extension-vector
--comment: pgvector supplies the vector column type and the HNSW index method.
-- Checked as a precondition rather than letting CREATE EXTENSION fail, so the build stops
-- here with an actionable message instead of at changeset 003 with "type vector does not
-- exist" — and, critically, before any table has been created.
--preconditions onFail:HALT onError:HALT onFailMessage:The pgvector extension is not installed on this PostgreSQL server. The agent cannot store embeddings without it. Install pgvector for your server version (Docker image pgvector/pgvector:pgNN, or build from https://github.com/pgvector/pgvector), then re-run. Verify with: SELECT * FROM pg_available_extensions WHERE name = 'vector';
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM pg_available_extensions WHERE name = 'vector'
CREATE EXTENSION IF NOT EXISTS vector;
--rollback DROP EXTENSION IF EXISTS vector;

--changeset jspringverse:001-extension-uuid
--comment: uuid_generate_v4() is the default for primary keys in the tables below.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
--rollback DROP EXTENSION IF EXISTS "uuid-ossp";
