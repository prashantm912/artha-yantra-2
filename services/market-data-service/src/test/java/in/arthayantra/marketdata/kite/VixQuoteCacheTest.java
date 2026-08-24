package in.arthayantra.marketdata.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * H31. The point of this cache is that a VIX read stops queueing behind the 1/s {@code kite-quote}
 * limiter, so every test here is about HOW MANY TIMES THE GATEWAY IS CALLED — not about the value
 * that comes back. A test that only asserted the returned quote would pass with the cache deleted.
 */
class VixQuoteCacheTest {

  private static final InstrumentKey VIX = new InstrumentKey("NSE", "INDIA VIX");

  /** A clock the test moves by hand, so TTL expiry is exercised without sleeping. */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-08-21T04:00:00Z");

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }
  }

  private static QuoteGateway.Quote quote(String ltp) {
    return new QuoteGateway.Quote(
        VIX, new BigDecimal(ltp), OffsetDateTime.parse("2026-08-21T09:30:00+05:30"));
  }

  @Test
  void secondReadInsideTheTtlDoesNotCallTheGatewayAgain() {
    QuoteGateway gateway = mock(QuoteGateway.class);
    when(gateway.quotes(any())).thenReturn(Map.of(VIX, quote("12.34")));
    MovableClock clock = new MovableClock();
    VixQuoteCache cache = new VixQuoteCache(gateway, clock, new SimpleMeterRegistry(), 30);

    assertThat(cache.quote(VIX)).map(QuoteGateway.Quote::lastPrice).contains(new BigDecimal("12.34"));
    clock.advance(Duration.ofSeconds(29));
    assertThat(cache.quote(VIX)).map(QuoteGateway.Quote::lastPrice).contains(new BigDecimal("12.34"));

    verify(gateway, times(1)).quotes(any());
    verifyNoMoreInteractions(gateway);
  }

  @Test
  void theGatewayIsCalledAgainOnceTheTtlHasExpired() {
    QuoteGateway gateway = mock(QuoteGateway.class);
    when(gateway.quotes(any())).thenReturn(Map.of(VIX, quote("12.34")), Map.of(VIX, quote("13.50")));
    MovableClock clock = new MovableClock();
    VixQuoteCache cache = new VixQuoteCache(gateway, clock, new SimpleMeterRegistry(), 30);

    cache.quote(VIX);
    clock.advance(Duration.ofSeconds(30));

    assertThat(cache.quote(VIX)).map(QuoteGateway.Quote::lastPrice).contains(new BigDecimal("13.50"));
    verify(gateway, times(2)).quotes(any());
  }

  @Test
  void absentQuoteIsNotCachedSoRecoveringFeedIsPickedUpImmediately() {
    QuoteGateway gateway = mock(QuoteGateway.class);
    when(gateway.quotes(any())).thenReturn(Map.of(), Map.of(VIX, quote("11.00")));
    VixQuoteCache cache = new VixQuoteCache(gateway, new MovableClock(), new SimpleMeterRegistry(), 30);

    assertThat(cache.quote(VIX)).isEmpty();
    // Same instant, so a cached empty would still be "fresh" — it must not be cached at all.
    assertThat(cache.quote(VIX)).map(QuoteGateway.Quote::lastPrice).contains(new BigDecimal("11.00"));
    verify(gateway, times(2)).quotes(any());
  }

  @Test
  void quoteWithNoLastPriceCountsAsAbsentAndEvictsWhatWasCached() {
    QuoteGateway gateway = mock(QuoteGateway.class);
    QuoteGateway.Quote priceless = new QuoteGateway.Quote(VIX, null, null);
    when(gateway.quotes(any())).thenReturn(Map.of(VIX, quote("12.00")), Map.of(VIX, priceless));
    MovableClock clock = new MovableClock();
    VixQuoteCache cache = new VixQuoteCache(gateway, clock, new SimpleMeterRegistry(), 30);

    cache.quote(VIX);
    clock.advance(Duration.ofSeconds(31));

    assertThat(cache.quote(VIX)).isEmpty();
    assertThat(cache.snapshot()).isEmpty();
  }

  @Test
  void zeroTtlDisablesCachingEntirely() {
    QuoteGateway gateway = mock(QuoteGateway.class);
    when(gateway.quotes(any())).thenReturn(Map.of(VIX, quote("12.34")));
    VixQuoteCache cache = new VixQuoteCache(gateway, new MovableClock(), new SimpleMeterRegistry(), 0);

    cache.quote(VIX);
    cache.quote(VIX);

    verify(gateway, times(2)).quotes(any());
    assertThat(cache.snapshot()).isEmpty();
  }

  @Test
  void failingGatewayPropagatesAndLeavesNothingCached() {
    QuoteGateway gateway = mock(QuoteGateway.class);
    when(gateway.quotes(any())).thenThrow(new IllegalStateException("kite down"));
    VixQuoteCache cache = new VixQuoteCache(gateway, new MovableClock(), new SimpleMeterRegistry(), 30);

    assertThatThrownBy(() -> cache.quote(VIX)).isInstanceOf(IllegalStateException.class);
    assertThat(cache.snapshot()).isEmpty();
  }

  @Test
  void staleIsNeverServedWhenRefreshFails() {
    QuoteGateway gateway = mock(QuoteGateway.class);
    when(gateway.quotes(any()))
        .thenReturn(Map.of(VIX, quote("12.34")))
        .thenThrow(new IllegalStateException("kite down"));
    MovableClock clock = new MovableClock();
    VixQuoteCache cache = new VixQuoteCache(gateway, clock, new SimpleMeterRegistry(), 30);

    cache.quote(VIX);
    clock.advance(Duration.ofSeconds(31));

    // The whole point: a broken feed must look exactly as broken as it did before this class
    // existed. Serving the 12.34 here would turn an outage into a plausible-looking number.
    assertThatThrownBy(() -> cache.quote(VIX)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void hitsAndMissesAreCounted() {
    QuoteGateway gateway = mock(QuoteGateway.class);
    when(gateway.quotes(any())).thenReturn(Map.of(VIX, quote("12.34")));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    VixQuoteCache cache = new VixQuoteCache(gateway, new MovableClock(), meters, 30);

    cache.quote(VIX);
    cache.quote(VIX);
    cache.quote(VIX);

    assertThat(meters.counter("ay_vix_quote_cache_miss_total").count()).isEqualTo(1.0);
    assertThat(meters.counter("ay_vix_quote_cache_hit_total").count()).isEqualTo(2.0);
  }
}
