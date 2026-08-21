--liquibase formatted sql

-- Conversation history for MessageChatMemoryAdvisor.
--
-- This mirrors exactly what Spring AI's JdbcChatMemoryRepository would create for itself
-- (schema-postgresql.sql inside spring-ai-model-chat-memory-repository-jdbc). We create it
-- here instead, and set spring.ai.chat.memory.repository.jdbc.initialize-schema=never, so
-- that one tool owns every table. Two independent schema initialisers racing on the same
-- database is how a deployment ends up half-migrated.
--
-- The column names, types and CHECK constraint must match the repository's queries exactly.

--changeset jspringverse:005-chat-memory
CREATE TABLE SPRING_AI_CHAT_MEMORY
(
    conversation_id VARCHAR(36) NOT NULL,
    content         TEXT        NOT NULL,
    type            VARCHAR(10) NOT NULL
        CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP   NOT NULL,
    sequence_id     BIGINT      NOT NULL
);

-- Both indexes are used: retrieval orders by timestamp, the window query by sequence_id.
CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, "timestamp");

CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, sequence_id);

--rollback DROP TABLE SPRING_AI_CHAT_MEMORY;
