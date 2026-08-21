package dev.codecounty.springai.market;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * A point-in-time snapshot of a listed US equity.
 *
 * <p>Fields are nullable on purpose: not every venue publishes every field for every
 * instrument (ETFs have no fifty-two week range on some feeds, pre-IPO tickers have no
 * previous close). The tool layer renders nulls as "n/a" rather than failing the call.
 */
public record StockQuote(
        String symbol,
        String companyName,
        String exchange,
        String currency,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        BigDecimal fiftyTwoWeekHigh,
        BigDecimal fiftyTwoWeekLow,
        Long volume,
        Instant asOf) {

    /** Absolute change against the previous close, or {@code null} if either side is unknown. */
    public BigDecimal change() {
        if (price == null || previousClose == null) {
            return null;
        }
        return price.subtract(previousClose);
    }

    /** Percentage change against the previous close, or {@code null} if it cannot be computed. */
    public BigDecimal changePercent() {
        BigDecimal change = change();
        if (change == null || previousClose.signum() == 0) {
            return null;
        }
        return change.divide(previousClose, new MathContext(6))
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
