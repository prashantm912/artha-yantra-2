package in.arthayantra.marketdata.kite.upstoxfeed;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.kite.LastSeenProvider;
import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.UpstoxLiveTickFeed;
import in.arthayantra.marketdata.kite.ticker.LiveTickerFeed;
import in.arthayantra.marketdata.kite.ticker.SubscriptionRegistry;
import in.arthayantra.marketdata.kite.ticker.TickerHandle;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Live-profile wiring for the Wave-U3 direct-Upstox v3 WS ticker — the {@link MarketFeed} binds ONLY
 * when {@code artha.marketdata.source.ticker=upstox}; absent ⇒ the Kite {@code LiveTickerFeed} stays
 * the bound default (the unchanged fallback, {@code LiveKiteConfig#liveMarketFeed}). The two are
 * mutually-exclusive {@code @ConditionalOnProperty} branches, so exactly one feed ever runs (the
 * {@code FeedPipeline} autowires a single {@code MarketFeed}).
 *
 * <p><b>GATED, default-off (§17.3 scalp-latency gate):</b> the Upstox WS path is BUILT but dormant
 * until a live A/B measures place-ack/tick latency vs Kite-WS for ≥1 session and the owner flips the
 * flag + deploys. Built on the login-free 1-yr analytics token (proven 2026-06-24: the analytics
 * token authorizes the v3 WS), so the live tick feed survives a missed daily Kite login.
 *
 * <p>It REUSES the existing {@link LiveTickerFeed} supervisor verbatim — registry replay, the {@code
 * kite-ticker} breaker, reconnect counting, post-reconnect gap-heal, {@code FeedRearm} — by supplying
 * an Upstox-backed {@link TickerHandle} ({@link UpstoxTickerHandle}) over the {@link UpstoxLiveTickFeed}
 * port (implemented in {@code options}); so candles/charts/signals stay source-agnostic and the
 * subscribed symbol set ({@link SubscriptionRegistry}) is shared. Lives in {@code kite} (intra-module
 * access to {@code kite.ticker}) and reaches the {@code upstox} WS client only through the {@code
 * kite}-declared port, so no {@code kite → upstox} edge / no Modulith cycle.
 */
@Configuration
@Profile("live")
@ConditionalOnProperty(name = "artha.marketdata.source.ticker", havingValue = "upstox")
public class UpstoxMarketFeedConfig {

  /**
   * The Upstox-backed {@link MarketFeed} — the SAME {@link LiveTickerFeed} supervisor as Kite, driven
   * through an Upstox {@link TickerHandle} over the {@link UpstoxLiveTickFeed} port. The handle factory
   * is lazy so a re-arm rebuilds it.
   */
  @Bean
  public MarketFeed upstoxMarketFeed(
      UpstoxLiveTickFeed upstoxLiveTickFeed,
      SubscriptionRegistry registry,
      LastSeenProvider lastSeenProvider,
      GapBackfiller gapBackfiller,
      MarketCalendar calendar,
      CircuitBreakerRegistry circuitBreakers,
      Clock clock,
      MeterRegistry meterRegistry) {
    Supplier<TickerHandle> factory = () -> new UpstoxTickerHandle(upstoxLiveTickFeed);
    return new LiveTickerFeed(
        factory,
        registry,
        lastSeenProvider,
        gapBackfiller,
        calendar,
        circuitBreakers.circuitBreaker("kite-ticker"),
        clock,
        meterRegistry);
  }
}
