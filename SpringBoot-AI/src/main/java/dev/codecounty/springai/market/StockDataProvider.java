package dev.codecounty.springai.market;

import java.util.List;

/**
 * Abstraction over a US equity market data vendor.
 *
 * <p>The agent's tools depend on this interface rather than on any one vendor. Swapping
 * Yahoo Finance for Alpha Vantage, Finnhub or a paid feed is then a matter of adding an
 * implementation and flipping {@code jspringverse.market.provider} — no change to the
 * tool layer, the prompts, or the controllers.
 */
public interface StockDataProvider {

    /** Identifier matched against {@code jspringverse.market.provider}. */
    String name();

    /**
     * Latest available quote for a ticker.
     *
     * @throws MarketDataException.SymbolNotFound if the venue has no such instrument
     * @throws MarketDataException                if the vendor call fails
     */
    StockQuote getQuote(String symbol);

    /**
     * Daily OHLCV bars, oldest first.
     *
     * @param symbol  ticker, e.g. {@code MSFT}
     * @param range   vendor-neutral lookback window, e.g. {@code 1mo}, {@code 6mo}, {@code 1y}
     */
    List<PriceCandle> getDailyHistory(String symbol, String range);

    /** Free-text company or ticker lookup, best match first. */
    List<SymbolMatch> searchSymbols(String query, int limit);
}
