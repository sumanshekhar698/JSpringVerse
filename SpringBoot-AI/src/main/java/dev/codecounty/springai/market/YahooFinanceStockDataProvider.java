package dev.codecounty.springai.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
// Spring Boot 4 ships Jackson 3 (tools.jackson) as the HTTP message converter backend.
// Importing com.fasterxml.jackson (Jackson 2, still present transitively) fails at runtime
// with "Type definition error: JsonNode" because no converter can produce that type.
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link StockDataProvider} backed by Yahoo Finance's public {@code query1} endpoints.
 *
 * <p>Chosen because it needs no API key, no signup and no payment method. The trade-off is
 * that these endpoints are undocumented and unsupported: Yahoo may change the shape or
 * throttle without notice. Everything here is therefore defensive — responses are read
 * field-by-field out of a {@link JsonNode} rather than bound to a fixed DTO, so an added
 * or renamed field degrades one value instead of failing the whole call.
 *
 * <p>For production-grade SLAs, add a keyed provider (Alpha Vantage or Finnhub, both free
 * with an email address and no card) behind the same interface and switch
 * {@code jspringverse.market.provider}.
 */
public class YahooFinanceStockDataProvider implements StockDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceStockDataProvider.class);

    /** US tickers: letters, digits, dot and dash (BRK.B, RDS-A). Bounds the URL path segment. */
    private static final Pattern VALID_SYMBOL = Pattern.compile("^[A-Z0-9.\\-]{1,12}$");

    /** Ranges Yahoo's chart endpoint accepts. Anything else is rejected before the call. */
    private static final Set<String> VALID_RANGES =
            Set.of("1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max");

    private static final ZoneId US_MARKET_ZONE = ZoneId.of("America/New_York");

    private final RestClient restClient;
    private final MarketDataProperties properties;
    private final TtlCache<String, StockQuote> quoteCache;

    public YahooFinanceStockDataProvider(MarketDataProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.yahooBaseUrl())
                .requestFactory(requestFactory)
                // Yahoo returns 403 to the default JDK user agent. This header is required.
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();

        this.quoteCache = new TtlCache<>(properties.quoteCacheTtl(), 512);
    }

    @Override
    public String name() {
        return "yahoo";
    }

    @Override
    public StockQuote getQuote(String symbol) {
        String ticker = normalizeSymbol(symbol);
        return quoteCache.get(ticker, this::fetchQuote);
    }

    private StockQuote fetchQuote(String ticker) {
        JsonNode meta = fetchChart(ticker, "1d", "1d").path("meta");
        if (meta.isMissingNode() || meta.isEmpty()) {
            throw new MarketDataException.SymbolNotFound(ticker);
        }

        return new StockQuote(
                text(meta, "symbol", ticker),
                firstText(meta, "longName", "shortName"),
                firstText(meta, "fullExchangeName", "exchangeName"),
                text(meta, "currency", "USD"),
                decimal(meta, "regularMarketPrice"),
                // Yahoo exposes the prior close under different keys depending on range.
                firstDecimal(meta, "previousClose", "chartPreviousClose"),
                decimal(meta, "regularMarketDayHigh"),
                decimal(meta, "regularMarketDayLow"),
                decimal(meta, "fiftyTwoWeekHigh"),
                decimal(meta, "fiftyTwoWeekLow"),
                longValue(meta, "regularMarketVolume"),
                epochSeconds(meta, "regularMarketTime"));
    }

    @Override
    public List<PriceCandle> getDailyHistory(String symbol, String range) {
        String ticker = normalizeSymbol(symbol);
        String window = (range == null || range.isBlank()) ? "1mo" : range.trim().toLowerCase(Locale.ROOT);
        if (!VALID_RANGES.contains(window)) {
            throw new MarketDataException(
                    "Unsupported range '%s'. Supported: %s".formatted(range, VALID_RANGES));
        }

        JsonNode result = fetchChart(ticker, window, "1d");
        JsonNode timestamps = result.path("timestamp");
        JsonNode ohlcv = result.path("indicators").path("quote").path(0);

        if (!timestamps.isArray() || ohlcv.isMissingNode()) {
            throw new MarketDataException(
                    "No price history available for '%s' over %s".formatted(ticker, window));
        }

        List<PriceCandle> candles = new ArrayList<>(timestamps.size());
        for (int i = 0; i < timestamps.size(); i++) {
            BigDecimal close = elementDecimal(ohlcv.path("close"), i);
            // Yahoo pads the arrays with nulls for halted or non-trading sessions.
            if (close == null) {
                continue;
            }
            LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                    .atZone(US_MARKET_ZONE)
                    .toLocalDate();
            candles.add(new PriceCandle(
                    date,
                    elementDecimal(ohlcv.path("open"), i),
                    elementDecimal(ohlcv.path("high"), i),
                    elementDecimal(ohlcv.path("low"), i),
                    close,
                    elementLong(ohlcv.path("volume"), i)));
        }

        if (candles.isEmpty()) {
            throw new MarketDataException(
                    "No price history available for '%s' over %s".formatted(ticker, window));
        }
        return candles;
    }

    @Override
    public List<SymbolMatch> searchSymbols(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new MarketDataException("Search query must not be empty");
        }
        int capped = Math.clamp(limit, 1, properties.maxSearchResults());

        JsonNode body = exchange(uriBuilder -> uriBuilder
                .path("/v1/finance/search")
                .queryParam("q", query.trim())
                .queryParam("quotesCount", capped)
                .queryParam("newsCount", 0)
                .build());

        JsonNode quotes = body.path("quotes");
        if (!quotes.isArray()) {
            return List.of();
        }

        List<SymbolMatch> matches = new ArrayList<>(quotes.size());
        for (JsonNode quote : quotes) {
            String ticker = quote.path("symbol").asString(null);
            if (ticker == null) {
                continue;
            }
            matches.add(new SymbolMatch(
                    ticker,
                    firstText(quote, "longname", "shortname"),
                    firstText(quote, "exchDisp", "exchange"),
                    quote.path("quoteType").asString(null)));
            if (matches.size() == capped) {
                break;
            }
        }
        return matches;
    }

    /** Fetches {@code chart.result[0]}, translating Yahoo's error envelope into our exceptions. */
    private JsonNode fetchChart(String ticker, String range, String interval) {
        JsonNode body = exchange(uriBuilder -> uriBuilder
                .path("/v8/finance/chart/{symbol}")
                .queryParam("range", range)
                .queryParam("interval", interval)
                .build(ticker));

        JsonNode error = body.path("chart").path("error");
        if (!error.isNull() && !error.isMissingNode()) {
            String code = error.path("code").asString("");
            if ("Not Found".equalsIgnoreCase(code) || code.contains("NotFound")) {
                throw new MarketDataException.SymbolNotFound(ticker);
            }
            throw new MarketDataException("Yahoo Finance error for '%s': %s"
                    .formatted(ticker, error.path("description").asString(code)));
        }

        JsonNode result = body.path("chart").path("result").path(0);
        if (result.isMissingNode() || result.isEmpty()) {
            throw new MarketDataException.SymbolNotFound(ticker);
        }
        return result;
    }

    private JsonNode exchange(java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFunction) {
        try {
            JsonNode body = restClient.get()
                    .uri(uriFunction)
                    .retrieve()
                    // Yahoo answers unknown symbols with 404 *and* a useful JSON error body,
                    // so suppress the default throw and let the caller read the envelope.
                    .onStatus(HttpStatusCode::isError, (request, response) ->
                            log.debug("Yahoo Finance returned {} for {}", response.getStatusCode(), request.getURI()))
                    .body(JsonNode.class);

            if (body == null) {
                throw new MarketDataException("Yahoo Finance returned an empty response");
            }
            return body;
        }
        catch (RestClientException ex) {
            throw new MarketDataException(
                    "Could not reach Yahoo Finance: " + ex.getMessage(), ex);
        }
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new MarketDataException("Ticker symbol must not be empty");
        }
        String ticker = symbol.trim().toUpperCase(Locale.ROOT);
        if (!VALID_SYMBOL.matcher(ticker).matches()) {
            throw new MarketDataException(
                    "'%s' is not a valid ticker symbol".formatted(symbol));
        }
        return ticker;
    }

    // --- Null-tolerant JsonNode readers -------------------------------------------------

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asString(null);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = decimal(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private static Instant epochSeconds(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? Instant.ofEpochSecond(value.asLong()) : Instant.now();
    }

    private static BigDecimal elementDecimal(JsonNode array, int index) {
        JsonNode value = array.path(index);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static Long elementLong(JsonNode array, int index) {
        JsonNode value = array.path(index);
        return value.isNumber() ? value.asLong() : null;
    }
}
