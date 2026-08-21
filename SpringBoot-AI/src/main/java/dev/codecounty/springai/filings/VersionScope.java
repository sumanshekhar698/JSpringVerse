package dev.codecounty.springai.filings;

import java.util.Locale;

/**
 * Which versions of a filing a search should consider.
 *
 * <p>Every filing exists in successive annual or quarterly editions that contradict each
 * other by design — a 2023 10-K and a 2025 10-K describe different businesses. Searching
 * all of them by default produces answers that blend fiscal years, which is the single most
 * misleading failure mode of filing RAG. So {@link #LATEST} is the default, and history is
 * opt-in.
 */
public enum VersionScope {

    /**
     * Only the most recent filing of each form type per company. The right default for
     * "what does the company say about X".
     */
    LATEST,

    /**
     * Every version ever ingested. Use for "how has X changed", trend analysis, or any
     * question naming a past period.
     */
    ALL;

    /** Lenient parse; unrecognized input falls back to {@link #LATEST}. */
    public static VersionScope from(String raw) {
        if (raw == null || raw.isBlank()) {
            return LATEST;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ALL", "HISTORY", "HISTORICAL", "ANY" -> ALL;
            default -> LATEST;
        };
    }
}
