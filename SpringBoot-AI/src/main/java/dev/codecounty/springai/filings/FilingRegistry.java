package dev.codecounty.springai.filings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the {@code filing_document} registry.
 *
 * <p>Two jobs, both of which the vector store cannot do:
 *
 * <ul>
 *   <li><b>Duplicate detection.</b> A content hash lookup before parsing means re-uploading
 *       the same file skips extraction and embedding entirely — the expensive part, and the
 *       part that would otherwise double every retrieval hit for that document.
 *   <li><b>Version resolution.</b> Records which document is the most recent filing of its
 *       kind, so a question defaults to current disclosure while older filings stay indexed
 *       and searchable for historical comparison.
 * </ul>
 */
@Repository
public class FilingRegistry {

    /**
     * Recency ordering for "which filing supersedes which".
     *
     * <p>Prefers the actual reporting period over the ingestion timestamp, so uploading an
     * old 10-K today does not make it look like the current one. Falls back through
     * period end → filing date → fiscal year → ingestion order as each becomes unavailable.
     */
    private static final String RECENCY_ORDER = """
            COALESCE(period_end_date, filing_date, make_date(COALESCE(fiscal_year, 1900), 12, 31)) DESC,
            fiscal_period DESC NULLS FIRST,
            ingested_at DESC
            """;

    private static final String COLUMNS = """
            id, content_sha256, embedding_model, vector_table, ticker, company_name, form_type,
            fiscal_year, fiscal_period, period_end_date, filing_date, source_filename, source_uri,
            byte_size, extracted_chars, section_count, chunk_count, ingested_at
            """;

    private final JdbcClient jdbcClient;

    public FilingRegistry(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Looks up a previously ingested document by content hash, scoped to the embedding model. */
    public Optional<FilingDocument> findByContentHash(String sha256, String embeddingModel) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM filing_document "
                        + "WHERE content_sha256 = :sha AND embedding_model = :model")
                .param("sha", sha256)
                .param("model", embeddingModel)
                .query(FilingRegistry::mapRow)
                .optional();
    }

    public Optional<FilingDocument> findById(UUID id) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM filing_document WHERE id = :id")
                .param("id", id)
                .query(FilingRegistry::mapRow)
                .optional();
    }

    public void insert(FilingDocument document) {
        jdbcClient.sql("""
                        INSERT INTO filing_document (
                            id, content_sha256, embedding_model, vector_table, ticker, company_name,
                            form_type, fiscal_year, fiscal_period, period_end_date, filing_date,
                            source_filename, source_uri, byte_size, extracted_chars, section_count,
                            chunk_count, ingested_at)
                        VALUES (
                            :id, :sha, :model, :table, :ticker, :company,
                            :form, :year, :period, :periodEnd, :filingDate,
                            :filename, :uri, :bytes, :chars, :sections,
                            :chunks, :ingestedAt)
                        """)
                .param("id", document.id())
                .param("sha", document.contentSha256())
                .param("model", document.embeddingModel())
                .param("table", document.vectorTable())
                .param("ticker", document.ticker())
                .param("company", document.companyName())
                .param("form", document.formType().label())
                .param("year", document.fiscalYear())
                .param("period", document.fiscalPeriod())
                .param("periodEnd", document.periodEndDate() == null ? null : Date.valueOf(document.periodEndDate()))
                .param("filingDate", document.filingDate() == null ? null : Date.valueOf(document.filingDate()))
                .param("filename", document.sourceFilename())
                .param("uri", document.sourceUri())
                .param("bytes", document.byteSize())
                .param("chars", document.extractedChars())
                .param("sections", document.sectionCount())
                .param("chunks", document.chunkCount())
                .param("ingestedAt", java.sql.Timestamp.from(document.ingestedAt()))
                .update();
    }

    public void deleteById(UUID id) {
        jdbcClient.sql("DELETE FROM filing_document WHERE id = :id").param("id", id).update();
    }

    /** Every registered document for an embedding model, newest first. */
    public List<FilingDocument> findAll(String embeddingModel) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM filing_document "
                        + "WHERE embedding_model = :model ORDER BY ticker, form_type, " + RECENCY_ORDER)
                .param("model", embeddingModel)
                .query(FilingRegistry::mapRow)
                .list();
    }

    /**
     * The most recent filing of each (ticker, form type) combination.
     *
     * <p>{@code DISTINCT ON} picks the first row per group under the given ordering, which
     * is exactly "latest per company per form" in one pass.
     *
     * @param ticker   restrict to one company, or null for all
     * @param formType restrict to one form type, or null for all
     */
    public List<FilingDocument> findLatest(String embeddingModel, String ticker, FilingType formType) {
        return jdbcClient.sql("""
                        SELECT DISTINCT ON (ticker, form_type) %s
                        FROM filing_document
                        WHERE embedding_model = :model
                          AND (CAST(:ticker AS text) IS NULL OR ticker = CAST(:ticker AS text))
                          AND (CAST(:form AS text) IS NULL OR form_type = CAST(:form AS text))
                        ORDER BY ticker, form_type, %s
                        """.formatted(COLUMNS, RECENCY_ORDER))
                .param("model", embeddingModel)
                .param("ticker", ticker)
                .param("form", formType == null ? null : formType.label())
                .query(FilingRegistry::mapRow)
                .list();
    }

    /** Document ids of the latest filings, for use as a vector-store metadata filter. */
    public List<String> findLatestDocumentIds(String embeddingModel, String ticker, FilingType formType) {
        return findLatest(embeddingModel, ticker, formType).stream()
                .map(document -> document.id().toString())
                .toList();
    }

    /** All fiscal years on record for a company and form type, newest first. */
    public List<Integer> findFiscalYears(String embeddingModel, String ticker, FilingType formType) {
        return jdbcClient.sql("""
                        SELECT DISTINCT fiscal_year FROM filing_document
                        WHERE embedding_model = :model
                          AND fiscal_year IS NOT NULL
                          AND (CAST(:ticker AS text) IS NULL OR ticker = CAST(:ticker AS text))
                          AND (CAST(:form AS text) IS NULL OR form_type = CAST(:form AS text))
                        ORDER BY fiscal_year DESC
                        """)
                .param("model", embeddingModel)
                .param("ticker", ticker)
                .param("form", formType == null ? null : formType.label())
                .query(Integer.class)
                .list();
    }

    public long count(String embeddingModel) {
        return jdbcClient.sql("SELECT COUNT(*) FROM filing_document WHERE embedding_model = :model")
                .param("model", embeddingModel)
                .query(Long.class)
                .single();
    }

    private static FilingDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FilingDocument(
                rs.getObject("id", UUID.class),
                rs.getString("content_sha256"),
                rs.getString("embedding_model"),
                rs.getString("vector_table"),
                rs.getString("ticker"),
                rs.getString("company_name"),
                FilingType.from(rs.getString("form_type")),
                nullableInt(rs, "fiscal_year"),
                rs.getString("fiscal_period"),
                nullableDate(rs, "period_end_date"),
                nullableDate(rs, "filing_date"),
                rs.getString("source_filename"),
                rs.getString("source_uri"),
                nullableLong(rs, "byte_size"),
                nullableLong(rs, "extracted_chars"),
                nullableInt(rs, "section_count"),
                nullableInt(rs, "chunk_count"),
                rs.getTimestamp("ingested_at").toInstant());
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate nullableDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    /** Instant helper kept for readability at call sites building new registry rows. */
    public static Instant now() {
        return Instant.now();
    }
}
