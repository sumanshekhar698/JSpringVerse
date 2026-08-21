package dev.codecounty.springai.filings;

import dev.codecounty.springai.market.MarketDataException;
import dev.codecounty.springai.market.StockDataProvider;
import dev.codecounty.springai.market.SymbolMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Assembles {@link FilingMetadata} from the filename plus whatever the caller supplied.
 *
 * <p>Precedence is always <b>explicit request parameter → filename → lookup</b>. A filename
 * is metadata a human typed and is frequently wrong; an explicit parameter is a deliberate
 * assertion. Getting this order backwards means a typo'd filename silently overrides a
 * correct request, and a wrong fiscal year corrupts every future version comparison.
 */
@Service
public class FilingMetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(FilingMetadataResolver.class);

    private final FilingNameParser nameParser;
    private final StockDataProvider stockDataProvider;

    public FilingMetadataResolver(FilingNameParser nameParser, StockDataProvider stockDataProvider) {
        this.nameParser = nameParser;
        this.stockDataProvider = stockDataProvider;
    }

    /** Everything a caller may state explicitly. Any field may be null. */
    public record Overrides(
            String ticker,
            String companyName,
            String formType,
            Integer fiscalYear,
            String fiscalPeriod,
            LocalDate periodEndDate,
            LocalDate filingDate) {
    }

    /**
     * @param filename  the uploaded filename, e.g. {@code Apple_10K-2025.pdf}
     * @param overrides explicit values from the request; wins over anything parsed
     * @throws FilingIngestionException if no ticker can be determined
     */
    public FilingMetadata resolve(String filename, Overrides overrides) {
        Optional<FilingNameParser.ParsedName> parsed = nameParser.parse(filename);
        if (parsed.isEmpty()) {
            log.debug("Filename '{}' does not match the <Company>_<Form>-<Period> convention", filename);
        }

        String companyName = firstNonBlank(
                overrides.companyName(),
                parsed.map(FilingNameParser.ParsedName::companyName).orElse(null));

        FilingType formType = overrides.formType() != null && !overrides.formType().isBlank()
                ? FilingType.from(overrides.formType())
                : parsed.map(FilingNameParser.ParsedName::formType).orElse(FilingType.OTHER);

        Integer fiscalYear = overrides.fiscalYear() != null
                ? overrides.fiscalYear()
                : parsed.map(FilingNameParser.ParsedName::fiscalYear).orElse(null);

        String fiscalPeriod = firstNonBlank(
                overrides.fiscalPeriod(),
                parsed.map(FilingNameParser.ParsedName::fiscalPeriod).orElse(null));

        LocalDate periodEnd = overrides.periodEndDate() != null
                ? overrides.periodEndDate()
                : parsed.map(FilingNameParser.ParsedName::periodEndDate).orElse(null);

        String ticker = firstNonBlank(overrides.ticker(), resolveTicker(companyName));
        if (ticker == null) {
            throw new FilingIngestionException(
                    ("Could not determine a ticker for '%s'. Name the file like "
                            + "Apple_10K-2025.pdf, or pass ticker= explicitly.").formatted(filename));
        }

        return new FilingMetadata(
                null, ticker, companyName, formType, fiscalYear,
                fiscalPeriod, periodEnd, overrides.filingDate(), filename);
    }

    /**
     * Resolves a company name to a ticker using the market data provider.
     *
     * <p>Best-effort: the provider is an unofficial free feed, and ingestion must not fail
     * because a lookup was rate-limited. A failure here surfaces as "pass ticker explicitly"
     * rather than as a stack trace.
     */
    private String resolveTicker(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        try {
            List<SymbolMatch> matches = stockDataProvider.searchSymbols(companyName, 5);
            return matches.stream()
                    // Prefer a common-stock listing over an ETF or index that happens to
                    // share the name.
                    .filter(match -> match.quoteType() == null
                            || "EQUITY".equalsIgnoreCase(match.quoteType()))
                    .map(SymbolMatch::symbol)
                    .filter(symbol -> symbol != null && !symbol.contains("."))
                    .findFirst()
                    .map(symbol -> {
                        log.info("Resolved company '{}' to ticker {}", companyName, symbol);
                        return symbol.toUpperCase(Locale.ROOT);
                    })
                    .orElse(null);
        }
        catch (MarketDataException ex) {
            log.warn("Ticker lookup for '{}' failed: {}", companyName, ex.getMessage());
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }
}
