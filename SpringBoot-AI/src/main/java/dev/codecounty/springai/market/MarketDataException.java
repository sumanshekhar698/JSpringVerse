package dev.codecounty.springai.market;

/**
 * Raised when a market data provider cannot satisfy a request.
 *
 * <p>Deliberately unchecked so {@code @Tool} method signatures stay clean — Spring AI
 * derives the tool schema from the signature, and checked exceptions add nothing there.
 */
public class MarketDataException extends RuntimeException {

    public MarketDataException(String message) {
        super(message);
    }

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Thrown when the symbol is well-formed but the venue has no such instrument. */
    public static class SymbolNotFound extends MarketDataException {
        public SymbolNotFound(String symbol) {
            super("No US-listed instrument found for symbol '%s'".formatted(symbol));
        }
    }
}
