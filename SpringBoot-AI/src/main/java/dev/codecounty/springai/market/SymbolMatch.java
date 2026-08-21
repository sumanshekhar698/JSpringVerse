package dev.codecounty.springai.market;

/**
 * A candidate ticker returned by a symbol lookup.
 *
 * <p>Lets the agent resolve "Apple" or "the chip company Nvidia" to a tradeable symbol
 * before calling the quote tool, instead of guessing at a ticker.
 */
public record SymbolMatch(
        String symbol,
        String name,
        String exchange,
        String quoteType) {
}
