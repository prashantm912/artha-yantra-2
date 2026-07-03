package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.LastSeenProvider;
import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.TickListener;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live {@code MarketFeed} (B-6 / Phase 13): socket lifecycle under the {@code kite-ticker}
 * breaker (5 consecutive failures → OPEN 60 s → 1 probe), reconnect supervision that replays the
 * full registry idempotently on every {@code onConnected}, and post-reconnect gap detection —
 * instruments silent &gt; 2 min during market hours get a 1m backfill with
 * {@code source='BACKFILL'}. SDK callbacks only ever ENQUEUE into the ingress queue (B-6).
 */
public class LiveTickerFeed implements MarketFeed, SubscriptionRegistry.TickerCommands {

  static final Duration GAP_THRESHOLD = Duration.ofMinutes(2);

  private static final Logger log = LoggerFactory.getLogger(LiveTickerFeed.class);

  private final java.util.function.Supplier<TickerHandle> handleFactory;
  private final SubscriptionRegistry registry;
  private final LastSeenProvider lastSeen;
  private final GapBackfiller backfiller;
  private final MarketCalendar calendar;
  private final CircuitBreaker breaker;
  private final Clock clock;
  private final Counter reconnects;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean connectedBefore = new AtomicBoolean();
  private volatile TickerHandle handle;
  private volatile TickListener listener;
  private volatile java.util.function.Consumer<Boolean> connectionStateSink;

  /**
   * Wires the supervisor; registers itself as the registry's ticker. The handle is created
   * LAZILY at start — the day's access token cannot exist before the morning ritual (B-2).
   */
  public LiveTickerFeed(
      java.util.function.Supplier<TickerHandle> handleFactory,
      SubscriptionRegistry registry,
      LastSeenProvider lastSeen,
      GapBackfiller backfiller,
      MarketCalendar calendar,
      CircuitBreaker breaker,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.handleFactory = handleFactory;
    this.registry = registry;
    this.lastSeen = lastSeen;
    this.backfiller = backfiller;
    this.calendar = calendar;
    this.breaker = breaker;
    this.clock = clock;
    this.reconnects = meterRegistry.counter("ay_kite_ws_reconnects_total");
    meterRegistry.gauge(
        "ay_kite_circuit_state",
        breaker,
        b ->
            switch (b.getState()) {
              case OPEN, FORCED_OPEN -> 1.0;
              case HALF_OPEN -> 2.0;
              default -> 0.0;
            });
    registry.attachTicker(this);
  }

  @Override
  public void start(TickListener tickListener) {
    this.listener = tickListener;
    if (!started.compareAndSet(false, true)) {
      return;
    }
    try {
      if (handle == null) {
        TickerHandle created = handleFactory.get();
        created.onConnected(this::onConnected);
        created.onDisconnected(
            () -> {
              log.warn("kite ticker disconnected");
              notifyConnectionState(false);
            });
        created.onTicks(this::onTicks);
        created.onError(message -> log.warn("kite ticker error: {}", message));
        handle = created;
      }
      breaker.executeRunnable(handle::connect);
    } catch (Exception failure) {
      started.set(false);
      log.warn(
          "kite ticker start failed (breaker {}): {}", breaker.getState(), failure.getMessage());
    }
  }

  @Override
  public void stop() {
    started.set(false);
    TickerHandle current = handle;
    if (current != null) {
      current.disconnect();
    }
    // Drop the handle so the next start() rebuilds it via the factory, re-reading the CURRENT
    // access token. Without this the day's first handle (often built at boot, before the morning
    // login) is frozen for the process lifetime and the SDK reconnect loop 403s forever on the
    // stale token — a restart was the only recovery.
    handle = null;
    connectedBefore.set(false);
  }

  @Override
  public boolean running() {
    TickerHandle current = handle;
    return current != null && current.connected();
  }

  @Override
  public void subscribe(List<Long> tokens, SubscriptionMode mode) {
    TickerHandle current = handle;
    if (current != null && current.connected()) {
      current.subscribe(tokens, mode);
    }
  }

  @Override
  public void unsubscribe(List<Long> tokens) {
    TickerHandle current = handle;
    if (current != null && current.connected()) {
      current.unsubscribe(tokens);
    }
  }

  @Override
  public void onConnectionState(java.util.function.Consumer<Boolean> sink) {
    this.connectionStateSink = sink;
  }

  private void notifyConnectionState(boolean connected) {
    java.util.function.Consumer<Boolean> sink = connectionStateSink;
    if (sink != null) {
      sink.accept(connected);
    }
  }

  /** Reconnect supervision: replay the registry idempotently, then scan for tick gaps. */
  void onConnected() {
    notifyConnectionState(true); // status truth BEFORE the replay — a replay failure ≠ not connected
    if (connectedBefore.getAndSet(true)) {
      reconnects.increment();
    }
    Map<SubscriptionMode, List<Long>> byMode = registry.tokensByMode();
    byMode.forEach(
        (mode, tokens) -> {
          if (!tokens.isEmpty()) {
            handle.subscribe(tokens, mode);
          }
        });
    log.info("kite ticker connected; replayed {} mode groups", byMode.size());
    scanForGaps();
  }

  /** B-6 gap rule: silent &gt; 2 min while the market is OPEN → schedule a 1m backfill. */
  void scanForGaps() {
    Instant now = clock.instant();
    if (!calendar.isOpen(now)) {
      return;
    }
    for (InstrumentKey key : registry.subscribedKeys()) {
      Optional<Instant> seen = lastSeen.lastSeen(key);
      if (seen.isPresent() && Duration.between(seen.get(), now).compareTo(GAP_THRESHOLD) > 0) {
        log.warn("tick gap on {} since {} — scheduling 1m backfill", key.canonical(), seen.get());
        backfiller.backfill(key, seen.get(), now);
      }
    }
  }

  private void onTicks(List<RawTick> ticks) {
    TickListener sink = listener;
    if (sink == null) {
      return;
    }
    for (RawTick tick : ticks) {
      sink.onTick(tick); // enqueue only — the bounded ingress queue is the sink (B-6)
    }
  }
}
