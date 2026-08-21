package dev.codecounty.springai.tools;

import dev.codecounty.springai.market.MarketDataException;
import dev.codecounty.springai.market.PriceCandle;
import dev.codecounty.springai.market.StockDataProvider;
import dev.codecounty.springai.market.StockQuote;
import dev.codecounty.springai.market.SymbolMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.StringJoiner;

/**
 * Live US market data exposed to the agent as callable tools.
 *
 * <p>Two conventions hold across every method here, and both matter for agent behaviour:
 *
 * <ol>
 *   <li><b>Failures are returned, not thrown.</b> A thrown exception aborts the tool-calling
 *       loop; a returned sentence lets the model recover on its own — typically by calling
 *       {@code searchStockSymbol} after a bad ticker and retrying.
 *   <li><b>Output is compact prose, not JSON.</b> Models read labelled key-value lines more
 *       reliably than nested JSON, and it costs materially fewer tokens per call.
 * </ol>
 */
@Component
public class StockTools {

    private static final Logger log = LoggerFactory.getLogger(StockTools.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final StockDataProvider provider;

    public StockTools(StockDataProvider provider) {
        this.provider = provider;
    }

    @Tool(description = """
            Get the current market price and daily trading statistics for a US-listed stock or ETF.
            Use this for any question about what a stock is worth right now, how it moved today,
            or where it sits against its 52-week range. Requires an exact ticker symbol — if the
            user gave a company name instead, call searchStockSymbol first.""")
    public String getStockQuote(
            @ToolParam(description = "Exact ticker symbol on a US exchange, e.g. AAPL, MSFT, BRK.B")
            String symbol) {

        log.debug("Tool getStockQuote({})", symbol);
        try {
            StockQuote quote = provider.getQuote(symbol);

            StringJoiner out = new StringJoiner("\n");
            out.add("Quote for %s (%s)".formatted(
                    quote.symbol(), orNa(quote.companyName())));
            out.add("Exchange: %s".formatted(orNa(quote.exchange())));
            out.add("Price: %s %s".formatted(money(quote.price()), quote.currency()));
            out.add("Previous close: %s".formatted(money(quote.previousClose())));
            out.add("Change: %s (%s)".formatted(
                    money(quote.change()), percent(quote.changePercent())));
            out.add("Day range: %s - %s".formatted(money(quote.dayLow()), money(quote.dayHigh())));
            out.add("52-week range: %s - %s".formatted(
                    money(quote.fiftyTwoWeekLow()), money(quote.fiftyTwoWeekHigh())));
            out.add("Volume: %s".formatted(count(quote.volume())));
            out.add("As of: %s".formatted(quote.asOf()));
            return out.toString();
        }
        catch (MarketDataException ex) {
            return toolError(ex);
        }
    }

    @Tool(description = """
            Get daily open/high/low/close/volume history for a US-listed stock over a lookback
            window, plus the total return and highest/lowest close across that window. Use this
            for questions about performance over time, trends, or volatility.""")
    public String getStockPriceHistory(
            @ToolParam(description = "Exact ticker symbol on a US exchange, e.g. NVDA")
            String symbol,
            @ToolParam(description = "Lookback window: 1d, 5d, 1mo, 3mo, 6mo, 1y, 2y, 5y, 10y, ytd or max")
            String range) {

        log.debug("Tool getStockPriceHistory({}, {})", symbol, range);
        try {
            List<PriceCandle> candles = provider.getDailyHistory(symbol, range);

            PriceCandle first = candles.getFirst();
            PriceCandle last = candles.getLast();

            BigDecimal high = candles.stream().map(PriceCandle::close)
                    .max(BigDecimal::compareTo).orElse(null);
            BigDecimal low = candles.stream().map(PriceCandle::close)
                    .min(BigDecimal::compareTo).orElse(null);

            StringJoiner out = new StringJoiner("\n");
            out.add("Daily history for %s over %s (%d sessions, %s to %s)".formatted(
                    symbol.toUpperCase(), range, candles.size(),
                    DATE.format(first.date()), DATE.format(last.date())));
            out.add("First close: %s | Last close: %s | Period return: %s".formatted(
                    money(first.close()), money(last.close()),
                    percent(pctChange(first.close(), last.close()))));
            out.add("Highest close: %s | Lowest close: %s".formatted(money(high), money(low)));
            out.add("");

            // A long window would blow the context budget and add nothing — the model reasons
            // from the summary above plus a representative sample of the actual bars.
            List<PriceCandle> sample = sample(candles, 30);
            out.add("Sampled bars (date, open, high, low, close, volume):");
            for (PriceCandle candle : sample) {
                out.add("%s  %s  %s  %s  %s  %s".formatted(
                        DATE.format(candle.date()),
                        money(candle.open()), money(candle.high()),
                        money(candle.low()), money(candle.close()),
                        count(candle.volume())));
            }
            if (sample.size() < candles.size()) {
                out.add("(%d of %d sessions shown, evenly sampled)"
                        .formatted(sample.size(), candles.size()));
            }
            return out.toString();
        }
        catch (MarketDataException ex) {
            return toolError(ex);
        }
    }

    @Tool(description = """
            Find the ticker symbol for a company by name or partial name. Call this whenever the
            user names a company rather than a ticker, and before retrying a quote that failed
            with an unknown symbol.""")
    public String searchStockSymbol(
            @ToolParam(description = "Company name or partial ticker, e.g. 'Nvidia' or 'Berkshire'")
            String query) {

        log.debug("Tool searchStockSymbol({})", query);
        try {
            List<SymbolMatch> matches = provider.searchSymbols(query, 8);
            if (matches.isEmpty()) {
                return "No matching instruments found for '%s'.".formatted(query);
            }

            StringJoiner out = new StringJoiner("\n");
            out.add("Symbol matches for '%s' (best first):".formatted(query));
            for (SymbolMatch match : matches) {
                out.add("%s — %s [%s, %s]".formatted(
                        match.symbol(), orNa(match.name()),
                        orNa(match.exchange()), orNa(match.quoteType())));
            }
            return out.toString();
        }
        catch (MarketDataException ex) {
            return toolError(ex);
        }
    }

    @Tool(description = """
            Compare the current price and today's move across several US-listed stocks in one
            call. Prefer this over repeated getStockQuote calls when the user asks about two or
            more tickers together.""")
    public String compareStockQuotes(
            @ToolParam(description = "Comma-separated ticker symbols, e.g. 'AAPL,MSFT,GOOGL'")
            String symbols) {

        log.debug("Tool compareStockQuotes({})", symbols);
        if (symbols == null || symbols.isBlank()) {
            return "Error: provide at least one ticker symbol.";
        }

        StringJoiner out = new StringJoiner("\n");
        out.add("Symbol | Price | Change | Change % | Company");
        for (String raw : symbols.split(",")) {
            String ticker = raw.trim();
            if (ticker.isEmpty()) {
                continue;
            }
            try {
                StockQuote quote = provider.getQuote(ticker);
                out.add("%s | %s | %s | %s | %s".formatted(
                        quote.symbol(), money(quote.price()), money(quote.change()),
                        percent(quote.changePercent()), orNa(quote.companyName())));
            }
            catch (MarketDataException ex) {
                // One bad ticker must not void the whole comparison.
                out.add("%s | unavailable (%s)".formatted(ticker, ex.getMessage()));
            }
        }
        return out.toString();
    }

    // --- Formatting helpers -------------------------------------------------------------

    /** Turns an exception into something the model can act on rather than a stack trace. */
    private static String toolError(MarketDataException ex) {
        log.warn("Market data tool call failed: {}", ex.getMessage());
        return "Error: " + ex.getMessage()
                + " Do not invent a price; tell the user the data could not be retrieved.";
    }

    /** Evenly spaced subset preserving the first and last bar. */
    private static List<PriceCandle> sample(List<PriceCandle> candles, int max) {
        if (candles.size() <= max) {
            return candles;
        }
        int step = (int) Math.ceil((double) candles.size() / max);
        List<PriceCandle> sampled = new java.util.ArrayList<>(max);
        for (int i = 0; i < candles.size(); i += step) {
            sampled.add(candles.get(i));
        }
        PriceCandle last = candles.getLast();
        if (!sampled.getLast().equals(last)) {
            sampled.add(last);
        }
        return sampled;
    }

    private static BigDecimal pctChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            return null;
        }
        return to.subtract(from)
                .divide(from, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString() + "%";
    }

    private static String count(Long value) {
        return value == null ? "n/a" : String.format("%,d", value);
    }

    private static String orNa(String value) {
        return (value == null || value.isBlank()) ? "n/a" : value;
    }
}
