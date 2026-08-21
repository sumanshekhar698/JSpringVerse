package dev.codecounty.springai.filings;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row of the {@code filing_document} registry: one ingested document.
 *
 * <p>The registry is what makes versioning work. The vector table holds chunks with no
 * notion of which filing supersedes which; this table records the identity, period and
 * provenance of each document, so "the latest 10-K" is a query rather than a guess.
 */
public record FilingDocument(
        UUID id,
        String contentSha256,
        String embeddingModel,
        String vectorTable,
        String ticker,
        String companyName,
        FilingType formType,
        Integer fiscalYear,
        String fiscalPeriod,
        LocalDate periodEndDate,
        LocalDate filingDate,
        String sourceFilename,
        String sourceUri,
        Long byteSize,
        Long extractedChars,
        Integer sectionCount,
        Integer chunkCount,
        Instant ingestedAt) {

    /** Short human-readable citation, e.g. {@code AAPL 10-K FY2024 Q3}. */
    public String citation() {
        StringBuilder citation = new StringBuilder(ticker == null ? "Unknown issuer" : ticker);
        citation.append(' ').append(formType.label());
        if (fiscalYear != null) {
            citation.append(" FY").append(fiscalYear);
        }
        if (fiscalPeriod != null && !fiscalPeriod.isBlank()) {
            citation.append(' ').append(fiscalPeriod);
        }
        return citation.toString();
    }
}
