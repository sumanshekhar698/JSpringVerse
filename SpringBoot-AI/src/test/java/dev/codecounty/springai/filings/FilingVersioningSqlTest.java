package dev.codecounty.springai.filings;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link FilingRegistry}'s version resolution against a real PostgreSQL.
 *
 * <p>Separate from {@link LiquibaseChangelogTest} because it needs only the
 * {@code filing_document} table, not pgvector — so it runs in environments where the
 * extension is unavailable, which is where a subtle ordering bug would otherwise go
 * unnoticed the longest.
 *
 * <p>What is actually being pinned down is the rule that "latest" means <b>latest reporting
 * period</b>, not latest upload. Backfilling an old 10-K must not make it the current one.
 * That is easy to get wrong and impossible to notice from unit tests, because it only shows
 * up when the ingestion order and the period order disagree.
 *
 * <pre>
 *   $env:POSTGRES_TEST="true"; $env:POSTGRES_PASSWORD="..."
 *   .\mvnw.cmd test -Dtest=FilingVersioningSqlTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST", matches = "true")
class FilingVersioningSqlTest {

    private static final String URL = System.getenv().getOrDefault(
            "POSTGRES_URL", "jdbc:postgresql://localhost:5432/jspringverse");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    /**
     * Everything happens in a throwaway schema.
     *
     * <p>Creating {@code filing_document} in {@code public} would collide with Liquibase
     * changeset 002 the next time the changelog runs — an unmanaged table already sitting
     * where a changeset wants to create one. An isolated schema keeps the test self-contained
     * and leaves the migration path untouched.
     */
    private static final String TEST_SCHEMA = "filing_versioning_test";

    private JdbcClient jdbcClient;
    private FilingRegistry registry;

