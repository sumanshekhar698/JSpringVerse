package dev.codecounty.springai.filings;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies the changelog to a real Postgres and verifies the resulting schema.
 *
 * <p>Runs Liquibase directly rather than through a Spring context, so it needs no API keys
 * and no AI beans — the only thing under test is the DDL.
 *
 * <p>Deliberately asserts the exact column layout PgVectorStore requires and the exact
 * vector dimensions. A mismatch there is the failure that would otherwise appear at runtime
 * as an opaque insert error after a filing has already been parsed and embedded.
 *
 * <pre>
 *   $env:POSTGRES_TEST="true"; $env:POSTGRES_PASSWORD="..."
 *   .\mvnw.cmd test -Dtest=LiquibaseChangelogTest
 * </pre>
 *
 * <p>Idempotent: re-running applies nothing, because Liquibase tracks applied changesets in
 * DATABASECHANGELOG. That property is itself asserted below.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST", matches = "true")
class LiquibaseChangelogTest {

    private static final String URL = System.getenv().getOrDefault(
            "POSTGRES_URL", "jdbc:postgresql://localhost:5432/jspringverse");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    @Test
    @DisplayName("applies cleanly and produces the schema PgVectorStore expects")
    void appliesChangelog() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {

            runLiquibase(connection);

            // Extensions
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'"))
                    .as("pgvector extension").isEqualTo("1");

            // Registry table
            assertThat(columnsOf(connection, "filing_document"))
                    .contains("id", "content_sha256", "embedding_model", "vector_table", "ticker",
                            "company_name", "form_type", "fiscal_year", "fiscal_period",
                            "period_end_date", "filing_date", "source_filename", "source_uri",
                            "byte_size", "extracted_chars", "section_count", "chunk_count",
                            "ingested_at");

            // The dedup guarantee is a database constraint, not just application logic.
            assertThat(scalar(connection, """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conname = 'uq_filing_document_content' AND contype = 'u'
                    """)).as("unique (content_sha256, embedding_model)").isEqualTo("1");

            // Vector tables: exact column layout PgVectorStore requires.
            for (String table : List.of("filing_vectors_openai", "filing_vectors_ollama")) {
                assertThat(columnsOf(connection, table))
                        .as("%s layout", table)
                        .containsExactlyInAnyOrder("id", "content", "metadata", "embedding");
            }

            // Dimensions must match the embedding profiles exactly.
            assertThat(vectorDimension(connection, "filing_vectors_openai"))
                    .as("OpenAI text-embedding-3-small").isEqualTo(1536);
            assertThat(vectorDimension(connection, "filing_vectors_ollama"))
                    .as("Ollama nomic-embed-text").isEqualTo(768);

            // Metadata indexes, without which every filtered search is a full table scan.
            assertThat(indexesOf(connection, "filing_vectors_openai"))
                    .contains("idx_filing_vectors_openai_hnsw",
                            "idx_filing_vectors_openai_doc",
                            "idx_filing_vectors_openai_ticker");

            System.out.printf("Schema verified against %s%n", URL);
            System.out.printf("  changesets applied: %s%n",
                    scalar(connection, "SELECT COUNT(*) FROM databasechangelog"));
        }
    }

    @Test
    @DisplayName("is idempotent — a second run applies nothing new")
    void isIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runLiquibase(connection);
            String before = scalar(connection, "SELECT COUNT(*) FROM databasechangelog");

            runLiquibase(connection);
            String after = scalar(connection, "SELECT COUNT(*) FROM databasechangelog");

            assertThat(after).isEqualTo(before);
        }
    }

    private static void runLiquibase(Connection connection) throws Exception {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        Liquibase liquibase = new Liquibase(
                "db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(), database);
        liquibase.update(new Contexts(), new LabelExpression());
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> columnsOf(Connection connection, String table) throws Exception {
        return queryList(connection,
                "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "'");
    }

    private static List<String> indexesOf(Connection connection, String table) throws Exception {
        return queryList(connection,
                "SELECT indexname FROM pg_indexes WHERE tablename = '" + table + "'");
    }

    /** Reads the declared dimension out of the column type, e.g. {@code vector(1536)} → 1536. */
    private static int vectorDimension(Connection connection, String table) throws Exception {
        String type = scalar(connection, """
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relname = '%s' AND a.attname = 'embedding'
                """.formatted(table));
        assertThat(type).as("embedding column type of %s", table).startsWith("vector(");
        return Integer.parseInt(type.replaceAll("\\D+", ""));
    }

    private static List<String> queryList(Connection connection, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
