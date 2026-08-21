--liquibase formatted sql

-- Every filing query applies a metadata filter before the vector comparison. Without
-- these, Postgres scans the whole table to evaluate metadata->>'document_id', which
-- defeats the point of restricting the search to the latest filing.

--changeset jspringverse:004-metadata-indexes-openai
CREATE INDEX idx_filing_vectors_openai_doc
    ON filing_vectors_openai ((metadata ->> 'document_id'));
CREATE INDEX idx_filing_vectors_openai_ticker
    ON filing_vectors_openai ((metadata ->> 'ticker'));
CREATE INDEX idx_filing_vectors_openai_form
    ON filing_vectors_openai ((metadata ->> 'form_type'));
CREATE INDEX idx_filing_vectors_openai_year
    ON filing_vectors_openai ((metadata ->> 'fiscal_year'));

--rollback DROP INDEX idx_filing_vectors_openai_doc;
--rollback DROP INDEX idx_filing_vectors_openai_ticker;
--rollback DROP INDEX idx_filing_vectors_openai_form;
--rollback DROP INDEX idx_filing_vectors_openai_year;

--changeset jspringverse:004-metadata-indexes-ollama
CREATE INDEX idx_filing_vectors_ollama_doc
    ON filing_vectors_ollama ((metadata ->> 'document_id'));
CREATE INDEX idx_filing_vectors_ollama_ticker
    ON filing_vectors_ollama ((metadata ->> 'ticker'));
CREATE INDEX idx_filing_vectors_ollama_form
    ON filing_vectors_ollama ((metadata ->> 'form_type'));
CREATE INDEX idx_filing_vectors_ollama_year
    ON filing_vectors_ollama ((metadata ->> 'fiscal_year'));

--rollback DROP INDEX idx_filing_vectors_ollama_doc;
--rollback DROP INDEX idx_filing_vectors_ollama_ticker;
--rollback DROP INDEX idx_filing_vectors_ollama_form;
--rollback DROP INDEX idx_filing_vectors_ollama_year;
