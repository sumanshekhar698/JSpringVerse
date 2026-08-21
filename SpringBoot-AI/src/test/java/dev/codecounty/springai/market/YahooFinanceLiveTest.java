package dev.codecounty.springai.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live contract test against Yahoo Finance.
 *
 * <p>These endpoints are undocumented and can change without notice, which is precisely why
 * this test exists — it is the only thing that will tell you the free feed stopped working.
 * It is opt-in so a normal build never depends on network access or on Yahoo being up:
 *
 * <pre>
 *   $env:MARKET_LIVE_TEST = "true"; .\mvnw.cmd test -Dtest=YahooFinanceLiveTest
 * </pre>
 *
 * <p>Assertions check shape and plausibility rather than exact values, since prices move.
 */
@EnabledIfEnvironmentVariable(named = "MARKET_LIVE_TEST", matches = "true")
class YahooFinanceLiveTest {

    private final StockDataProvider provider = new YahooFinanceStockDataProvider(
            new MarketDataProperties(
                    "yahoo",
                    "https://query1.finance.yahoo.com",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JSpringVerse/1.0",
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(30),
                    10));

    @Test
    @DisplayName("fetches a live quote for a large-cap US stock")
    void fetchesQuote() {
        StockQuote quote = provider.getQuote("AAPL");

        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.companyName()).containsIgnoringCase("Apple");
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.price()).isNotNull();
        assertThat(quote.price().doubleValue()).isPositive();
        assertThat(quote.asOf()).isNotNull();

        System.out.printf("AAPL %s %s (prev close %s, change %s)%n",
                quote.price(), quote.currency(), quote.previousClose(), quote.changePercent());
    }

    @Test
    @DisplayName("normalises lower-case input and handles dotted tickers")
    void normalisesSymbols() {
        assertThat(provider.getQuote("msft").symbol()).isEqualTo("MSFT");
        assertThat(provider.getQuote("BRK-B").price()).isNotNull();
    }

    @Test
    @DisplayName("fetches daily history in chronological order")
    void fetchesHistory() {
        List<PriceCandle> candles = provider.getDailyHistory("MSFT", "1mo");

        assertThat(candles).isNotEmpty();
        assertThat(candles).allSatisfy(candle -> {
            assertThat(candle.date()).isNotNull();
            assertThat(candle.close()).isNotNull();
        });
        assertThat(candles.getFirst().date()).isBefore(candles.getLast().date());

        System.out.printf("MSFT 1mo: %d sessions, %s to %s%n",
                candles.size(), candles.getFirst().date(), candles.getLast().date());
    }

    @Test
    @DisplayName("resolves a company name to a ticker")
    void searchesSymbols() {
        List<SymbolMatch> matches = provider.searchSymbols("Nvidia", 5);

        assertThat(matches).isNotEmpty();
        assertThat(matches).extracting(SymbolMatch::symbol).contains("NVDA");

        matches.forEach(match -> System.out.printf("  %s — %s [%s]%n",
                match.symbol(), match.name(), match.exchange()));
    }

    @Test
    @DisplayName("reports an unknown ticker as not found rather than returning junk")
    void rejectsUnknownSymbol() {
        assertThatThrownBy(() -> provider.getQuote("ZZZZQQ"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("ZZZZQQ");
    }

    @Test
    @DisplayName("rejects malformed symbols before making a network call")
    void rejectsMalformedSymbol() {
        assertThatThrownBy(() -> provider.getQuote("../../etc/passwd"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("not a valid ticker");
    }

    @Test
    @DisplayName("rejects an unsupported history range")
    void rejectsBadRange() {
        assertThatThrownBy(() -> provider.getDailyHistory("AAPL", "7 years"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("Unsupported range");
    }
}
