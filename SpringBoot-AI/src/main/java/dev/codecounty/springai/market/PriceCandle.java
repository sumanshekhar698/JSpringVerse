package dev.codecounty.springai.market;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One OHLCV bar of a daily price series. */
public record PriceCandle(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume) {
}
