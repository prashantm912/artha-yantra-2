package in.arthayantra.marketdata.kite;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A short-TTL cache in front of the INDIA VIX spot quote.
 *
 * <p><b>Why this exists (H31):</b> {@code LiveQuoteGateway} routes every call through the {@code
 * kite-quote} limiter, which is <b>1 call per second</b> for the whole service. Measured live on
 * 2026-08-21, {@code GET /api/v1/market/vix} averaged <b>1.530 s</b> across 31 calls while doing
 * nothing but one quote fetch — that time is limiter QUEUEING behind the futures/OI/term-structure
 * readers, not Kite being slow. {@code DayContextService.dayContext()} makes the same call inline,
 * which is why it averaged <b>1.920 s</b> against the insight sweep's 2000 ms read timeout and had
 * <b>6 of 8</b> of its responses discarded that morning. Caching this one quote removes a
 * competitor from a 1/s queue; it is the remedy the ledger asked for instead of widening the
 * timeout, because it makes the call go away rather than hiding it.
 *
 * <p><b>Deliberately narrow.</b> This is the VIX level only — a display/context reading that feeds
 * a coarse regime band and a UI header. It must NEVER be used to price a fill, size a position, or
 * resolve an exit: those paths need the live tick and have their own freshness doctrine. It takes
 * the key as an argument only because the two call sites derive it differently (the controller
 * pins {@code NSE:INDIA VIX}; {@code DayContextService} reads {@code artha.context.vix-instrument}),
 * and unifying that config is a separate change.
 *
 * <p><b>Failures are never cached and stale is never served on error.</b> A refresh that throws
 * propagates to the caller, so behaviour on a broken feed is exactly what it was before this class
 * existed. Only a successful quote is stored, and only until its TTL expires.
 */
@Component
public class VixQuoteCache {

  private final QuoteGateway quoteGateway;
  private final Clock clock;
  private final Duration ttl;
  private final MeterRegistry meterRegistry;
  private final Map<InstrumentKey, Entry> cache = new HashMap<>();

  private record Entry(QuoteGateway.Quote quote, Instant expiresAt) {}

  /** Wires the gateway plus the TTL knob ({@code 0} disables caching entirely). */
  public VixQuoteCache(
      QuoteGateway quoteGateway,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${artha.market.vix-quote-cache-ttl-seconds:30}") long ttlSeconds) {
    this.quoteGateway = quoteGateway;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    this.ttl = Duration.ofSeconds(Math.max(0, ttlSeconds));
  }

  /**
   * The VIX quote, served from cache when a previous read is still inside its TTL.
   *
   * @param key the VIX instrument key
   * @return the quote, or empty when the gateway has none (off-hours / mock) — an empty result is
   *     NOT cached, so a feed that comes back is picked up on the very next call
   */
  public synchronized Optional<QuoteGateway.Quote> quote(InstrumentKey key) {
    Instant now = clock.instant();
    Entry cached = cache.get(key);
    if (cached != null && now.isBefore(cached.expiresAt())) {
      meterRegistry.counter("ay_vix_quote_cache_hit_total").increment();
      return Optional.of(cached.quote());
    }
    meterRegistry.counter("ay_vix_quote_cache_miss_total").increment();
    QuoteGateway.Quote fresh = quoteGateway.quotes(List.of(key)).get(key);
    if (fresh == null || fresh.lastPrice() == null) {
      cache.remove(key);
      return Optional.empty();
    }
    if (!ttl.isZero()) {
      cache.put(key, new Entry(fresh, now.plus(ttl)));
    }
    return Optional.of(fresh);
  }

  /**
   * Snapshot of the cached keys — test/diagnostic use only. Synchronized on the same monitor as
   * {@link #quote}: the backing map is a plain {@link HashMap}, so an unsynchronized read here
   * would be a genuine data race, not a stale-read nuisance.
   */
  synchronized Map<InstrumentKey, QuoteGateway.Quote> snapshot() {
    Map<InstrumentKey, QuoteGateway.Quote> out = new HashMap<>();
    cache.forEach((k, v) -> out.put(k, v.quote()));
    return out;
  }
}
