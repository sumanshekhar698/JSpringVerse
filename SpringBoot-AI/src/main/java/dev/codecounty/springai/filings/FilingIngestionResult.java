package dev.codecounty.springai.filings;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of an ingestion request.
 *
 * <p>Returned to the caller so an operator can verify a filing landed as expected — the
 * section list in particular is the fastest way to spot a PDF that extracted badly, and
 * {@code status} distinguishes real work from a deduplicated no-op.
 */
public record FilingIngestionResult(
        Status status,
        UUID documentId,
        String contentSha256,
        String citation,
        String sourceFile,
        boolean latestVersion,
        int sectionsDetected,
        int chunksStored,
        long extractedCharacters,
        Duration duration,
        List<String> sections,
        String message) {

    public enum Status {
        /** Parsed, chunked, embedded and stored. */
        INGESTED,
        /**
         * Byte-identical to a document already indexed under this embedding model, so
         * nothing was re-parsed or re-embedded.
         */
        DUPLICATE_SKIPPED,
        /** A prior copy was removed and the document re-ingested at the caller's request. */
        REINGESTED
    }

    /** Result for a document whose content hash already exists in the registry. */
    public static FilingIngestionResult duplicate(FilingDocument existing, boolean latest) {
        return new FilingIngestionResult(
                Status.DUPLICATE_SKIPPED,
                existing.id(),
                existing.contentSha256(),
                existing.citation(),
                existing.sourceFilename(),
                latest,
                existing.sectionCount() == null ? 0 : existing.sectionCount(),
                existing.chunkCount() == null ? 0 : existing.chunkCount(),
                existing.extractedChars() == null ? 0 : existing.extractedChars(),
                Duration.ZERO,
                List.of(),
                "Identical content was already ingested on %s as %s. Nothing was re-embedded. "
                        .formatted(existing.ingestedAt(), existing.citation())
                        + "Pass force=true to replace it.");
    }
}
