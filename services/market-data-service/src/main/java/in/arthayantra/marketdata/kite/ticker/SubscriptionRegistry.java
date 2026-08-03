package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.PinnedSubscriptionRegistrar;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The refcounted Kite WS subscription registry (B-6 / Phase 13): subscribers register
 * {@code (exchange, tradingsymbol, mode)}; the registry resolves stable keys to tokens via the
 * synced master and holds the HIGHEST requested mode per instrument. Hard cap 3,000 instruments
 * per connection — on pressure, evict strictly-lower-priority instruments (pinned indices &gt;
 * strategy &gt; UI &gt; speculative) with a logged warning; beyond that → 400
 * {@code VALIDATION_FAILED}. Profile-agnostic: in mock mode it simply has no ticker listening.
 */
@Component
public class SubscriptionRegistry implements PinnedSubscriptionRegistrar {

  /** Commands a live ticker executes when the desired set changes; absent under mock. */
  public interface TickerCommands {
    /** Subscribe + set mode for tokens. */
    void subscribe(List<Long> tokens, SubscriptionMode mode);

    /** Drop tokens. */
    void unsubscribe(List<Long> tokens);
  }

  /** One subscriber's hold on an instrument. */
  record Hold(SubscriptionMode mode, SubscriptionPriority priority) {}

  /** Registry state for one instrument. */
  record Instrument(
      long token, boolean index, Map<String, Hold> holds, SubscriptionMode effectiveMode) {}

  /** A view row for the REST surface. */
  public record SubscriptionView(
      String exchange,
      String tradingsymbol,
      long instrumentToken,
      SubscriptionMode mode,
      SubscriptionPriority priority,
      int subscribers) {}

  private static final Logger log = LoggerFactory.getLogger(SubscriptionRegistry.class);

  private final InstrumentTokenResolver tokenResolver;
  private final int cap;
  private final Counter evictions;
  private final SubscriptionStore store;
  private final Map<InstrumentKey, Instrument> instruments = new LinkedHashMap<>();
  private volatile TickerCommands commands;

  /** Legacy ctor (unit tests): no durable store — holds live only in memory. */
  public SubscriptionRegistry(
      InstrumentTokenResolver tokenResolver, int cap, MeterRegistry meterRegistry) {
    this(tokenResolver, cap, meterRegistry, SubscriptionStore.NOOP);
  }

  /** Wires the resolver, cap, and the durable {@link SubscriptionStore} (B-6 restart durability). */
  @org.springframework.beans.factory.annotation.Autowired
  public SubscriptionRegistry(
      InstrumentTokenResolver tokenResolver,
      @Value("${artha.subscriptions.cap:3000}") int cap,
      MeterRegistry meterRegistry,
      SubscriptionStore store) {
    this.tokenResolver = tokenResolver;
    this.cap = cap;
    this.evictions = meterRegistry.counter("ay_subscription_evictions_total");
    this.store = store;
    meterRegistry.gauge("ay_subscriptions_active", this, r -> r.instruments.size());
  }

  /** The live ticker registers itself here; mock mode never calls this. */
  public void attachTicker(TickerCommands tickerCommands) {
    this.commands = tickerCommands;
  }

  @Override
  public synchronized java.util.Set<InstrumentKey> heldBy(String subscriber) {
    java.util.Set<InstrumentKey> held = new java.util.LinkedHashSet<>();
    instruments.forEach(
        (key, instrument) -> {
          if (instrument.holds().containsKey(subscriber)) {
            held.add(key);
          }
        });
    return held;
  }

  /** Adds/raises a subscriber's hold; returns the instrument's effective mode. */
  @Override
  public void subscribe(String subscriber, InstrumentKey key) {
    // SPECULATIVE, deliberately — see PinnedSubscriptionRegistrar. This port serves RESEARCH capture
    // (option-premium bars), and subscribe() EVICTS lower-priority holds at the cap. At PINNED_INDEX
    // a few dozen option strikes could displace a live STRATEGY subscription, trading a tradeable
    // signal for a research bar. FuturesPinner keeps PINNED_INDEX via the explicit 4-arg call: the
    // front/next/far futures ARE index infrastructure, not capture.
    subscribe(subscriber, key, SubscriptionMode.QUOTE, SubscriptionPriority.SPECULATIVE);
  }

