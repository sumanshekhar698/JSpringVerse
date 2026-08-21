package dev.codecounty.springai.tools;

import dev.codecounty.springai.market.MarketDataException;
import dev.codecounty.springai.market.PriceCandle;
import dev.codecounty.springai.market.StockDataProvider;
import dev.codecounty.springai.market.StockQuote;
import dev.codecounty.springai.market.SymbolMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract these tests pin down is behavioural, not cosmetic: a tool that throws
 * aborts the model's tool-calling loop, and a tool that omits a failure invites the model
 * to invent a number. Both are tested explicitly.
 */
class StockToolsTest {

    @Test
    @DisplayName("renders a quote with change and percentage derived from previous close")
    void rendersQuote() {
        StockQuote quote = new StockQuote(
                "AAPL", "Apple Inc.", "NasdaqGS", "USD",
                new BigDecimal("200.00"), new BigDecimal("190.00"),
                new BigDecimal("201.50"), new BigDecimal("189.10"),
                new BigDecimal("260.10"), new BigDecimal("164.08"),
                52_000_000L, Instant.parse("2026-07-24T20:00:00Z"));

        StockTools tools = new StockTools(new StubProvider(quote));
        String output = tools.getStockQuote("aapl");

        assertThat(output)
                .contains("Apple Inc.")
                .contains("Price: 200.00 USD")
                .contains("Change: 10.00 (5.26%)")
                .contains("Volume: 52,000,000");
    }

    @Test
    @DisplayName("returns an error string instead of throwing when the provider fails")
    void returnsErrorRatherThanThrowing() {
        StockTools tools = new StockTools(new FailingProvider(
                new MarketDataException.SymbolNotFound("ZZZZ")));

        String output = tools.getStockQuote("ZZZZ");

        // Must not throw: an exception here would end the agent's turn.
        assertThat(output)
                .startsWith("Error:")
                .contains("ZZZZ")
                // The instruction not to fabricate is part of the contract, not decoration.
                .contains("Do not invent a price");
    }

    @Test
    @DisplayName("renders nullable fields as n/a rather than failing the call")
    void handlesMissingFields() {
        StockQuote sparse = new StockQuote(
                "XYZ", null, null, "USD",
                new BigDecimal("12.34"), null, null, null, null, null, null,
                Instant.parse("2026-07-24T20:00:00Z"));

        StockTools tools = new StockTools(new StubProvider(sparse));
        String output = tools.getStockQuote("XYZ");

        assertThat(output)
                .contains("Price: 12.34 USD")
                .contains("Change: n/a (n/a)")
                .contains("Volume: n/a");
    }

    @Test
    @DisplayName("keeps working tickers when one symbol in a comparison fails")
    void comparisonSurvivesOneBadTicker() {
        StockQuote good = new StockQuote(
                "MSFT", "Microsoft Corp", "NasdaqGS", "USD",
                new BigDecimal("400.00"), new BigDecimal("396.00"),
                null, null, null, null, null, Instant.now());

        StockTools tools = new StockTools(new StockDataProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public StockQuote getQuote(String symbol) {
                if ("MSFT".equalsIgnoreCase(symbol)) {
                    return good;
                }
                throw new MarketDataException.SymbolNotFound(symbol);
            }

            @Override
            public List<PriceCandle> getDailyHistory(String symbol, String range) {
                return List.of();
            }

            @Override
            public List<SymbolMatch> searchSymbols(String query, int limit) {
                return List.of();
            }
        });

        String output = tools.compareStockQuotes("MSFT, NOTREAL");

        assertThat(output).contains("MSFT | 400.00");
        assertThat(output).contains("NOTREAL | unavailable");
    }

    @Test
    @DisplayName("summarises history and samples long series to bound prompt size")
    void summarisesAndSamplesHistory() {
        List<PriceCandle> candles = new java.util.ArrayList<>();
        LocalDate start = LocalDate.of(2025, 1, 2);
        for (int i = 0; i < 250; i++) {
            BigDecimal close = new BigDecimal(100 + i);
            candles.add(new PriceCandle(start.plusDays(i), close, close, close, close, 1_000L));
        }

        StockTools tools = new StockTools(new HistoryProvider(candles));
        String output = tools.getStockPriceHistory("TEST", "1y");

        assertThat(output).contains("250 sessions");
        assertThat(output).contains("First close: 100.00");
        assertThat(output).contains("Last close: 349.00");
        assertThat(output).contains("Period return: 249.00%");
        assertThat(output).contains("evenly sampled");

        // Only the sampled bars are rendered, otherwise a 5y window would dominate the prompt.
        long renderedBars = output.lines().filter(line -> line.startsWith("2025-")).count();
        assertThat(renderedBars).isLessThanOrEqualTo(31);
    }

    // --- Stubs ---------------------------------------------------------------------------

    private record StubProvider(StockQuote quote) implements StockDataProvider {
        @Override
        public String name() {
            return "stub";
        }

        @Override
        public StockQuote getQuote(String symbol) {
            return quote;
        }

        @Override
        public List<PriceCandle> getDailyHistory(String symbol, String range) {
            return List.of();
        }

        @Override
        public List<SymbolMatch> searchSymbols(String query, int limit) {
            return List.of();
        }
    }

    private record FailingProvider(MarketDataException failure) implements StockDataProvider {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public StockQuote getQuote(String symbol) {
            throw failure;
        }

        @Override
        public List<PriceCandle> getDailyHistory(String symbol, String range) {
            throw failure;
        }

        @Override
        public List<SymbolMatch> searchSymbols(String query, int limit) {
            throw failure;
        }
    }

    private record HistoryProvider(List<PriceCandle> candles) implements StockDataProvider {
        @Override
        public String name() {
            return "history";
        }

        @Override
        public StockQuote getQuote(String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PriceCandle> getDailyHistory(String symbol, String range) {
            return candles;
        }

        @Override
        public List<SymbolMatch> searchSymbols(String query, int limit) {
            return List.of();
        }
    }
}
