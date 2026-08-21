package dev.codecounty.springai.market;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Minimal thread-safe cache with a fixed time-to-live per entry.
 *
 * <p>Exists because an agent typically calls the quote tool several times inside one
 * conversation ("compare AAPL and MSFT, then tell me about AAPL again"), and the free
 * Yahoo endpoint throttles aggressively. A 30-second TTL removes the duplicate calls
 * without ever serving a price stale enough to matter for the questions this agent answers.
 *
 * <p>Deliberately not Spring Cache + Caffeine: one small class beats two dependencies and
 * a cache manager for a single call site. Swap it if a second use case appears.
 */
final class TtlCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
        boolean isLive(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;

    TtlCache(Duration ttl, int maxEntries) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    /** Returns the cached value, or computes, stores and returns a fresh one. */
    V get(K key, Function<K, V> loader) {
        Instant now = Instant.now();
        Entry<V> hit = entries.get(key);
        if (hit != null && hit.isLive(now)) {
            return hit.value();
        }
        V fresh = loader.apply(key);
        // Unbounded growth guard: tickers are low-cardinality, but a hostile or buggy
        // caller could still walk the key space, so drop everything and start over
        // rather than hold memory indefinitely.
        if (entries.size() >= maxEntries) {
            entries.clear();
        }
        entries.put(key, new Entry<>(fresh, now.plus(ttl)));
        return fresh;
    }
}
