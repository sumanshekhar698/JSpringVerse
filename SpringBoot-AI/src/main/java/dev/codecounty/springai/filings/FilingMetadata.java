package dev.codecounty.springai.filings;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Provenance attached to every chunk of an ingested filing.
 *
 * <p>This is what makes filing RAG usable rather than a bag of text. Three things depend on
 * it: <b>filtering</b> (restrict retrieval to one company, form type or year),
 * <b>versioning</b> (via {@link #KEY_DOCUMENT_ID}, which ties a chunk back to its row in
 * {@code filing_document} so "latest only" is expressible as a filter), and <b>citation</b>
 * (the agent can say <i>"AAPL 10-K FY2024, Item 1A"</i> instead of "somewhere in your
 * documents").
 *
 * <p>Keys are snake_case because they are queried through Spring AI's filter expression
 * language against the pgvector JSON column.
 */
public record FilingMetadata(
        UUID documentId,
        String ticker,
        String companyName,
        FilingType formType,
        Integer fiscalYear,
        String fiscalPeriod,
        LocalDate periodEndDate,
        LocalDate filingDate,
        String sourceFile) {

    /** Ties a chunk to its {@code filing_document} row. The key to version-scoped retrieval. */
    public static final String KEY_DOCUMENT_ID = "document_id";
    public static final String KEY_TICKER = "ticker";
    public static final String KEY_COMPANY = "company_name";
    public static final String KEY_FORM_TYPE = "form_type";
    public static final String KEY_FISCAL_YEAR = "fiscal_year";
    public static final String KEY_FISCAL_PERIOD = "fiscal_period";
    public static final String KEY_PERIOD_END = "period_end_date";
    public static final String KEY_FILING_DATE = "filing_date";
    public static final String KEY_SOURCE_FILE = "source_file";
    public static final String KEY_SECTION = "section";
    public static final String KEY_CHUNK_INDEX = "chunk_index";

    public FilingMetadata {
        ticker = ticker == null ? null : ticker.trim().toUpperCase(Locale.ROOT);
        formType = formType == null ? FilingType.OTHER : formType;
    }

    /**
     * Flattens to the map stored alongside each embedding.
     *
     * <p>Nulls are omitted rather than stored: a filter such as {@code fiscal_year == 2024}
     * behaves predictably when the key is absent, but matches unpredictably against a JSON
     * null across store implementations.
     */
    public Map<String, Object> toMetadataMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, KEY_DOCUMENT_ID, documentId == null ? null : documentId.toString());
        putIfPresent(map, KEY_TICKER, ticker);
        putIfPresent(map, KEY_COMPANY, companyName);
        map.put(KEY_FORM_TYPE, formType.label());
        putIfPresent(map, KEY_FISCAL_YEAR, fiscalYear);
        putIfPresent(map, KEY_FISCAL_PERIOD, fiscalPeriod);
        putIfPresent(map, KEY_PERIOD_END, periodEndDate == null ? null : periodEndDate.toString());
        putIfPresent(map, KEY_FILING_DATE, filingDate == null ? null : filingDate.toString());
        putIfPresent(map, KEY_SOURCE_FILE, sourceFile);
        return map;
    }

    /** Short human-readable citation, e.g. {@code AAPL 10-K FY2024}. */
    public String citation() {
        StringBuilder citation = new StringBuilder();
        citation.append(ticker == null ? "Unknown issuer" : ticker);
        citation.append(' ').append(formType.label());
        if (fiscalYear != null) {
            citation.append(" FY").append(fiscalYear);
        }
        if (fiscalPeriod != null && !fiscalPeriod.isBlank()) {
            citation.append(' ').append(fiscalPeriod);
        }
        return citation.toString();
    }

    /** Copy carrying the registry id assigned at ingestion time. */
    public FilingMetadata withDocumentId(UUID id) {
        return new FilingMetadata(id, ticker, companyName, formType, fiscalYear,
                fiscalPeriod, periodEndDate, filingDate, sourceFile);
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            map.put(key, value);
        }
    }
}
