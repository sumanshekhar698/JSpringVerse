package dev.codecounty.springai.filings;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives filing metadata from the upload's filename.
 *
 * <p>Convention: {@code <Company>_<FormType>-<Period>.<ext>} — the shape of
 * {@code Apple_10K-2025.pdf}. Separators are interchangeable ({@code _}, {@code -}, space)
 * because real filenames arrive inconsistently, and the period may carry a quarter or a
 * full period-end date:
 *
 * <pre>
 *   Apple_10K-2025.pdf                → Apple,     10-K, FY2025
 *   AAPL_10-K_2024.pdf                → AAPL,      10-K, FY2024
 *   Apple_10Q-2025-Q3.pdf             → Apple,     10-Q, FY2025 Q3
 *   Microsoft_10K-2024-06-30.pdf      → Microsoft, 10-K, FY2024, period end 2024-06-30
 * </pre>
 *
 * <p>Parsing is a convenience, never an authority: any field supplied explicitly on the
 * request overrides what the filename says. A filename is metadata a human typed, and a
 * wrong fiscal year silently corrupts every future version comparison.
 */
@Component
public class FilingNameParser {

    private static final Pattern FILENAME = Pattern.compile(
            "^(?<company>.+?)"                                   // Apple
                    + "[ _.\\-]+"
                    + "(?<form>10[ _.\\-]?[KQkq]"                // 10K / 10-K / 10Q
                    + "|8[ _.\\-]?[Kk]"                          // 8-K
                    + "|DEF[ _.\\-]?14A)"                        // DEF 14A
                    + "[ _.\\-]+"
                    + "(?<period>.+?)"                           // 2025 / 2025-Q3 / 2024-06-30
                    + "\\.[A-Za-z0-9]{2,5}$");

    private static final Pattern PERIOD_END = Pattern.compile("(\\d{4})[ _.\\-](\\d{2})[ _.\\-](\\d{2})");
    private static final Pattern YEAR_QUARTER = Pattern.compile("(\\d{4})[ _.\\-]?([Qq][1-4])");
    private static final Pattern QUARTER_YEAR = Pattern.compile("([Qq][1-4])[ _.\\-]?(\\d{4})");
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    /** Everything the filename could yield. Any field may be null. */
    public record ParsedName(
            String companyName,
            FilingType formType,
            Integer fiscalYear,
            String fiscalPeriod,
            LocalDate periodEndDate) {
    }

    /**
     * @return the parsed metadata, or empty when the filename does not follow the convention
     */
    public Optional<ParsedName> parse(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = FILENAME.matcher(filename.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String company = matcher.group("company")
                .replaceAll("[ _.\\-]+", " ")
                .strip();
        FilingType formType = FilingType.from(matcher.group("form"));
        String period = matcher.group("period");

        LocalDate periodEnd = parsePeriodEnd(period);
        String quarter = parseQuarter(period);
        Integer year = parseYear(period, periodEnd);

        return Optional.of(new ParsedName(
                company.isBlank() ? null : company, formType, year, quarter, periodEnd));
    }

    private static LocalDate parsePeriodEnd(String period) {
        Matcher matcher = PERIOD_END.matcher(period);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        }
        catch (NumberFormatException | java.time.DateTimeException ex) {
            // A filename like Foo_10K-2025-99-99.pdf: keep the year, drop the bad date.
            return null;
        }
    }

    private static String parseQuarter(String period) {
        Matcher yearFirst = YEAR_QUARTER.matcher(period);
        if (yearFirst.find()) {
            return yearFirst.group(2).toUpperCase(Locale.ROOT);
        }
        Matcher quarterFirst = QUARTER_YEAR.matcher(period);
        if (quarterFirst.find()) {
            return quarterFirst.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static Integer parseYear(String period, LocalDate periodEnd) {
        if (periodEnd != null) {
            return periodEnd.getYear();
        }
        Matcher matcher = YEAR.matcher(period);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }
}
