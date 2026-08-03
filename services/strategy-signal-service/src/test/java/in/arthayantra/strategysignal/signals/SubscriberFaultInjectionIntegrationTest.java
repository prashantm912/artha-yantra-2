package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.registry.MarketDataInstrumentClient;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.signals.SubscriberHealthCanary.SubscriberStallAlert;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * THE point of the whole change: drive {@link SubscriberHealthCanary}'s receive-stall branch — the
 * one live path no external action can reach — end to end against a real engine, a real Redis and a
 * real Postgres.
 *
 * <p>Background: the 2026-08-03 live drill killed the pub/sub connection server-side and Lettuce's
 * {@code ConnectionWatchdog} healed it in ~22 ms, so the transport-drop class can never reach a 180 s
 * {@code bar-gap-ms} detector. This test injects the OTHER shape — the 2026-07-07 silent subscription
 * loss — and asserts the full chain: the injector produces exactly the observable pair the canary
 * keys on ({@code lastBarReceivedAtMs} stale WHILE {@code ticks:last-at} fresh), and the canary then
 * fires, re-subscribes, publishes {@link SubscriberStallAlert} and writes its
 * {@code subscriber_health_events} rows.
 *
 * <p>The context runs on a SHIFTABLE clock: it ticks in real time (so a received bar always stamps a
 * NEW value — a frozen clock would make the "heartbeat did not advance" assertion pass for free) but
 * is anchored at 10:00 IST on 2026-07-07, an open NSE session, so the canary's in-session gate is
 * satisfied, and can be jumped forward to age the receive gap past the threshold without sleeping.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.signals.fault-injection.enabled=true"})
class SubscriberFaultInjectionIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String SYMBOL = "FAULTINJ";
  private static final String SLUG = "engine-it-faultinj";
  /** 2026-07-07T04:30:00Z == 10:00 IST, an open NSE session (the 2026-07-07 incident's own date). */
  private static final Instant ANCHOR = Instant.parse("2026-07-07T04:30:00Z");

  private static final ShiftableClock CLOCK = new ShiftableClock(ANCHOR);

  private static final String STRATEGY_YAML =
      """
      schema: strategy-schema/v1
      id: engine-it-faultinj
      name: "Engine IT Fault Injection"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: FAULTINJ }
      timeframes: { primary: 1m }
      indicators:
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
            normalize: { type: rsi_momentum } }
      entry_rules:
        direction: long
        gate:
          all:
            - "close > 1"
        scoring: { threshold: 0.2 }
      exit_rules:
        - { type: stop_loss, params: { basis: premium_pct, value: 20 } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  /** Records the in-process alert the notifier module would page on (signals must not import it). */
  static class StallAlertRecorder {
    final List<SubscriberStallAlert> alerts = new CopyOnWriteArrayList<>();

    @EventListener
    void onStall(SubscriberStallAlert alert) {
      alerts.add(alert);
    }
  }

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    Clock shiftableClock() {
      return CLOCK;
    }

    @Bean
    StallAlertRecorder stallAlertRecorder() {
      return new StallAlertRecorder();
    }

    @Bean
    @Primary
    MarketDataInstrumentClient stubInstruments() {
      return (exchange, tradingsymbol) -> true;
    }

    /** Other classes' strategies share this DB; leaving them unresolved keeps this context quick. */
    @Bean
    @Primary
    FuturesUniverseResolver stubFuturesResolver() {
      FuturesUniverseResolver resolver = mock(FuturesUniverseResolver.class);
      when(resolver.resolve(anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(Optional.empty());
      return resolver;
    }

    /** A flat warm-up ramp ending at whatever window the engine asks for — enough to load. */
    @Bean
    @Primary
    MarketDataCandlesClient stubCandles(
        org.springframework.web.client.RestClient.Builder builder, ObjectMapper objectMapper) {
      return new MarketDataCandlesClient(builder, objectMapper, "http://127.0.0.1:1", 10_000) {
        @Override
        public List<EngineCandle> fetch(
            String exchange,
            String tradingsymbol,
            String interval,
            OffsetDateTime from,
            OffsetDateTime to) {
          if (!"1m".equals(interval) || !SYMBOL.equals(tradingsymbol)) {
            return List.of();
          }
          OffsetDateTime end = to.truncatedTo(ChronoUnit.MINUTES);
          List<EngineCandle> warm = new ArrayList<>();
          for (int i = 60; i > 0; i--) {
            BigDecimal close = new BigDecimal("100.00").add(BigDecimal.valueOf(60 - i));
            warm.add(
                new EngineCandle(
                    end.minusMinutes(i),
                    close,
                    close.add(new BigDecimal("0.10")),
                    close.subtract(new BigDecimal("0.10")),
                    close,
                    500));
          }
          return warm;
        }
      };
    }
  }

  @Autowired private RegistryService registryService;
  @Autowired private SignalEngine engine;
  @Autowired private SubscriberHealthCanary canary;
  @Autowired private SignalFaultInjector injector;
  @Autowired private StallAlertRecorder alertRecorder;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void injectedSubscriptionStall_drivesTheWatchdogsFullFirePath() throws Exception {
    // ── Arrange: a published intraday strategy so the engine subscribes candles.1m.NSE.FAULTINJ ──
    UUID strategyId =
        registryService.create("Engine IT Fault Injection", null, null, STRATEGY_YAML).id();
    registryService.publish(strategyId, null, null);
    await().atMost(Duration.ofSeconds(30)).until(() -> engine.loadedSlugs().contains(SLUG));

    // Baseline: bars really are being delivered. Without this the "no longer delivered" assertion
    // below would pass for a context that never delivered anything in the first place.
    long beforeDelivery = engine.lastBarReceivedAtMs();
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              publishBar();
              return engine.lastBarReceivedAtMs() > beforeDelivery;
            });
    long receiveStallRowsBefore = healthEventRows("receive-stall");
    long resubscribeRowsBefore = healthEventRows("resubscribe");
    int alertsBefore = alertRecorder.alerts.size();

    // POSITIVE CONTROL for the negative assertion below: over this exact publish window, delivery
    // demonstrably advances the heartbeat. Without a matched control, "it did not advance" could be
    // explained by a publish loop that never worked at all.
    long beforeControlWindow = engine.lastBarReceivedAtMs();
    publishFor(20);
    assertThat(engine.lastBarReceivedAtMs())
        .as("control: with the subscription healthy this window DOES advance the heartbeat")
        .isGreaterThan(beforeControlWindow);

    // ── Act 1: inject. MAX auto-restore so the WATCHDOG, not the timer, is what recovers here. ──
    SignalFaultInjector.SubscriptionStallInjection injection =
        injector.injectSubscriptionStall(SignalFaultInjector.MAX_AUTO_RESTORE_MS);
    assertThat(injection.injected()).isTrue();
    // Redis pub/sub is fire-and-forget: a message already in flight when the container stopped can
    // still land. Let those drain before baselining — the claim is that no bar is received once the
    // stall has taken effect, not that the stop is instantaneous.
    Thread.sleep(500);
    long heartbeatBeforeInjection = engine.lastBarReceivedAtMs();

    // ── Assert observable #1: bars are no longer RECEIVED (the clock ticks, so a delivered bar
    // would necessarily stamp a NEW value — this assertion cannot pass by the clock standing still).
    publishFor(20);
    assertThat(engine.lastBarReceivedAtMs())
        .as("the injected stall must stop candle RECEIPT dead")
        .isEqualTo(heartbeatBeforeInjection);

    // ── Assert observable #2: the shared Redis connection is untouched — this is a SUBSCRIPTION
    // loss, not a transport drop (the shape Lettuce's ConnectionWatchdog would have healed).
    redis.opsForValue().set("fault-injection-probe", "alive");
    assertThat(redis.opsForValue().get("fault-injection-probe")).isEqualTo("alive");

    // ── Act 2: age the receive gap past bar-gap-ms while the producer heartbeat stays FRESH ──
    CLOCK.advance(Duration.ofSeconds(200)); // > the 180s bar-gap-ms default
    redis.opsForValue().set("ticks:last-at", Long.toString(CLOCK.millis() - 10_000));
    canary.sweep();

    // ── Assert the fire path: paged, telemetry rows written, and a re-subscribe was requested ──
    assertThat(alertRecorder.alerts)
        .as("the watchdog must publish the in-process stall alert the notifier pages on")
        .hasSizeGreaterThan(alertsBefore);
    assertThat(alertRecorder.alerts.get(alertRecorder.alerts.size() - 1).title())
        .contains("STARVED");
    assertThat(healthEventRows("receive-stall"))
        .as("durable forensics: one receive-stall row")
        .isGreaterThan(receiveStallRowsBefore);
    assertThat(healthEventRows("resubscribe"))
        .as("durable forensics: one resubscribe row")
        .isGreaterThan(resubscribeRowsBefore);

    // ── Assert recovery: the watchdog's re-subscribe actually restored delivery ──
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              publishBar();
              return engine.lastBarReceivedAtMs() > heartbeatBeforeInjection;
            });
  }

  /** Publishes one bar per 100 ms — the fixed window both the control and the stall are measured on. */
  private void publishFor(int ticks) throws Exception {
    for (int i = 0; i < ticks; i++) {
      publishBar();
      Thread.sleep(100);
    }
  }

  private long healthEventRows(String kind) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM subscriber_health_events WHERE kind = ?", Long.class, kind);
    return count == null ? 0L : count;
  }

  private void publishBar() throws Exception {
    OffsetDateTime bucket =
        OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
    Map<String, Object> bar = new LinkedHashMap<>();
    bar.put("exchange", "NSE");
    bar.put("tradingsymbol", SYMBOL);
    bar.put("interval", "1m");
    bar.put("bucket", bucket.toString());
    bar.put("open", "100.00");
    bar.put("high", "100.20");
    bar.put("low", "99.90");
    bar.put("close", "100.10");
    bar.put("volume", 750);
    bar.put("oi", null);
    bar.put("source", "MOCK");
    redis.convertAndSend("candles.1m.NSE." + SYMBOL, objectMapper.writeValueAsString(bar));
  }

  /**
   * Real-time-ticking clock with an adjustable offset. Ticking matters: a {@code Clock.fixed} would
   * stamp every received bar with the SAME millis, so "the heartbeat did not advance" would hold
   * even if every bar were still being delivered — a broken proof that looks like a passing one.
   */
  static final class ShiftableClock extends Clock {
    private volatile Duration shift;

    ShiftableClock(Instant anchor) {
      this.shift = Duration.between(Instant.now(), anchor);
    }

    void advance(Duration by) {
      this.shift = this.shift.plus(by);
    }

    @Override
    public Instant instant() {
      return Instant.now().plus(shift);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
