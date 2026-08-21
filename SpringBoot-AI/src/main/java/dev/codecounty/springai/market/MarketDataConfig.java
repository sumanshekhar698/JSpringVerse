package dev.codecounty.springai.market;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the configured {@link StockDataProvider}.
 *
 * <p>Only one provider ships today. When a second is added, register it here with
 * {@code @ConditionalOnProperty(name = "jspringverse.market.provider", havingValue = "...")}
 * so the choice stays a config change rather than a code change.
 */
@Configuration(proxyBeanMethods = false)
public class MarketDataConfig {

    @Bean
    public StockDataProvider yahooFinanceStockDataProvider(MarketDataProperties properties) {
        return new YahooFinanceStockDataProvider(properties);
    }
}
