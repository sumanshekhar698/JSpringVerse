package dev.codecounty.springai.actuator;

import dev.codecounty.springai.market.MarketDataException;
import dev.codecounty.springai.market.StockDataProvider;
import dev.codecounty.springai.market.StockQuote;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the market data feed is answering.
 *
 * <p>Worth a dedicated indicator because the default provider is an unofficial, unsupported
 * endpoint: when Yahoo changes shape or throttles us, the agent keeps starting and keeps
 * serving requests, it just stops being able to quote a price. Without this the failure is
 * invisible until a user reports it.
 *
 * <p>Probes a mega-cap that is always listed, and reports DOWN rather than throwing so the
 * rest of the health report still renders.
 */
@Component("marketData")
public class MarketDataHealthIndicator implements HealthIndicator {

    /** Liquid, always-listed, and served from the provider's cache on repeated probes. */
    private static final String PROBE_SYMBOL = "AAPL";

    private final StockDataProvider provider;

    public MarketDataHealthIndicator(StockDataProvider provider) {
        this.provider = provider;
    }

    @Override
    public Health health() {
        try {
            StockQuote quote = provider.getQuote(PROBE_SYMBOL);
            return Health.up()
                    .withDetail("provider", provider.name())
                    .withDetail("probeSymbol", PROBE_SYMBOL)
                    .withDetail("price", quote.price())
                    .withDetail("asOf", quote.asOf())
                    .build();
        }
        catch (MarketDataException ex) {
            return Health.down()
                    .withDetail("provider", provider.name())
                    .withDetail("probeSymbol", PROBE_SYMBOL)
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