  /** Adds/raises a subscriber's hold; returns the instrument's effective mode. */
  public synchronized SubscriptionMode subscribe(
      String subscriber, InstrumentKey key, SubscriptionMode mode, SubscriptionPriority priority) {
    Instrument existing = instruments.get(key);
    if (existing == null) {
      InstrumentTokenResolver.TokenInfo info =
          tokenResolver
              .resolve(key)
              .orElseThrow(
                  () ->
                      new NotFoundException(
                          ErrorCodes.NOT_FOUND_INSTRUMENT, "unknown instrument " + key.canonical()));
      refuseCashEquity(key, info.isIndex());
      if (instruments.size() >= cap) {
        evictLowerPriorityThan(priority, key);
      }
      Map<String, Hold> holds = new LinkedHashMap<>();
      holds.put(subscriber, new Hold(mode, priority));
      instruments.put(key, new Instrument(info.instrumentToken(), info.isIndex(), holds, mode));
      notifySubscribe(List.of(info.instrumentToken()), mode);
      persist(subscriber, key, mode, priority);
      return mode;
    }
    refuseCashEquity(key, existing.index());
    existing.holds().put(subscriber, new Hold(mode, priority));
    persist(subscriber, key, mode, priority);
    SubscriptionMode newEffective = effectiveModeOf(existing.holds());
    if (newEffective != existing.effectiveMode()) {
      instruments.put(
          key,
          new Instrument(existing.token(), existing.index(), existing.holds(), newEffective));
      notifySubscribe(List.of(existing.token()), newEffective);
    }
    return newEffective;
  }

  /** The cash exchanges - the only ones carrying ordinary tradable EQUITIES alongside indices. */
  private static final java.util.Set<String> CASH_EXCHANGES = java.util.Set.of("NSE", "BSE");

  /**
   * RATCHET (PR #1251): refuses to put a CASH EQUITY on the WS ticker.
   *
   * <p>It sits here because this method is the one choke point every subscription must pass.
   * Ratcheting the configured pin list instead ({@code artha.subscriptions.pinned-indices}) looked
   * equivalent and was not: {@code SubscriptionsController.subscribe} takes an arbitrary
   * exchange/symbol from the caller straight into this method, and {@code SubscriptionReplayer.replay}
   * restores persisted holds through it on every restart - so an equity could reach {@code ticks:last}
   * with a config-level ratchet fully green. Any future caller is covered for the same reason.
   *
   * <p><b>Why a subscription bean carries a money-path rule.</b> {@code PaperBracketEvaluator} (in
   * strategy-signal) prices every open paper position off the {@code ticks:last} LTP and compares it
   * to the STORED {@code paper_positions.stop_loss} - a level the daily swing batch writes once, at
   * entry, on the corporate-action plane current then, and which nothing ever re-scales. A split would
   * halve the tick while the stored stop stayed whole and stop a swing holding out intraday, on the
   * ex-date morning, at a price that never happened. That exposure is real and deliberately UNFIXED:
   * PR #1251 attempted fixes on both surfaces, cross-vendor review found four Criticals in them, and
   * the owner reverted to marking the exposure rather than patching it. It is unreachable today for
   * exactly one reason - no cash equity is on the live tick feed, so {@code lastTick} returns empty
   * and the comparison never runs (docs/signal-analysis/2026-08-02-manas-exit-stop-doctrine.md).
   * Every open swing holding already carries a non-null {@code stop_loss}, so a single tick is the
   * whole remaining distance. This makes that distance impossible to close by accident.
   *
   * <p>The rule is the instrument master's own {@code INDICES} segment, not a name allowlist: an index
   * is not tradable and can never be a paper holding, so a NEW index needs no change here, while every
   * tradable cash instrument is refused whatever it is called. Checked after resolution, so an unknown
   * instrument still 404s exactly as before, and on the already-registered path too, so a hold
   * persisted before this ratchet existed cannot be re-raised.
   */
  private static void refuseCashEquity(InstrumentKey key, boolean isIndex) {
    String exchange = key.exchange() == null ? "" : key.exchange().trim().toUpperCase(java.util.Locale.ROOT);
    if (isIndex || !CASH_EXCHANGES.contains(exchange)) {
      return;
    }
    throw new ApiException(
        400,
        ErrorCodes.VALIDATION_FAILED,
        "refusing to subscribe cash-exchange instrument "
            + key.canonical()
            + " to the live ticker: the instrument master does not report it in the INDICES segment,"
            + " so it is a tradable EQUITY. Subscribing it arms a live money-path exposure -"
            + " PaperBracketEvaluator prices open paper positions off ticks:last against the STORED"
            + " paper_positions.stop_loss, which is written once at entry and never re-scaled when a"
            + " corporate action re-planes the market, so a split would stop a swing holding out"
            + " intraday at a price that never happened. That gap is known and UNFIXED (PR #1251;"
            + " see docs/signal-analysis/2026-08-02-manas-exit-stop-doctrine.md). Close it before"
            + " putting equities on the feed.");
  }

