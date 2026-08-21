package dev.codecounty.springai.filings;

import java.util.Locale;

/**
 * SEC filing types this system understands.
 *
 * <p>Stored on every chunk as metadata so retrieval can be scoped — "what did the annual
 * report say" should not pull answers out of a quarterly, and vice versa.
 */
public enum FilingType {

    /** Annual report. Comprehensive: business, risk factors, MD&A, audited financials. */
    FORM_10K("10-K", "Annual report"),

    /** Quarterly report. Unaudited interim financials and material updates. */
    FORM_10Q("10-Q", "Quarterly report"),

    /** Current report announcing a material event between periodic filings. */
    FORM_8K("8-K", "Current report"),

    /** Proxy statement: executive compensation, governance, shareholder votes. */
    DEF_14A("DEF 14A", "Proxy statement"),

    /** Anything else the user chooses to ingest. */
    OTHER("OTHER", "Other document");

    private final String label;
    private final String description;

    FilingType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Human- and metadata-facing label, e.g. {@code 10-K}. */
    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /**
     * Lenient parse accepting {@code 10-K}, {@code 10K}, {@code form_10k} and the enum name.
     *
     * @return the matching type, or {@link #OTHER} when nothing matches
     */
    public static FilingType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        String normalized = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        for (FilingType type : values()) {
            String typeKey = type.label.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
            if (normalized.equals(typeKey)
                    || normalized.equals(type.name())
                    || normalized.equals("FORM" + typeKey)) {
                return type;
            }
        }
        return OTHER;
    }
}
