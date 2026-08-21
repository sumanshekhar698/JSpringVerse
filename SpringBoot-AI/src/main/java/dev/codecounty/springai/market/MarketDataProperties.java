package dev.codecounty.springai.market;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Tunables for the market data layer.
 *
 * <p>Prefixed {@code jspringverse.market.*}.
 */
@Validated
@ConfigurationProperties(prefix = "jspringverse.market")
public record MarketDataProperties(

        /** Which {@link StockDataProvider} bean to use. Matches {@link StockDataProvider#name()}. */
        String provider,

        /** Base URL of the Yahoo Finance query host. */
        @NotBlank String yahooBaseUrl,

        /**
         * Yahoo rejects requests carrying a default JDK/Java user agent, so a browser-like
         * value is required rather than cosmetic.
         */
        @NotBlank String userAgent,

        /** Per-request timeout against the vendor. */
        Duration timeout,

        /** How long a quote may be served from cache before it is re-fetched. */
        Duration quoteCacheTtl,

        /** Upper bound on results returned by a symbol search. */
        @Positive int maxSearchResults) {

    public MarketDataProperties {
        provider = (provider == null || provider.isBlank()) ? "yahoo" : provider;
        yahooBaseUrl = (yahooBaseUrl == null || yahooBaseUrl.isBlank())
                ? "https://query1.finance.yahoo.com" : yahooBaseUrl;
        userAgent = (userAgent == null || userAgent.isBlank())
                ? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JSpringVerse/1.0" : userAgent;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        quoteCacheTtl = quoteCacheTtl == null ? Duration.ofSeconds(30) : quoteCacheTtl;
        maxSearchResults = maxSearchResults <= 0 ? 10 : maxSearchResults;
    }
}
