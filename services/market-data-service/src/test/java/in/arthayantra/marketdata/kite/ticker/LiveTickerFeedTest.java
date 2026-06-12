package in.arthayantra.marketdata.kite.ticker;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.LastSeenProvider;
import in.arthayantra.marketdata.kite.RawTick;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Phase-13 supervision: replay-on-connect, reconnect counting, breaker, gap backfill. */
class LiveTickerFeedTest {

  /** A scriptable ticker socket. */
  static class FakeTickerHandle implements TickerHandle {
    final List<String> subscribeCalls = new ArrayList<>();
    boolean failConnect;
    boolean connected;
    Runnable onConnected = () -> {};
    Consumer<List<RawTick>> onTicks = t -> {};

    @Override
    public void connect() {
      if (failConnect) {
        throw new IllegalStateException("socket refused");
      }
      connected = true;
      onConnected.run();
    }

    @Override
    public void disconnect() {
      connected = false;
    }

    @Override
    public boolean connected() {
      return connected;
    }

    @Override
    public void subscribe(List<Long> tokens, SubscriptionMode mode) {
      subscribeCalls.add(tokens + ":" + mode.wireName());
    }

    @Override
    public void unsubscribe(List<Long> tokens) {
      subscribeCalls.add("unsub" + tokens);
    }

    @Override
    public void onConnected(Runnable callback) {
      this.onConnected = callback;
    }

    @Override
    public void onDisconnected(Runnable callback) {}

    @Override
    public void onTicks(Consumer<List<RawTick>> callback) {
      this.onTicks = callback;
    }

    @Override
    public void onError(Consumer<String> callback) {}
  }

  private static final InstrumentTokenResolver RESOLVER =
      key ->
          Optional.of(
              new InstrumentTokenResolver.TokenInfo(
                  key.tradingsymbol().hashCode() & 0xffff, "EQ", "NSE"));

  // Tue 2026-02-03 11:00 IST — inside the session
  private static final Instant MARKET_OPEN_NOW = OffsetDateTime.parse("2026-02-03T11:00:00+05:30").toInstant();

  record Fixture(
      LiveTickerFeed feed,
      FakeTickerHandle handle,
      SubscriptionRegistry registry,
      Map<InstrumentKey, Instant> lastSeen,
      List<String> backfills,
      MeterRegistry meters,
      CircuitBreaker breaker) {}

  private static Fixture fixture(Instant now) {
    FakeTickerHandle handle = new FakeTickerHandle();
    MeterRegistry meters = new SimpleMeterRegistry();
    SubscriptionRegistry registry = new SubscriptionRegistry(RESOLVER, 3_000, meters);
    Map<InstrumentKey, Instant> lastSeen = new HashMap<>();
    List<String> backfills = new ArrayList<>();
    LastSeenProvider seenProvider = key -> Optional.ofNullable(lastSeen.get(key));
    GapBackfiller backfiller = (key, from, to) -> backfills.add(key.canonical());
    CircuitBreaker breaker =
        CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                    .slidingWindowSize(5)
                    .minimumNumberOfCalls(5)
                    .failureRateThreshold(100)
                    .build())
            .circuitBreaker("kite-ticker");
    LiveTickerFeed feed =
        new LiveTickerFeed(
            () -> handle,
            registry,
            seenProvider,
            backfiller,
            MarketCalendar.nse(),
            breaker,
            Clock.fixed(now, ZoneOffset.UTC),
            meters);
    return new Fixture(feed, handle, registry, lastSeen, backfills, meters, breaker);
  }

  @Test
  void onConnectedReplaysTheExactRegistrySetIdempotently() {
    Fixture f = fixture(MARKET_OPEN_NOW);
    f.registry().subscribe("ui", new InstrumentKey("NSE", "AAA"), SubscriptionMode.FULL, SubscriptionPriority.UI);
    f.registry().subscribe("ui", new InstrumentKey("NSE", "BBB"), SubscriptionMode.LTP, SubscriptionPriority.UI);

    f.feed().start(tick -> {});

    long fullReplays = f.handle().subscribeCalls.stream().filter(c -> c.endsWith(":full")).count();
    long ltpReplays = f.handle().subscribeCalls.stream().filter(c -> c.endsWith(":ltp")).count();
    assertThat(fullReplays).isEqualTo(1);
    assertThat(ltpReplays).isEqualTo(1);

    // a reconnect replays the same set again — idempotent by construction
    int before = f.handle().subscribeCalls.size();
    f.handle().onConnected.run();
    assertThat(f.handle().subscribeCalls).hasSize(before + 2);
  }

  @Test
  void reconnectsAreCountedAfterTheFirstConnect() {
    Fixture f = fixture(MARKET_OPEN_NOW);
    f.feed().start(tick -> {});
    assertThat(f.meters().counter("ay_kite_ws_reconnects_total").count()).isZero();

    f.handle().onConnected.run(); // SDK auto-reconnect fires the callback again
    f.handle().onConnected.run();

    assertThat(f.meters().counter("ay_kite_ws_reconnects_total").count()).isEqualTo(2.0);
  }

  @Test
  void fiveConsecutiveConnectFailuresOpenTheTickerBreaker() {
    Fixture f = fixture(MARKET_OPEN_NOW);
    f.handle().failConnect = true;

    for (int i = 0; i < 6; i++) {
      f.feed().start(tick -> {}); // failure resets the started latch, so each call retries
    }

    assertThat(f.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(
            f.meters().get("ay_kite_circuit_state").gauge().value())
        .as("breaker state observable via metric")
        .isEqualTo(1.0);
    assertThat(f.handle().connected).isFalse();
  }

  @Test
  void instrumentsSilentOverTwoMinutesGetBackfilledDuringMarketHours() {
    Fixture f = fixture(MARKET_OPEN_NOW);
    InstrumentKey stale = new InstrumentKey("NSE", "STALE");
    InstrumentKey fresh = new InstrumentKey("NSE", "FRESH");
    f.registry().subscribe("ui", stale, SubscriptionMode.QUOTE, SubscriptionPriority.UI);
    f.registry().subscribe("ui", fresh, SubscriptionMode.QUOTE, SubscriptionPriority.UI);
    f.lastSeen().put(stale, MARKET_OPEN_NOW.minusSeconds(180));
    f.lastSeen().put(fresh, MARKET_OPEN_NOW.minusSeconds(30));

    f.feed().start(tick -> {});

    assertThat(f.backfills()).containsExactly("NSE:STALE");
  }

  @Test
  void noBackfillOutsideMarketHours() {
    // Sunday 2026-02-01 11:00 IST
    Instant sunday = OffsetDateTime.parse("2026-02-01T11:00:00+05:30").toInstant();
    Fixture f = fixture(sunday);
    InstrumentKey stale = new InstrumentKey("NSE", "STALE");
    f.registry().subscribe("ui", stale, SubscriptionMode.QUOTE, SubscriptionPriority.UI);
    f.lastSeen().put(stale, sunday.minusSeconds(3_600));

    f.feed().start(tick -> {});

    assertThat(f.backfills()).isEmpty();
  }

  @Test
  void ticksFlowStraightToTheListener() {
    Fixture f = fixture(MARKET_OPEN_NOW);
    List<RawTick> received = new ArrayList<>();
    f.feed().start(received::add);

    f.handle()
        .onTicks
        .accept(
            List.of(
                new RawTick(1, java.math.BigDecimal.ONE, 10, null, MARKET_OPEN_NOW),
                new RawTick(2, java.math.BigDecimal.TWO, 20, null, MARKET_OPEN_NOW)));

    assertThat(received).hasSize(2);
  }
}
