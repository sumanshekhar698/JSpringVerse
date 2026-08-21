package dev.codecounty.springai.filings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.regex.Pattern;

/**
 * Chunk-level statistics over the pgvector table.
 *
 * <p>Narrow by design. Filing identity and versioning live in {@link FilingRegistry};
 * this covers only the questions that require counting rows in the vector table itself,
 * which {@link org.springframework.ai.vectorstore.VectorStore} exposes no API for and which
 * the health endpoint and ingestion reports need.
 */
@Repository
public class FilingCatalog {

    /**
     * Postgres identifiers cannot be bound as parameters, so the table name is interpolated
     * into SQL. It comes from configuration rather than user input, but the allowlist keeps
     * a bad config value from becoming an injection vector.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    private final JdbcClient jdbcClient;
    private final String qualifiedTable;

    public FilingCatalog(
            JdbcClient jdbcClient,
            @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {

        this.jdbcClient = jdbcClient;
        this.qualifiedTable = requireSafeIdentifier(schemaName) + "." + requireSafeIdentifier(tableName);
    }

    /** Total chunks indexed across all filings. */
    public long totalChunks() {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + qualifiedTable)
                .query(Long.class)
                .single();
    }

    /** Chunks belonging to one registered document. */
    public long chunksForDocument(String documentId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + qualifiedTable
                        + " WHERE metadata->>'document_id' = :id")
                .param("id", documentId)
                .query(Long.class)
                .single();
    }

    /** The table this instance is counting, for display on the health endpoint. */
    public String vectorTable() {
        return qualifiedTable;
    }

    private static String requireSafeIdentifier(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Unsafe Postgres identifier in configuration: '%s'".formatted(identifier));
        }
        return identifier;
    }
}