    @BeforeEach
    void setUp() throws SQLException {
        // search_path makes FilingRegistry's unqualified "filing_document" resolve into the
        // throwaway schema. The registry is the thing under test, so its SQL is used as-is.
        java.util.Properties connectionProperties = new java.util.Properties();
        connectionProperties.setProperty("user", USER);
        connectionProperties.setProperty("password", PASSWORD);
        connectionProperties.setProperty("options", "-c search_path=" + TEST_SCHEMA + ",public");

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriver(new org.postgresql.Driver());
        dataSource.setUrl(URL);
        dataSource.setConnectionProperties(connectionProperties);

        this.jdbcClient = JdbcClient.create(dataSource);
        this.registry = new FilingRegistry(jdbcClient);

        jdbcClient.sql("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"").update();
        jdbcClient.sql("CREATE SCHEMA IF NOT EXISTS " + TEST_SCHEMA).update();
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS filing_versioning_test.filing_document (
                    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                    content_sha256 CHAR(64) NOT NULL,
                    embedding_model VARCHAR(64) NOT NULL,
                    vector_table VARCHAR(64) NOT NULL,
                    ticker VARCHAR(12) NOT NULL,
                    company_name VARCHAR(255),
                    form_type VARCHAR(16) NOT NULL,
                    fiscal_year INTEGER,
                    fiscal_period VARCHAR(8),
                    period_end_date DATE,
                    filing_date DATE,
                    source_filename VARCHAR(512),
                    source_uri VARCHAR(1024),
                    byte_size BIGINT,
                    extracted_chars BIGINT,
                    section_count INTEGER,
                    chunk_count INTEGER,
                    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_filing_document_content UNIQUE (content_sha256, embedding_model))
                """).update();
        jdbcClient.sql("TRUNCATE " + TEST_SCHEMA + ".filing_document").update();
    }

    @AfterAll
    static void dropTestSchema() throws SQLException {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), URL, USER, PASSWORD);
        JdbcClient.create(dataSource)
                .sql("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE")
                .update();
    }

    @Test
    @DisplayName("latest is the newest reporting period, not the newest upload")
    void prefersReportingPeriodOverIngestionTime() {
        insert("1", "openai", "AAPL", "10-K", 2023, null, "2023-09-30", "2026-07-01");
        insert("2", "openai", "AAPL", "10-K", 2025, null, "2025-09-27", "2026-07-02");
        // Backfilled today, but reports an older period. Must NOT become the current filing.
        insert("3", "openai", "AAPL", "10-K", 2024, null, "2024-09-28", "2026-07-27");

        List<FilingDocument> latest = registry.findLatest("openai", "AAPL", FilingType.FORM_10K);

        assertThat(latest).hasSize(1);
        assertThat(latest.getFirst().fiscalYear()).isEqualTo(2025);
    }

    @Test
    @DisplayName("resolves one current filing per ticker and form type")
    void resolvesPerTickerAndForm() {
        insert("1", "openai", "AAPL", "10-K", 2025, null, "2025-09-27", "2026-07-02");
        insert("2", "openai", "AAPL", "10-Q", 2025, "Q1", "2024-12-28", "2026-07-03");
        insert("3", "openai", "AAPL", "10-Q", 2025, "Q3", "2025-06-28", "2026-07-04");
        insert("4", "openai", "MSFT", "10-K", 2024, null, "2024-06-30", "2026-07-05");

        List<FilingDocument> latest = registry.findLatest("openai", null, null);

        assertThat(latest).hasSize(3);
        assertThat(latest).extracting(FilingDocument::citation)
                .containsExactlyInAnyOrder(
                        "AAPL 10-K FY2025",
                        "AAPL 10-Q FY2025 Q3",   // Q3 beats Q1
                        "MSFT 10-K FY2024");
    }

    @Test
    @DisplayName("scopes to the active embedding model, since dimensions differ per table")
    void isolatesEmbeddingModels() {
        insert("1", "openai", "AAPL", "10-K", 2025, null, "2025-09-27", "2026-07-02");
        insert("2", "ollama", "AAPL", "10-K", 2025, null, "2025-09-27", "2026-07-06");

        assertThat(registry.findLatest("openai", null, null)).hasSize(1);
        assertThat(registry.findLatest("ollama", null, null)).hasSize(1);
        assertThat(registry.findAll("openai")).hasSize(1);
    }

    @Test
    @DisplayName("finds a prior ingest by content hash, scoped to the embedding model")
    void findsByContentHash() {
        insert("a", "openai", "AAPL", "10-K", 2025, null, "2025-09-27", "2026-07-02");
        String hash = "a".repeat(64);

        assertThat(registry.findByContentHash(hash, "openai")).isPresent();
        // Same bytes under a different embedding model is a legitimately separate document.
        assertThat(registry.findByContentHash(hash, "ollama")).isEmpty();
        assertThat(registry.findByContentHash("b".repeat(64), "openai")).isEmpty();
    }

    @Test
    @DisplayName("lists fiscal years available for historical comparison, newest first")
    void listsFiscalYears() {
        insert("1", "openai", "AAPL", "10-K", 2023, null, "2023-09-30", "2026-07-01");
        insert("2", "openai", "AAPL", "10-K", 2025, null, "2025-09-27", "2026-07-02");
        insert("3", "openai", "AAPL", "10-K", 2024, null, "2024-09-28", "2026-07-03");

        assertThat(registry.findFiscalYears("openai", "AAPL", FilingType.FORM_10K))
                .containsExactly(2025, 2024, 2023);
    }

    @Test
    @DisplayName("orders by fiscal year when no period end date is recorded")
    void fallsBackToFiscalYear() {
        insert("1", "openai", "NVDA", "10-K", 2024, null, null, "2026-07-01");
        insert("2", "openai", "NVDA", "10-K", 2025, null, null, "2026-07-02");

        assertThat(registry.findLatest("openai", "NVDA", FilingType.FORM_10K).getFirst().fiscalYear())
                .isEqualTo(2025);
    }

    private void insert(String hashSeed, String model, String ticker, String formType,
                        Integer fiscalYear, String fiscalPeriod, String periodEnd, String ingestedAt) {

        jdbcClient.sql("""
                        INSERT INTO filing_document (content_sha256, embedding_model, vector_table,
                            ticker, company_name, form_type, fiscal_year, fiscal_period,
                            period_end_date, ingested_at)
                        VALUES (:sha, :model, 'v', :ticker, :ticker || ' Inc.', :form, :year,
                                :period, CAST(:periodEnd AS date), CAST(:ingestedAt AS timestamptz))
                        """)
                .param("sha", hashSeed.repeat(64).substring(0, 64))
                .param("model", model)
                .param("ticker", ticker)
                .param("form", formType)
                .param("year", fiscalYear)
                .param("period", fiscalPeriod)
                .param("periodEnd", periodEnd)
                .param("ingestedAt", ingestedAt)
                .update();
    }
}