  /** Releases a subscriber's hold; drops or downgrades the instrument as refcounts dictate. */
  @Override
  public synchronized void unsubscribe(String subscriber, InstrumentKey key) {
    Instrument existing = instruments.get(key);
    if (existing == null || existing.holds().remove(subscriber) == null) {
      return;
    }
    store.remove(subscriber, key);
    if (existing.holds().isEmpty()) {
      instruments.remove(key);
      notifyUnsubscribe(List.of(existing.token()));
      return;
    }
    SubscriptionMode newEffective = effectiveModeOf(existing.holds());
    if (newEffective != existing.effectiveMode()) {
      instruments.put(
          key,
          new Instrument(existing.token(), existing.index(), existing.holds(), newEffective));
      notifySubscribe(List.of(existing.token()), newEffective);
    }
  }

  /** The REST view. */
  public synchronized List<SubscriptionView> view() {
    List<SubscriptionView> rows = new ArrayList<>(instruments.size());
    instruments.forEach(
        (key, instrument) ->
            rows.add(
                new SubscriptionView(
                    key.exchange(),
                    key.tradingsymbol(),
                    instrument.token(),
                    instrument.effectiveMode(),
                    bestPriorityOf(instrument.holds()),
                    instrument.holds().size())));
    return rows;
  }

  /** Tokens grouped by effective mode — the reconnect replay set (B-6). */
  public synchronized Map<SubscriptionMode, List<Long>> tokensByMode() {
    Map<SubscriptionMode, List<Long>> byMode = new EnumMap<>(SubscriptionMode.class);
    instruments.forEach(
        (key, instrument) ->
            byMode.computeIfAbsent(instrument.effectiveMode(), m -> new ArrayList<>())
                .add(instrument.token()));
    return byMode;
  }

  /** Subscribed keys (gap scanning after reconnect). */
  public synchronized List<InstrumentKey> subscribedKeys() {
    return new ArrayList<>(instruments.keySet());
  }

  /** Expected packet layout for a token — the B-9 frame-guard lookup. */
  public synchronized Optional<ExpectedLayout> layoutFor(long token) {
    for (Instrument instrument : instruments.values()) {
      if (instrument.token() == token) {
        return Optional.of(new ExpectedLayout(instrument.effectiveMode(), instrument.index()));
      }
    }
    return Optional.empty();
  }

  /** What the guard expects for a token. */
  public record ExpectedLayout(SubscriptionMode mode, boolean index) {

    /** The only legal packet size for this subscription. */
    public int expectedBytes() {
      return mode.expectedBytes(index);
    }
  }

  private void evictLowerPriorityThan(SubscriptionPriority incoming, InstrumentKey forKey) {
    InstrumentKey victim = null;
    SubscriptionPriority victimPriority = null;
    for (Map.Entry<InstrumentKey, Instrument> entry : instruments.entrySet()) {
      SubscriptionPriority p = bestPriorityOf(entry.getValue().holds());
      if (p.compareTo(incoming) > 0 && (victimPriority == null || p.compareTo(victimPriority) > 0)) {
        victim = entry.getKey();
        victimPriority = p;
      }
    }
    if (victim == null) {
      throw new ApiException(
          400,
          ErrorCodes.VALIDATION_FAILED,
          "subscription cap " + cap + " reached and nothing lower-priority to evict");
    }
    Instrument evicted = instruments.remove(victim);
    evictions.increment();
    for (String subscriber : evicted.holds().keySet()) {
      store.remove(subscriber, victim);
    }
    log.warn(
        "subscription cap {}: evicted {} (priority {}) for {} (priority {})",
        cap, victim.canonical(), victimPriority, forKey.canonical(), incoming);
    notifyUnsubscribe(List.of(evicted.token()));
  }

  private static SubscriptionMode effectiveModeOf(Map<String, Hold> holds) {
    SubscriptionMode mode = SubscriptionMode.LTP;
    boolean first = true;
    for (Hold hold : holds.values()) {
      mode = first ? hold.mode() : SubscriptionMode.max(mode, hold.mode());
      first = false;
    }
    return mode;
  }

  private static SubscriptionPriority bestPriorityOf(Map<String, Hold> holds) {
    SubscriptionPriority best = SubscriptionPriority.SPECULATIVE;
    for (Hold hold : holds.values()) {
      if (hold.priority().compareTo(best) < 0) {
        best = hold.priority();
      }
    }
    return best;
  }

  /** Persists a non-pinned hold so it survives a restart; pinned indices self-restore from config. */
  private void persist(
      String subscriber, InstrumentKey key, SubscriptionMode mode, SubscriptionPriority priority) {
    if (priority != SubscriptionPriority.PINNED_INDEX) {
      store.put(subscriber, key, mode, priority);
    }
  }

  private void notifySubscribe(List<Long> tokens, SubscriptionMode mode) {
    TickerCommands ticker = commands;
    if (ticker != null) {
      ticker.subscribe(tokens, mode);
    }
  }

  private void notifyUnsubscribe(List<Long> tokens) {
    TickerCommands ticker = commands;
    if (ticker != null) {
      ticker.unsubscribe(tokens);
    }
  }
}
