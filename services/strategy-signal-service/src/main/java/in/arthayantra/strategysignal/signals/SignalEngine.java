package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.common.web.time.Ist;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.BarValues;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.eval.ScoreBreakdownJson;
import in.arthayantra.strategyengine.golden.TickwiseGoldenRunner;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer;
import in.arthayantra.strategysignal.scalper.OpenHighLow;
import in.arthayantra.strategysignal.scalper.ScalperConfig;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.scalper.ScalperGateContext;
import in.arthayantra.strategysignal.scalper.ScalperGates;
import in.arthayantra.strategysignal.scalper.ScalperManualChecks;
import in.arthayantra.strategysignal.scalper.ScalperRisk;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flow 5 Part B — the live signal engine (Phase 23). Subscribes {@code candles.1m.*} for
 * PUBLISHED strategies' universes only (never a firehose); an order-preserving bar queue + a
 * single evaluation executor decouple evaluation from the Redis receive thread without dropping a
 * bar (candles.1m.* are never conflated); bar-close evaluation through the shared engine JAR; engine pinning
 * {@code (strategy_id, version, checksum)} on every emitted signal; hot-swap at the NEXT bar
 * boundary on {@code strategy.changed}; session-window gating; the A7/A9 additions — pre-close
 * BTST clock, context-instrument subscriptions (series inputs, never signal emitters),
 * futures_of_underlying front-month resolution with roll re-subscribe, and 1m exit-level
 * evaluation for {@code exit_intrabar} strategies.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    value = "artha.signals.engine-enabled", havingValue = "true", matchIfMissing = true)
public class SignalEngine {

  private static final Logger log = LoggerFactory.getLogger(SignalEngine.class);

  /** One loaded (strategy, version) with its resolved universe; {@code scalper} non-null = Track-2. */
  record Loaded(
      UUID strategyId,
      UUID versionId,
      String slug,
      String name,
      String version,
      String checksum,
      StrategyDefinition definition,
      List<StrategyDefinition.InstrumentRef> universe,
      Set<SeriesKey> seriesKeys,
      ScalperConfig scalper,
      String book) {}

  private final StrategyRepository registry;
  private final SignalRepository signals;
  private final SignalPublisher publisher;
  private final ApplicationEventPublisher events;
  private final LiveSeriesStore seriesStore;
  private final FuturesUniverseResolver futuresResolver;
  private final RedisConnectionFactory connectionFactory;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final int signalTtlMinutes;
  // A12 SPI (paper adapter): ENTRY risk gate + engine-stamped suggested qty. Absent ⇒ permissive.
  private final java.util.Optional<EmissionGuard> emissionGuard;
  // §12.3 Track-2 confluence seam: OI/macro gate + option pick for scalper strategies. Absent ⇒
  // scalper strategies cannot emit (fail-closed — a scalper without its confluence must not fire).
  private final java.util.Optional<ScalperConfluenceGate> scalperGate;
  // Live-only diagnostics: every scalper chart-entry the confluence gate blocked (why + margin).
  private final SignalRejectionRepository rejections;
  // Shadow book: opens an eligible rejection as a virtual 1-lot position (signal-analysis §7.1/7.2).
  private final ShadowBookService shadowBook;

  // candles.1m.* are NEVER conflated (A.7.2 bus contract — every closed bar matters). A FIFO
  // queue preserves each distinct bar in arrival order; the single-threaded evalExecutor drains it
  // in order. (A latest-value-wins map would drop a bar under a burst, leaving a permanent series
  // gap and potentially skipping a stop-loss EXIT.)
  private final java.util.Queue<Map.Entry<String, EngineCandle>> pending =
      new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final AtomicBoolean drainScheduled = new AtomicBoolean();
  private final AtomicBoolean reloadRequested = new AtomicBoolean(true);
  // Warm ta4j banks per (version|instrument) — cleared on reload/hot-swap (P1-12, D17 live).
  private final Map<String, IndicatorBank> bankCache = new ConcurrentHashMap<>();
  private final Map<String, LocalDate> preCloseDone = new ConcurrentHashMap<>();
  private final ExecutorService evalExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "signal-eval");
            t.setDaemon(true);
            return t;
          });
  // A watchdog-forced re-subscribe runs OFF the eval thread (audit A13, RC-1). Routing recovery
  // through evalExecutor (the #634 design) meant a receive-drop's recovery queued BEHIND a blocked
  // eval task — so a stalled eval loop could never re-subscribe itself out of a starvation. This
  // dedicated single-thread daemon executor decouples the two; the lock analysis in forceResubscribe
  // shows both #634 invariants (sweep thread never blocks on Redis I/O; no monitor deadlock) survive.
  private final ExecutorService recoveryExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "subscriber-recovery");
            t.setDaemon(true);
            return t;
          });

  private volatile List<Loaded> loaded = List.of();

  // Subscriber-liveness heartbeat: wall-clock (clock) millis of the last candle message RECEIVED on
  // any candles.1m.* channel. SubscriberHealthCanary compares this against market-data's ticks:last-at
  // to catch a SILENT Redis subscription drop (feed alive, but this consumer stopped receiving) — the
  // 2026-07-07 eval-starvation signature. Stamped on receipt in onCandleMessage; sole writer is the
  // receive path (a forced re-subscribe does NOT stamp it, so the latch clears only on a real bar).
  private volatile long lastBarReceivedAtMs;
  // Eval-side heartbeat: wall-clock millis of the last COMPLETED drain() batch, stamped ON the
  // signal-eval thread (audit A13, RC-1). lastBarReceivedAtMs only proves bars are ARRIVING (it is
  // stamped on the Redis dispatch thread); a stall in evaluation keeps it fresh, so the canary read
  // "receiving normally" all through the 2026-07-10 14:52 eval stall. SubscriberHealthCanary compares
  // received−evaluated (NOT wall-clock, so a quiet market that freezes both does not false-alarm) to
  // catch bars-arriving-but-not-processed. Sole writer is the eval thread (single-threaded → no race).
  private volatile long lastBarEvaluatedAtMs;
  /**
   * The registry's published+enabled version-id set AS OF the last {@link #reload()} — the reconcile
   * baseline. Comparing the CURRENT published set against this (not against the LOADED subset) is what
   * lets the 20s reconcile detect a genuine registry change while NOT looping forever on strategies
   * the engine deliberately skips at load (swing, non-rollable-primary, empty-universe, load-error).
   */
  private volatile String lastReloadedPublishedSet = "";
  private volatile RedisMessageListenerContainer container;

  private final Timer evalTimer;
  private final Counter emitted;
  private final Counter evalFailures;
  // Audit emit-entry-not-transactional: each emit path's dependent writes commit atomically —
  // a mid-sequence failure must never leave an ENTRY without its option leg / suggested qty,
  // or an EXIT inserted with the entry anchor still ACTIVE (duplicate EXIT next bar).
  private final org.springframework.transaction.support.TransactionTemplate tx;

  /** Wires the engine. */
  public SignalEngine(
      StrategyRepository registry,
      SignalRepository signals,
      SignalPublisher publisher,
      ApplicationEventPublisher events,
      LiveSeriesStore seriesStore,
      FuturesUniverseResolver futuresResolver,
      RedisConnectionFactory connectionFactory,
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry meterRegistry,
      java.util.Optional<EmissionGuard> emissionGuard,
      java.util.Optional<ScalperConfluenceGate> scalperGate,
      SignalRejectionRepository rejections,
      ShadowBookService shadowBook,
      org.springframework.transaction.PlatformTransactionManager transactionManager,
      @Value("${artha.signals.ttl-minutes:60}") int signalTtlMinutes) {
    this.registry = registry;
    this.signals = signals;
    this.rejections = rejections;
    this.shadowBook = shadowBook;
    this.publisher = publisher;
    this.events = events;
    this.seriesStore = seriesStore;
    this.futuresResolver = futuresResolver;
    this.connectionFactory = connectionFactory;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.lastBarReceivedAtMs = clock.millis(); // boot grace — no false stall before the first bar
    this.lastBarEvaluatedAtMs = clock.millis(); // boot grace — received−evaluated starts at ~0
    this.signalTtlMinutes = signalTtlMinutes;
    this.emissionGuard = emissionGuard;
    this.scalperGate = scalperGate;
    this.evalTimer = meterRegistry.timer("ay_signal_eval_duration_seconds");
    this.emitted = meterRegistry.counter("ay_signals_emitted_total");
    this.evalFailures = meterRegistry.counter("ay_signal_eval_failures_total");
    this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  }

  /** Boots subscriptions once the app is ready. */
  @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public synchronized void start() {
    reload();
  }

  @EventListener(ContextClosedEvent.class)
  void stop() {
    if (container != null) {
      container.stop();
    }
    evalExecutor.shutdownNow();
    recoveryExecutor.shutdownNow();
  }

  /** (Re)loads published+enabled strategies and rebuilds subscriptions. */
  public synchronized void reload() {
    reloadRequested.set(false);
    bankCache.clear(); // definitions/universes may have changed — banks rebuild on next bar (P1-12)
    List<Loaded> fresh = new ArrayList<>();
    List<StrategyRepository.StrategyRow> all = registry.listAll();
    for (StrategyRepository.StrategyRow strategy : all) {
      if (!strategy.enabled() || strategy.publishedVersionId() == null) {
        continue;
      }
      Optional<StrategyRepository.VersionRow> versionRow =
          registry.findVersionById(strategy.publishedVersionId());
      if (versionRow.isEmpty()) {
        continue;
      }
      try {
        JsonNode config = versionRow.get().config();
        StrategyDefinition definition = StrategyCompiler.compile(config);
        // Track-2 (S24): the gate-arming tags are the SINGLE SOURCE OF TRUTH on the PUBLISHED config
        // (what the editor shows), NOT the strategy identity row — so editing + publishing the YAML is
        // what arms a scalper. The row tags (registry.update keeps them in lockstep) drive only the
        // list/filters. A strategy tagged `scalper` over an options_of_underlying universe carries the
        // §0B knobs (the confluence seam reads them); every other strategy stays null = unaffected.
        List<String> configTags = new ArrayList<>();
        config.path("tags").forEach(t -> configTags.add(t.asText()));
        ScalperConfig scalper =
            configTags.contains("scalper")
                    && "options_of_underlying".equals(config.path("universe").path("mode").asText())
                ? ScalperConfig.from(config, configTags)
                : null;
        // §0B hard-stop rule: a scalper without a fixed SL or a time-stop could ride an unbounded
        // losing option — refuse to load it rather than emit signals it can never safely exit.
        if (scalper != null && !ScalperRisk.hasBoundingExit(definition.exitRules())) {
          log.warn(
              "scalper {} has no hard stop / time-stop exit — not loaded (§0B hard-SL rule)",
              strategy.slug());
          continue;
        }
        // Phase-9: swing strategies (session.style=swing, 1d primary) are driven by the daily
        // SwingBatchEngine (per family), NOT the tick loop — their equities do not tick. Skip
        // them here cleanly (not an error) so the batch owns them and the ROLLABLE check below never
        // logs a spurious "not live-rollable" warning for a strategy that is working as designed.
        if ("swing".equals(definition.session().style())) {
          continue;
        }
        // A non-rollable primary (e.g. '1d' on a non-btst strategy) used to silently degrade to
        // per-1m evaluation via intervalDuration's old 1m default — hundreds of pointless REST
        // refreshes/hour and a live-vs-replay divergence (the golden runner throws). Refuse the
        // load loudly instead (audit interval-duration-silent-default).
        if (!"btst".equals(definition.session().style())
            && !ROLLABLE_PRIMARIES.contains(definition.primaryTimeframe())) {
          log.warn(
              "strategy {} primary '{}' is not live-rollable (needs 1m/3m/5m/15m/1h) — not loaded",
              strategy.slug(), definition.primaryTimeframe());
          continue;
        }
        List<StrategyDefinition.InstrumentRef> universe = resolveUniverse(config);
        if (universe.isEmpty()) {
          log.warn("strategy {} resolves to an empty universe — not loaded", strategy.slug());
          continue;
        }
        Set<SeriesKey> keys = new LinkedHashSet<>();
        for (StrategyDefinition.InstrumentRef instrument : universe) {
          keys.add(new SeriesKey(instrument.exchange(), instrument.tradingsymbol(), "1m"));
          if (!definition.primaryTimeframe().equals("1m")) {
            keys.add(
                new SeriesKey(
                    instrument.exchange(), instrument.tradingsymbol(),
                    definition.primaryTimeframe()));
          }
        }
        for (StrategyDefinition.IndicatorSpec spec : definition.indicators()) {
          if (spec.instrument() != null) {
            // A7 context symbols: series inputs only, never signal emitters
            keys.add(
                new SeriesKey(
                    spec.instrument().exchange(), spec.instrument().tradingsymbol(),
                    spec.timeframe()));
            keys.add(
                new SeriesKey(
                    spec.instrument().exchange(), spec.instrument().tradingsymbol(), "1m"));
          }
        }
        // §3.2a: warm each declared higher-TF series on the SIGNAL future itself (e.g. bias60m@1h,
        // rsi@1d) on every universe instrument — else IndicatorBank.build throws "no series coverage"
        // and the strategy silently emits nothing on the live path.
        for (String tf : higherTimeframes(definition.primaryTimeframe(), definition.indicators())) {
          for (StrategyDefinition.InstrumentRef instrument : universe) {
            keys.add(new SeriesKey(instrument.exchange(), instrument.tradingsymbol(), tf));
          }
        }
        keys.forEach(seriesStore::ensureWarm);
        fresh.add(
            new Loaded(
                strategy.id(), versionRow.get().id(), strategy.slug(), strategy.name(),
                versionRow.get().version(), versionRow.get().checksum(), definition,
                universe, keys, scalper, Books.fromTags(strategy.tags())));
      } catch (RuntimeException e) {
        log.error("strategy {} failed to load — skipped: {}", strategy.slug(), e.getMessage());
      }
    }
    this.loaded = List.copyOf(fresh);
    // Snapshot the published set THIS reload was based on (from the same registry read), so the 20s
    // reconcile compares registry-vs-registry and converges even though `loaded` is a subset.
    this.lastReloadedPublishedSet = publishedVersionSetOf(all);
    resubscribe();
    log.info("signal engine loaded {} published strategies", fresh.size());
  }

  private List<StrategyDefinition.InstrumentRef> resolveUniverse(JsonNode config) {
    JsonNode universe = config.path("universe");
    String mode = universe.path("mode").asText();
    return switch (mode) {
      case "explicit" -> {
        List<StrategyDefinition.InstrumentRef> instruments = new ArrayList<>();
        for (JsonNode node : universe.path("instruments")) {
          instruments.add(
              new StrategyDefinition.InstrumentRef(
                  node.path("exchange").asText(), node.path("tradingsymbol").asText()));
        }
        yield instruments;
      }
      case "futures_of_underlying" ->
          // A7/A11: live trades the ACTUAL front/next contract; roll re-subscribe is the
          // daily re-resolution below
          futuresResolver.resolve(
              universe.path("underlying").path("exchange").asText(),
              universe.path("underlying").path("tradingsymbol").asText(),
              universe.path("futures").path("contract").asText("front_month"),
              universe.path("futures").path("roll_days_before_expiry").asInt(1));
      case "options_of_underlying" -> {
        // Phase 3 / Model A: the scalper EVALUATES + CHARTS on the index FRONT FUTURE (it carries the
        // volume the §0B VWAP/VWMA gates need); the option to TRADE is picked at signal time by the
        // confluence seam. 2c decoupling: a SENSEX variant signals on the NIFTY future, so the signal
        // future is resolved from the SIGNAL index (universe.signal_underlying mapped to its index),
        // not the option-root underlying. Absent signal_underlying ⇒ the underlying (unchanged).
        ScalperConfig.IndexRef sig = ScalperConfig.signalIndex(universe);
        yield futuresResolver.resolve(
            sig.exchange(),
            sig.tradingsymbol(),
            universe.path("futures").path("contract").asText("front_month"),
            universe.path("futures").path("roll_days_before_expiry").asInt(2));
      }
      case "futures_screener" ->
          // E1 §3.3: the dynamic Market-Movers stock-future universe — re-screened each reload
          // (08:40 + hot-swap), each picked mover mapped to its front contract + auto-subscribed.
          futuresResolver.resolveScreener(
              universe.path("side").asText("long"),
              universe.path("max_picks").asInt(5),
              universe.path("source").asText("captured"));
      default -> {
        // index_constituents cannot publish until Phase 44 (the registry guard) — defensive
        log.warn("universe mode '{}' is not live-resolvable yet", mode);
        yield List.of();
      }
    };
  }

  private synchronized void resubscribe() {
    // Start the NEW container BEFORE stopping the old one: Redis pub/sub is fire-and-forget, so a
    // stop-then-start gap permanently loses any 1m bar published in between (the 1m series is
    // never re-fetched after warm-up — audit resubscribe-gap-drops-1m-bars). A bar delivered by
    // BOTH containers during the overlap is skipped in onClosedBar (duplicate append ⇒ no re-eval).
    RedisMessageListenerContainer fresh = new RedisMessageListenerContainer();
    fresh.setConnectionFactory(connectionFactory);
    Set<String> channels = new LinkedHashSet<>();
    for (Loaded strategy : loaded) {
      for (SeriesKey key : strategy.seriesKeys()) {
        if (key.interval().equals("1m")) {
          channels.add("candles.1m." + key.exchange() + "." + key.tradingsymbol());
        }
      }
    }
    for (String channel : channels) {
      fresh.addMessageListener(
          (message, pattern) -> onCandleMessage(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)),
          new ChannelTopic(channel));
    }
    fresh.addMessageListener(
        (message, pattern) -> {
          // hot-swap NEVER lands mid-bar: the single eval thread processes each bar
          // atomically, so a reload queued behind it lands exactly on a bar boundary
          log.info("strategy.changed received — hot-swap at next bar boundary");
          reloadRequested.set(true);
          evalExecutor.execute(this::drainReloadOnly);
        },
        new ChannelTopic(in.arthayantra.strategysignal.registry.StrategyChangedPublisher.CHANNEL));
    fresh.afterPropertiesSet();
    fresh.start();
    RedisMessageListenerContainer old = this.container;
    this.container = fresh;
    if (old != null) {
      old.stop();
    }
    log.info("subscribed {} candle channels (universes + context series only)", channels.size());
  }

  /** Wall-clock millis of the last candle message received — the subscriber-liveness heartbeat. */
  long lastBarReceivedAtMs() {
    return lastBarReceivedAtMs;
  }

  /** Wall-clock millis of the last completed {@code drain()} batch — the eval-side heartbeat. */
  long lastBarEvaluatedAtMs() {
    return lastBarEvaluatedAtMs;
  }

  /**
   * True iff any loaded strategy subscribes a 1m channel, so {@link SubscriberHealthCanary} stays
   * quiet when there is nothing to receive (no intraday strategy loaded / all-empty-universe session).
   */
  boolean hasOneMinuteSubscriptions() {
    for (Loaded strategy : loaded) {
      for (SeriesKey key : strategy.seriesKeys()) {
        if (key.interval().equals("1m")) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Watchdog recovery: request a candle re-subscription (overlap-safe — see {@link #resubscribe()}).
   * Runs the rebuild on the dedicated {@code subscriber-recovery} thread, NOT the caller's thread and
   * NOT the eval thread. The original #634 design routed this through {@code evalExecutor} so the
   * SCHEDULED sweep thread would never hold the SignalEngine monitor across the container's blocking
   * Redis I/O — but that also made recovery for a receive-drop queue BEHIND a blocked eval task, so a
   * stalled eval loop could never re-subscribe itself out (audit A13, RC-1). A dedicated executor keeps
   * both #634 invariants while removing that coupling:
   *
   * <ul>
   *   <li><b>Sweep never blocks on Redis I/O:</b> the sweep thread only does a non-blocking
   *       {@code recoveryExecutor.execute(...)} (an unbounded-queue enqueue) and returns — it never
   *       acquires the SignalEngine monitor and never touches the container.
   *   <li><b>No monitor deadlock:</b> {@code resubscribe()} is {@code synchronized} on
   *       {@code SignalEngine.this} (monitor M). M is acquired by the recovery thread (here), the eval
   *       thread (reload→resubscribe), and the boot thread (start). The container's Redis lifecycle
   *       ({@code afterPropertiesSet}/{@code start}/{@code stop}) never acquires M — the listener
   *       callback {@link #onCandleMessage} does not — so no thread ever holds a container-internal lock
   *       while waiting for M. The only shared lock is M, always released after a bounded Redis op, so
   *       a thread waiting on M cannot be blocked by a thread that is itself waiting on M ⇒ no cycle.
   *       Recovery holding M during its Redis I/O only DELAYS a concurrent eval-thread reload (bounded),
   *       never deadlocks it.
   * </ul>
   *
   * <p>Does NOT stamp the receive heartbeat — the stall latch clears only when a REAL bar arrives, so a
   * re-subscribe that fails to restore delivery keeps being retried (and stays visibly stalled).
   */
  void forceResubscribe(String reason) {
    log.warn("subscriber watchdog: requesting candle re-subscription — {}", reason);
    recoveryExecutor.execute(
        () -> {
          try {
            resubscribe();
          } catch (RuntimeException e) {
            log.error("subscriber watchdog: forced re-subscription failed: {}", e.toString());
          }
        });
  }

  /** Redis receive thread: parse + conflate + hand off. NEVER evaluates here. */
  void onCandleMessage(String json) {
    lastBarReceivedAtMs = clock.millis(); // subscriber-liveness heartbeat (SubscriberHealthCanary)
    try {
      JsonNode node = objectMapper.readTree(json);
      EngineCandle candle =
          new EngineCandle(
              OffsetDateTime.parse(node.path("bucket").asText()),
              new BigDecimal(node.path("open").asText()),
              new BigDecimal(node.path("high").asText()),
              new BigDecimal(node.path("low").asText()),
              new BigDecimal(node.path("close").asText()),
              node.path("volume").asLong(),
              node.hasNonNull("oi") ? new BigDecimal(node.path("oi").asText()) : null);
      String symbolKey = node.path("exchange").asText() + ":" + node.path("tradingsymbol").asText();
      pending.add(Map.entry(symbolKey, candle)); // queue, never collapse — see the `pending` field
      if (drainScheduled.compareAndSet(false, true)) {
        evalExecutor.execute(this::drain);
      }
    } catch (Exception e) {
      log.warn("unparseable candle message dropped: {}", e.getMessage());
    }
  }

  private void drain() {
    drainScheduled.set(false);
    if (reloadRequested.get()) {
      reload(); // hot-swap lands exactly at a bar boundary, never mid-bar
    }
    Map.Entry<String, EngineCandle> head;
    while ((head = pending.poll()) != null) {
      String[] parts = head.getKey().split(":", 2);
      EngineCandle bar = head.getValue();
      evalTimer.record(() -> onClosedBar(parts[0], parts[1], bar));
    }
    // Eval-side heartbeat (audit A13): stamped on THIS (signal-eval) thread only when the batch has
    // fully drained, so a stall INSIDE onClosedBar freezes it while bars keep being received — the
    // signature SubscriberHealthCanary alarms on. See lastBarEvaluatedAtMs.
    lastBarEvaluatedAtMs = clock.millis();
  }

  private void onClosedBar(String exchange, String tradingsymbol, EngineCandle bar) {
    if (!seriesStore.append(new SeriesKey(exchange, tradingsymbol, "1m"), bar)) {
      // Duplicate/stale redelivery (the resubscribe overlap window, or a replayed message):
      // re-evaluating the same bar could fire a phantom instant exit (an entry-candle structural
      // stop touches its own extreme by definition) or a stale-priced entry — skip entirely.
      return;
    }
    for (Loaded strategy : loaded) {
      boolean inUniverse =
          strategy.universe().stream()
              .anyMatch(
                  i ->
                      i.exchange().equals(exchange) && i.tradingsymbol().equals(tradingsymbol));
      if (!inUniverse) {
        continue; // context symbols feed series only — never evaluated as signal emitters
      }
      if (!withinSessionWindow(strategy.definition(), bar)) {
        continue;
      }
      try {
        if (strategy.definition().primaryTimeframe().equals("1m")) {
          evaluateAtBarClose(strategy, exchange, tradingsymbol, bar, "1m");
        } else if (!"btst".equals(strategy.definition().session().style())) {
          evaluateCoarsePrimary(strategy, exchange, tradingsymbol, bar);
        }
        // btst primaries evaluate ONLY at the pre-close clock (scheduled below)
      } catch (RuntimeException e) {
        // The bar is already consumed off the queue — a failed ENTRY decision for it is gone for
        // good (the next qualifying bar re-fires; EXIT anchors stay ACTIVE and self-heal). The
        // counter makes a burst alertable instead of log-only (audit bar-eval-failure-drops-entry).
        evalFailures.increment();
        log.error(
            "evaluation failed for {} on {}:{}: {}",
            strategy.slug(), exchange, tradingsymbol, e.getMessage());
      }
    }
  }

  private void evaluateAtBarClose(
      Loaded strategy, String exchange, String tradingsymbol, EngineCandle bar, String interval) {
    // Long-lived bank per (version, instrument) — the D17 lesson applied live (audit P1-12):
    // rebuilding per bar gave every evaluation a COLD ta4j cache, recomputing recursive
    // indicators (EMA/RSI/SUPERTREND) from bar 0 in BigDecimal math — O(n²) over the session on
    // the single eval thread. The underlying EngineSeries instances are mutated in place (never
    // replaced) by LiveSeriesStore, and indicators are pure functions of (series, index), so a
    // warm bank stays correct as bars append; reload()/hot-swap clears the cache.
    IndicatorBank bank =
        bankCache.computeIfAbsent(
            strategy.versionId() + "|" + exchange + ":" + tradingsymbol,
            key ->
                IndicatorBank.build(
                    strategy.definition(),
                    new StrategyDefinition.InstrumentRef(exchange, tradingsymbol),
                    seriesStore));
    EngineSeries primary = bank.primarySeries();
    int index = primary.size() - 1;

    Optional<SignalRepository.SignalRow> activeEntry =
        signals.activeEntry(strategy.versionId(), exchange, tradingsymbol);
    if (activeEntry.isPresent()) {
      // §3.1/§3.6 structural stop (scalper, live-only): the persisted stop_loss is the captured
      // 1st-candle / entry-candle extreme on the index future. A bar that touches it exits FIRST
      // (protective priority, mirroring ExitEvaluator's stop_loss-wins precedence). The confluence
      // path is live-only, so this never affects the deterministic golden replay.
      if (strategy.scalper() != null
          && activeEntry.get().stopLoss() != null
          && structuralStopHit(
              scalperPositionDirection(strategy, activeEntry.get()),
              primary.candle(index), activeEntry.get().stopLoss())) {
        emit(strategy, exchange, tradingsymbol, interval, "EXIT", bar, activeEntry.get(),
            "STRUCTURAL_STOP");
        return;
      }
      // E9 D4 OI-confluence-flip exit (scalper, live-only, tag oi-confluence-exit): re-read the OI
      // confluence at this bar; if it now STRONGLY confirms the OPPOSITE side to the one held, the read
      // has flipped against the position — exit. Reuses the entry gate (never runs on the deterministic
      // golden replay → parity-safe by firewall). Held side rides the entry's scalper side-channel.
      if (strategy.scalper() != null
          && strategy.scalper().has("oi-confluence-exit")
          && scalperGate.isPresent()
          && activeEntry.get().scalperDetail() != null
          && confluenceFlipExit(strategy, bank, primary, index, bar, activeEntry.get())) {
        emit(strategy, exchange, tradingsymbol, interval, "EXIT", bar, activeEntry.get(),
            "CONFLUENCE_FLIP");
        return;
      }
      int entryIndex =
          entryAnchorIndex(primary, interval, activeEntry.get().generatedAt().toInstant());
      Optional<ExitEvaluator.ExitDecision> exit =
          ExitEvaluator.evaluate(
              strategy.definition(), bank,
              new ExitEvaluator.Position(
                  scalperPositionDirection(strategy, activeEntry.get()),
                  activeEntry.get().entryPrice(),
                  entryIndex),
              index);
      if (exit.isPresent()) {
        emit(strategy, exchange, tradingsymbol, interval, "EXIT", bar, activeEntry.get(),
            exit.get().type().toUpperCase(java.util.Locale.ROOT));
        return;
      }
    } else {
      Optional<EntryEvaluator.Evaluation> evaluation =
          EntryEvaluator.evaluate(strategy.definition(), bank, index);
      if (evaluation.isPresent() && evaluation.get().entry()) {
        if (strategy.scalper() != null) {
          scalperEntry(strategy, exchange, tradingsymbol, interval, bar, evaluation.get(), bank, primary, index);
        } else {
          emitEntry(strategy, exchange, tradingsymbol, interval, bar, evaluation.get(), null, null);
        }
      }
    }
  }

  /**
   * Track-2 entry: the chart gate passed; now the §12.3 confluence seam must also confirm and pick
   * the option, or the entry is blocked. Fail-closed — a scalper strategy without the gate never
   * fires. The signal is keyed on the index FUTURE (this {@code exchange}/{@code tradingsymbol}); the
   * picked option rides the side-channel.
   */
  private void scalperEntry(
      Loaded strategy, String exchange, String tradingsymbol, String interval, EngineCandle bar,
      EntryEvaluator.Evaluation evaluation, BarValues bank, EngineSeries future, int index) {
    if (scalperGate.isEmpty()) {
      log.warn("scalper strategy {} loaded but confluence gate absent — entry suppressed", strategy.slug());
      return;
    }
    // §12.7 scalper 5-account discipline: 5 losses freeze all sub-accounts / 5 wins bank the day.
    // Consulted IN ADDITION to the global risk gate (checked later in emitEntry); scalper entries only.
    if (emissionGuard.isPresent() && !emissionGuard.get().scalperEntryAllowed()) {
      log.info("scalper ENTRY paused by the 5-account discipline: {} {}:{}", strategy.slug(), exchange, tradingsymbol);
      return;
    }
    OffsetDateTime istBar = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    ScalperConfluenceGate.Result result =
        scalperGate.get().evaluateWithDiagnostic(
            strategy.scalper(), bank, future, index, bar.bucketStart().toInstant(),
            istBar.toLocalTime(), istBar.toLocalDate());
    if (result.blocked()) {
      recordRejection(strategy, exchange, tradingsymbol, interval, istBar, result.rejection());
      return;
    }
    emitEntry(
        strategy, exchange, tradingsymbol, interval, bar, evaluation, result.decision().get(),
        result.fired());
  }

  private void evaluateCoarsePrimary(
      Loaded strategy, String exchange, String tradingsymbol, EngineCandle bar) {
    String primaryInterval = strategy.definition().primaryTimeframe();
    SeriesKey primaryKey = new SeriesKey(exchange, tradingsymbol, primaryInterval);
    Duration duration = intervalDuration(primaryInterval);
    long epoch = bar.bucketStart().toEpochSecond();
    boolean bucketBoundary = Math.floorMod(epoch, duration.toSeconds()) == 0;
    if (bucketBoundary) {
      // the bucket this bar OPENS means the previous one just completed in the caggs
      seriesStore.refreshFromRest(primaryKey);
      // §3.2a: refresh each declared higher-TF series so a higher-TF gate (e.g. a 5m/daily RSI cap or
      // a 15m ST) reads a fresh higher-TF bar intraday (mirrors the BTST daily refresh). 1m + the
      // primary are already covered above.
      for (String tf :
          higherTimeframes(
              strategy.definition().primaryTimeframe(), strategy.definition().indicators())) {
        seriesStore.refreshFromRest(new SeriesKey(exchange, tradingsymbol, tf));
      }
      evaluateAtBarClose(strategy, exchange, tradingsymbol, bar, primaryInterval);
      return;
    }
    // A9 [FP-5]: 1m exit-level pass while a position-anchoring entry is active
    if (strategy.definition().session().exitIntrabar()) {
      Optional<SignalRepository.SignalRow> activeEntry =
          signals.activeEntry(strategy.versionId(), exchange, tradingsymbol);
      if (activeEntry.isPresent()) {
        EngineSeries primary = seriesStore.series(primaryKey);
        EngineSeries oneMinute = seriesStore.series(new SeriesKey(exchange, tradingsymbol, "1m"));
        if (primary == null || oneMinute == null) {
          return;
        }
        int entryPrimaryIndex =
            entryAnchorIndex(primary, primaryInterval, activeEntry.get().generatedAt().toInstant());
        // the 1m anchor stays generatedAt itself: the 1m TRIGGER bar exists in the 1m series and
        // is the correct 1m-scan start (no coarse off-by-one applies on the 1m axis)
        int entryOneMinuteIndex =
            Math.max(oneMinute.indexAtOrBefore(activeEntry.get().generatedAt().toInstant()), 0);
        Optional<ExitEvaluator.ExitDecision> exit =
            ExitEvaluator.evaluateIntrabarLevels(
                strategy.definition(), primary, entryPrimaryIndex, oneMinute,
                scalperPositionDirection(strategy, activeEntry.get()), activeEntry.get().entryPrice(),
                entryOneMinuteIndex, oneMinute.size() - 1);
        if (exit.isPresent()) {
          emit(strategy, exchange, tradingsymbol, "1m", "EXIT", bar, activeEntry.get(),
              exit.get().type().toUpperCase(java.util.Locale.ROOT));
        }
      }
    }
  }

  /** A9 [FP-6]: the pre-close BTST clock — every minute, fire strategies whose time matches. */
  @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
  public void preCloseClock() {
    LocalTime now = LocalTime.now(clock.withZone(Ist.ZONE)).withSecond(0).withNano(0);
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    for (Loaded strategy : loaded) {
      if (!"btst".equals(strategy.definition().session().style())) {
        continue;
      }
      if (!LocalTime.parse(strategy.definition().session().preCloseAt()).equals(now)) {
        continue;
      }
      String doneKey = strategy.versionId().toString();
      if (today.equals(preCloseDone.get(doneKey))) {
        continue;
      }
      preCloseDone.put(doneKey, today);
      for (StrategyDefinition.InstrumentRef instrument : strategy.universe()) {
        evalExecutor.execute(() -> preCloseEvaluate(strategy, instrument, today));
      }
    }
  }

  private void preCloseEvaluate(
      Loaded strategy, StrategyDefinition.InstrumentRef instrument, LocalDate today) {
    EngineSeries oneMinute =
        seriesStore.series(
            new SeriesKey(instrument.exchange(), instrument.tradingsymbol(), "1m"));
    if (oneMinute == null || oneMinute.size() == 0) {
      return;
    }
    List<EngineCandle> dayBars = new ArrayList<>();
    for (int i = oneMinute.sessionStart(oneMinute.size() - 1); i < oneMinute.size(); i++) {
      dayBars.add(oneMinute.candle(i));
    }
    if (dayBars.isEmpty() || !EngineSeries.sessionDate(dayBars.get(0)).equals(today)) {
      return;
    }
    // E12 §3.8 avoid-Friday carry (tag avoid-friday-carry): a BTST/STBT position opened on a Friday
    // carries weekend-gap risk (2+ nights, beyond the strategy's "<=1 night" mandate) — skip the carry
    // entirely on Fridays. Default-OFF; only the btst YAMLs carrying the tag opt in.
    if (strategy.scalper() != null
        && strategy.scalper().has("avoid-friday-carry")
        && today.getDayOfWeek() == java.time.DayOfWeek.FRIDAY) {
      return;
    }
    SeriesKey dailyKey =
        new SeriesKey(instrument.exchange(), instrument.tradingsymbol(), "1d");
    seriesStore.refreshFromRest(dailyKey);
    EngineSeries daily = seriesStore.series(dailyKey);
    if (daily == null) {
      return;
    }
    // E11 §3.8 route-through-gate: the carry is validated by the LIVE confluence seam, which reads the
    // PRIMARY (e.g. 3m) future + its higher-TF series — but the intraday tick path is skipped for the
    // btst style, so those series are stale at the pre-close clock. Refresh them now (mirrors
    // evaluateCoarsePrimary) so the gate reads a fresh close-bar confluence.
    SeriesKey primaryKey =
        new SeriesKey(
            instrument.exchange(), instrument.tradingsymbol(), strategy.definition().primaryTimeframe());
    seriesStore.refreshFromRest(primaryKey);
    for (String tf :
        higherTimeframes(strategy.definition().primaryTimeframe(), strategy.definition().indicators())) {
      seriesStore.refreshFromRest(new SeriesKey(instrument.exchange(), instrument.tradingsymbol(), tf));
    }
    // the deterministic pre-close bar view, appended for evaluation (A9 — identical in replay)
    EngineCandle preCloseBar = TickwiseGoldenRunner.preCloseDailyBar(dayBars);
    try {
      daily.append(preCloseBar);
    } catch (IllegalArgumentException alreadyHasToday) {
      // the 1d cagg already rolled today's bucket — evaluate on what's there
    }
    IndicatorBank bank = IndicatorBank.build(strategy.definition(), instrument, seriesStore);
    int index = daily.size() - 1;
    Optional<EntryEvaluator.Evaluation> evaluation =
        EntryEvaluator.evaluate(strategy.definition(), bank, index);
    if (evaluation.isPresent() && evaluation.get().entry()) {
      EngineCandle lastOneMinute = dayBars.get(dayBars.size() - 1);
      // E11 §3.8: a scalper BTST carry routes through the LIVE confluence gate (NOT a decision==null
      // bypass) — the same OI/macro/chart confluence the intraday scalps use must confirm the carry,
      // and the gate picks the option leg. The side is the day-close LOCATION (toward the day HIGH ⇒
      // CE/BTST, toward the LOW ⇒ PE/STBT — §3.8's BTST-vs-STBT split). Blocked ⇒ no carry (fail-closed,
      // NEUTRAL on derived history ⇒ ~0 backtest trades). Parity-safe: the golden runner injects no
      // scalperGate, so the deterministic btst replay never reaches this branch.
      if (strategy.scalper() != null && scalperGate.isPresent()) {
        EngineSeries primary = bank.primarySeries();
        if (primary == null || primary.size() == 0) {
          return;
        }
        OptionType carrySide = btstCarrySide(dayBars);
        OffsetDateTime istBar = lastOneMinute.bucketStart().withOffsetSameInstant(Ist.OFFSET);
        ScalperConfluenceGate.Result result =
            scalperGate.get().evaluateWithDiagnostic(
                strategy.scalper(), bank, primary, primary.size() - 1,
                lastOneMinute.bucketStart().toInstant(), istBar.toLocalTime(), today, carrySide, true);
        if (result.blocked()) {
          recordRejection(
              strategy, instrument.exchange(), instrument.tradingsymbol(), "1d", istBar,
              result.rejection());
          return;
        }
        emitEntry(
            strategy, instrument.exchange(), instrument.tradingsymbol(), "1d", lastOneMinute,
            evaluation.get(), result.decision().get(), result.fired());
        return;
      }
      emitEntry(
          strategy, instrument.exchange(), instrument.tradingsymbol(), "1d", lastOneMinute,
          evaluation.get(), null, null);
    }
  }

  /**
   * E11 §3.8 the BTST-vs-STBT side from the day's close LOCATION: a close in the UPPER half of the
   * day range ⇒ CE (a bullish BTST carry — price finishing near the day high), the LOWER half ⇒ PE
   * (a bearish STBT carry — finishing near the day low).
   */
  private static OptionType btstCarrySide(List<EngineCandle> dayBars) {
    BigDecimal high = dayBars.get(0).high();
    BigDecimal low = dayBars.get(0).low();
    for (EngineCandle c : dayBars) {
      if (c.high().compareTo(high) > 0) {
        high = c.high();
      }
      if (c.low().compareTo(low) < 0) {
        low = c.low();
      }
    }
    BigDecimal close = dayBars.get(dayBars.size() - 1).close();
    return close.subtract(low).compareTo(high.subtract(close)) >= 0 ? OptionType.CE : OptionType.PE;
  }

  private void emitEntry(
      Loaded strategy, String exchange, String tradingsymbol, String interval, EngineCandle bar,
      EntryEvaluator.Evaluation evaluation, ScalperConfluenceGate.Decision decision,
      ScalperConfluenceGate.FiredDiagnostic firedDiagnostic) {
    // Per-book risk gate: a daily-loss trip / kill switch / max-open cap pauses ENTRY emission for
    // this strategy's book for the rest of the IST day — exit/stop evaluation (emit()) is NOT gated.
    if (emissionGuard.isPresent() && !emissionGuard.get().entryAllowed(strategy.book())) {
      log.info("ENTRY suppressed by {} risk gate: {} {}:{}",
          strategy.book(), strategy.slug(), exchange, tradingsymbol);
      return;
    }
    BigDecimal entryPrice = bar.close();
    BigDecimal stopLoss = levelFromRules(strategy.definition(), entryPrice, "stop_loss");
    // §3.1/§3.6 structural stop: a scalper anchors its stop on the 1st-candle (Two-Candle) or
    // entry-candle (Golden-Cross) extreme of the index future, captured at entry. It overrides the
    // (absent) YAML rule level and is the price the bar-close structural-stop exit check fires on.
    if (decision != null && decision.structuralStop() != null) {
      stopLoss = decision.structuralStop();
    }
    // W3 PR-4 (S24 ratification D36/D37/D30/D46, additive fallback/cap): an index_points stop_loss
    // rule bounds the stop to a fixed point distance (BN ~100 / N ~50-60 / SENSEX ~200-250) — the
    // FALLBACK when no other stop is set, and a CAP that clamps a too-wide structural stop to that
    // distance (the tighter of the two wins). Default-OFF: no YAML carries an index_points rule
    // today, so stopLoss is unchanged for every existing strategy.
    boolean shortDir =
        strategy.definition().direction() == StrategyDefinition.Direction.SHORT;
    BigDecimal pointStop =
        indexPointStopLevel(strategy.definition().exitRules(), shortDir, entryPrice);
    if (pointStop != null) {
      stopLoss = (stopLoss == null) ? pointStop : closerToEntry(entryPrice, stopLoss, pointStop);
    }
    BigDecimal target = levelFromRules(strategy.definition(), entryPrice, "take_profit");
    String side =
        strategy.definition().direction() == StrategyDefinition.Direction.SHORT ? "SELL" : "BUY";
    String breakdownJson = ScoreBreakdownJson.write(evaluation.breakdown());
    // generated_at is the TRIGGER bar's bucket instant, not wall-clock now(): it is persisted
    // explicitly (so the row and the channel payload carry the IDENTICAL instant). For a 1m
    // primary the trigger IS the evaluated bar; for a coarse primary it is the boundary 1m bar
    // that OPENS the bucket AFTER the one evaluated, so the exit-side anchor reconstruction
    // (entryAnchorIndex) subtracts one primary duration first to land on the EVALUATED bucket —
    // deterministic and matching the replay harness, which carries the evaluated index directly.
    OffsetDateTime generatedAt = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    OffsetDateTime expiresAt = generatedAt.plusMinutes(signalTtlMinutes);
    // A12 suggested qty (lot-rounded sizing vs paper equity), stamped OUTSIDE the score breakdown.
    // Computed BEFORE the insert (it may call market-data REST) so the row + its stamps commit in
    // one tight transaction below — never a DB txn held open across an HTTP call.
    BigDecimal suggestedQty = null;
    if (emissionGuard.isPresent()) {
      BigDecimal stopDistance = stopLoss == null ? null : entryPrice.subtract(stopLoss).abs();
      // E8 §3.2: a probability-graded size multiplier off the confluence aggregate — scalper decisions
      // only (null for non-scalper signals → ungraded). Applied + lot-rounded inside the paper adapter;
      // defaults to 1.0 so the stamped qty is byte-identical until a weak-vs-strong spread is present.
      BigDecimal sizeMultiplier =
          decision == null
              ? null
              : in.arthayantra.strategysignal.scalper.ScalperSizing.sizeMultiplier(
                  decision.confluence().aggregate(), decision.oiImbalancePct(), decision.vixLevel());
      suggestedQty =
          emissionGuard.get().suggestedQty(
              strategy.definition().sizing(), exchange, tradingsymbol, entryPrice, stopDistance,
              sizeMultiplier, strategy.book());
      // E9/§3.7 hero-zero profit-funded sizing: the expiry-day hero-zero leg deploys ~10% of accumulated
      // realised PROFIT ("play with house money, never capital") with a ₹2.5k floor when profits are thin
      // (owner: mode a if enough profit, else mode b). Sized off the OPTION premium (not the index-priced
      // default), so it OVERRIDES the ordinary advisory qty — hero-zero family only.
      if (decision != null
          && strategy.scalper() != null
          && strategy.scalper().requireHeroZero()
          && decision.pick().candidate().ltp() != null) {
        BigDecimal hzQty =
            emissionGuard.get().heroZeroSuggestedQty(
                exchange,
                decision.pick().candidate().tradingsymbol(),
                decision.pick().candidate().ltp());
        if (hzQty != null) {
          suggestedQty = hzQty;
        }
      }
    }
    // §12.9 Track-2 side-channel: the signal is keyed on the index future; record the option the
    // confluence picked (the order/paper layer trades it) + the confluence detail, OUTSIDE the
    // frozen score breakdown. Options trade on the same derivatives exchange as the index future.
    String scalperDetail =
        decision == null ? null : scalperDetailJson(decision, strategy.scalper(), exchange);
    // INT §13 row 19 / FID P1-8 fired-side rail-operand side-channel: serialize the confluence gate's full
    // condition matrix (built from the SAME evaluation the Decision came from — never re-evaluated, so it
    // is deterministic) mirroring signal_rejections.diagnostic's shape. Built HERE (not inside the tx) so a
    // serialization/persistence hiccup can never roll back the real ENTRY; stamped best-effort AFTER commit
    // below (a diagnostic must never break the live signal path — same doctrine as recordRejection).
    String firedDiagnosticJson = null;
    if (firedDiagnostic != null) {
      try {
        firedDiagnosticJson =
            in.arthayantra.strategysignal.scalper.FiredDiagnosticJson.write(
                objectMapper, firedDiagnostic);
      } catch (RuntimeException e) {
        // Symmetric with the post-commit stamp: a diagnostic-build failure degrades to a null
        // side-channel — it must never prevent the REAL entry (review LOW, 2026-07-12).
        log.warn("fired-diagnostic build failed — emitting entry without it", e);
      }
    }
    // One transaction: the ENTRY row, its suggested qty and its option leg are all-or-nothing. A
    // partial commit left an ACTIVE entry with no tradeable leg — the exit side then read a null
    // scalper_detail and silently fell back to the definition direction (wrong side for a PE scalp).
    BigDecimal stampQty = suggestedQty;
    BigDecimal stopLevel = stopLoss;
    long id =
        tx.execute(
            status -> {
              long newId =
                  signals.insert(
                      strategy.versionId(), exchange, tradingsymbol, interval, "ENTRY", side,
                      entryPrice, stopLevel, target, evaluation.breakdown().composite(),
                      breakdownJson, generatedAt, expiresAt);
              if (stampQty != null) {
                signals.stampSuggestedQty(newId, stampQty);
              }
              if (scalperDetail != null) {
                signals.stampScalperDetail(
                    newId, exchange, decision.pick().candidate().tradingsymbol(), scalperDetail);
              }
              return newId;
            });
    emitted.increment();
    // Best-effort post-commit stamp of the fired-side diagnostic (§13 row 19): NOT inside the tx above —
    // a scalper contrast diagnostic must never roll back / break the real ENTRY (mirrors recordRejection's
    // swallow-and-warn). No correctness path reads it back, so a rare miss is harmless.
    if (firedDiagnosticJson != null) {
      try {
        signals.stampFiredDiagnostic(id, firedDiagnosticJson);
      } catch (RuntimeException e) {
        log.warn("failed to stamp fired diagnostic for signal #{}: {}", id, e.toString());
      }
    }
    // the channel carries EXACTLY the persisted canonical bytes (divergence = FAIL criterion)
    JsonNode canonicalBreakdown;
    try {
      canonicalBreakdown = objectMapper.readTree(breakdownJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("canonical breakdown unparseable", e);
    }
    publisher.publish(
        id, strategy.versionId(), strategy.name(), strategy.slug(), strategy.version(),
        strategy.checksum(), strategy.book(), exchange, tradingsymbol, interval, "ENTRY", side,
        entryPrice, stopLoss, target, evaluation.breakdown().composite(), canonicalBreakdown,
        generatedAt);
    log.info(
        "ENTRY signal #{} {} {}:{} at {} (composite {})",
        id, strategy.slug(), exchange, tradingsymbol, entryPrice,
        evaluation.breakdown().composite());
    // in-process trigger for the Phase-41 notifier (push only ENTRY signals). For a scalper entry
    // (decision != null) we distil the V009 leg into the LIVE-only scalp side-channel so the Z1
    // scalp-alert listener renders strike/option-side/confluence without re-querying. This event is
    // published only on the live emit path — deterministic replay never reaches here, so the
    // golden vectors stay byte-identical.
    SignalEmitted.ScalpDetail scalp =
        decision == null
            ? null
            : new SignalEmitted.ScalpDetail(
                strategy.scalper().underlying(),
                decision.neutral() ? "NEUTRAL" : decision.side().name(),
                decision.pick().candidate().strike(),
                decision.pick().candidate().tradingsymbol(),
                decision.pick().candidate().ltp(),
                decision.confluence().aggregate(),
                decision.ohTier() == null ? null : decision.ohTier().name(),
                decision.ohTier() == null ? null : OpenHighLow.probabilityPct(decision.ohTier()),
                // the A12 lot-rounded advisory qty stamped on this row above — reused verbatim (never
                // recomputed) so the alert's qty matches /signals and the paper ticket exactly.
                suggestedQty);
    events.publishEvent(
        new SignalEmitted(
            id, strategy.versionId(), exchange, tradingsymbol, side, entryPrice, stopLoss, target,
            evaluation.breakdown().composite(), evaluation.breakdown().threshold(), scalp));
  }

  /**
   * The §12.9 confluence side-channel JSON — chosen option, |delta|, IV, aggregate, per-dot detail.
   * The directional shape is unchanged (byte-identical). A #11 NEUTRAL straddle ({@code side==null})
   * stamps {@code side:"NEUTRAL"}, the primary (CE) leg in the legacy {@code tradeable/strike/...}
   * fields, and a {@code legs[]} array carrying BOTH BUY legs ({exchange, tradingsymbol, side, option_type,
   * strike, option_ltp, iv, delta}) — the two-leg carrier the order/paper layer reads.
   */
  private String scalperDetailJson(
      ScalperConfluenceGate.Decision d, ScalperConfig cfg, String tradeableExchange) {
    StrikePicker.Candidate c = d.pick().candidate();
    ObjectNode root = objectMapper.createObjectNode();
    root.put("side", d.neutral() ? "NEUTRAL" : d.side().name());
    root.put("underlying", cfg.underlying());
    root.put("expiry", d.expiry().toString());
    root.put("tradeable", c.tradingsymbol());
    root.put("strike", c.strike());
    root.put("option_ltp", c.ltp());
    root.put("iv", c.iv());
    root.put("delta", d.pick().delta());
    root.put("confluence_aggregate", d.confluence().aggregate());
    // W4 6c (OIP-AI surfacing): the Open=High probability read (tier + % + HIGH badge), present only for
    // an open-high-low strategy that graded a tier — the live signal side-channel the Cockpit/alerts render.
    if (d.ohTier() != null) {
      root.put("oh_tier", d.ohTier().name());
      root.put("oh_prob_pct", OpenHighLow.probabilityPct(d.ohTier()));
      root.put("badge", OpenHighLow.badge(d.ohTier()));
    }
    ArrayNode dots = root.putArray("dots");
    for (ConnectTheDotsScorer.DotScore ds : d.confluence().dots()) {
      ObjectNode n = dots.addObject();
      n.put("dot", ds.dot());
      n.put("weight", ds.weight());
      n.put("supports", ds.supports());
    }
    // #11 (section 3.11) two-leg carrier: only a neutral straddle adds the legs[] array, so the
    // single-leg directional side-channel stays byte-identical. The order/paper layer trades these.
    if (d.neutral()) {
      ArrayNode legs = root.putArray("legs");
      for (ScalperConfluenceGate.Leg leg : d.legs()) {
        StrikePicker.Candidate lc = leg.pick().candidate();
        ObjectNode n = legs.addObject();
        n.put("exchange", tradeableExchange);
        n.put("tradingsymbol", lc.tradingsymbol());
        n.put("side", "BUY"); // long straddle = both legs BUY (short = SELL is SPAN-deferred)
        n.put("option_type", leg.optionType().name());
        n.put("strike", lc.strike());
        n.put("option_ltp", lc.ltp());
        n.put("iv", lc.iv());
        n.put("delta", leg.pick().delta());
      }
    }
    ScalperManualChecks.appendTo(root);
    return root.toString();
  }

  /**
   * Persists the live-only rejection diagnostic (WHY the confluence gate blocked this scalper
   * chart-entry) and logs the structured reason. LIVE path only — the deterministic replay never
   * reaches the gate, so this is never invoked on backtest (no rows there → parity-safe). Diagnostics
   * must never break the live signal path, so a persistence failure is swallowed with a warning.
   */
  private void recordRejection(
      Loaded strategy, String exchange, String tradingsymbol, String interval, OffsetDateTime barTime,
      ScalperConfluenceGate.RejectionDiagnostic d) {
    if (d == null) {
      log.info("scalper confluence blocked entry: {} {}:{} (no diagnostic)", strategy.slug(), exchange, tradingsymbol);
      return;
    }
    try {
      long rejectionId =
          rejections.insert(
              strategy.versionId(), strategy.slug(), exchange, tradingsymbol, interval,
              d.side() == null ? null : d.side().name(), d.blockingRail(), d.operand(), d.threshold(),
              d.margin(), d.reason(), d.compositeScore(), d.compositeThreshold(),
              rejectionDiagnosticJson(d), barTime);
      // Shadow book (default-OFF): an eligible rejection opens as a virtual 1-lot position so the
      // exit sweep labels it with a real PnL. maybeOpen never throws (diagnostics never break live).
      shadowBook.maybeOpen(
          rejectionId, strategy.versionId(), strategy.slug(), exchange, tradingsymbol, barTime, d);
    } catch (RuntimeException e) {
      log.warn(
          "failed to persist scalper rejection {} {}:{}: {}",
          strategy.slug(), exchange, tradingsymbol, e.toString());
    }
    log.info(
        "scalper confluence blocked entry: {} {}:{} rail={} operand={} threshold={} margin={} composite={}/{} ({})",
        strategy.slug(), exchange, tradingsymbol, d.blockingRail(), d.operand(), d.threshold(),
        d.margin(), d.compositeScore(), d.compositeThreshold(), d.reason());
  }

  /**
   * The full rejection diagnostic JSON: the blocking rail + margin, every rail evaluated up to the
   * block, the dot-by-dot Connect-the-Dots confluence (when the composite was reached), and the raw
   * OI/macro/chart context — the complete "why blocked" payload the Rejections page renders.
   */
  private String rejectionDiagnosticJson(ScalperConfluenceGate.RejectionDiagnostic d) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("blockingRail", d.blockingRail());
    root.put("side", d.side() == null ? null : d.side().name());
    root.put("operand", d.operand());
    root.put("threshold", d.threshold());
    root.put("margin", d.margin());
    root.put("reason", d.reason());
    root.put("compositeScore", d.compositeScore());
    root.put("compositeThreshold", d.compositeThreshold());
    // The would-be trade the block vetoed (signal-analysis §7.1) — present when the gate resolved
    // a leg before blocking; the shadow book + counterfactual analysis key off this.
    if (d.pick() != null) {
      ObjectNode leg = root.putObject("wouldBeLeg");
      leg.put("tradingsymbol", d.pick().candidate().tradingsymbol());
      leg.put("strike", d.pick().candidate().strike());
      leg.put("optionType", d.side() == null ? null : d.side().name());
      leg.put("expiry", d.expiry() == null ? null : d.expiry().toString());
      leg.put("entryLtp", d.pick().candidate().ltp());
      leg.put("delta", d.pick().delta());
      leg.put("structuralStop", d.structuralStop());
    }
    ArrayNode checks = root.putArray("checks");
    for (ScalperConfluenceGate.RailCheck c : d.checks()) {
      ObjectNode n = checks.addObject();
      n.put("rail", c.rail());
      n.put("pass", c.pass());
      n.put("operand", c.operand());
      n.put("threshold", c.threshold());
      n.put("margin", c.margin());
      n.put("reason", c.reason());
      n.put("failPolicy", c.policy().name()); // P2: the rail's declared missing-data polarity
    }
    if (d.confluence() != null) {
      ConnectTheDotsScorer.Confluence conf = d.confluence();
      ObjectNode c = root.putObject("confluence");
      c.put("aggregate", conf.aggregate());
      c.put("threshold", d.compositeThreshold());
      c.put("bullish", conf.bullish());
      c.put("bearish", conf.bearish());
      c.put("vwapAligned", conf.vwapAligned());
      c.put("biasAligned", conf.biasAligned());
      c.put("standAside", conf.standAside());
      ArrayNode dots = c.putArray("dots");
      for (ConnectTheDotsScorer.DotScore ds : conf.dots()) {
        ObjectNode n = dots.addObject();
        n.put("dot", ds.dot());
        n.put("weight", ds.weight());
        n.put("supports", ds.supports());
        n.put("reason", ds.reason());
      }
    }
    if (d.context() != null) {
      ScalperGateContext ctx = d.context();
      ObjectNode c = root.putObject("context");
      c.put("underlying", ctx.underlying());
      c.put("signalIndex", ctx.signalIndex());
      ScalperGateContext.Chart ch = ctx.chart();
      ObjectNode chart = c.putObject("chart");
      chart.put("close", ch.close());
      chart.put("vwap", ch.vwap());
      chart.put("vwma20", ch.vwma20());
      chart.put("psar", ch.psar());
      chart.put("supertrendDir", ch.supertrendDir());
      chart.put("rsi14", ch.rsi14());
      chart.put("volume", ch.volume());
      chart.put("rsi5m", ch.rsi5m());
      chart.put("rsiDaily", ch.rsiDaily());
      ScalperGateContext.Oi oi = ctx.oi();
      ObjectNode on = c.putObject("oi");
      on.put("underlyingQuadrant", String.valueOf(oi.underlying()));
      on.put("futuresQuadrant", String.valueOf(oi.futures()));
      on.put("sentimentPct", oi.sentimentPct());
      on.put("trendingPeMinusCePct", oi.trendingPeMinusCePct());
      on.put("futuresBasis", oi.futuresBasis());
      on.put("ceOiDelta", oi.ceOiDelta());
      on.put("peOiDelta", oi.peOiDelta());
      on.put("callPutDeltaImbalancePct", oi.callPutDeltaImbalancePct());
      on.put("crossedThisWindow", oi.crossedThisWindow());
      on.put("gapWidening", oi.gapWidening());
      on.put("sentimentSlope", oi.sentimentSlope());
      on.put("spurtOiPct", oi.spurtOiPct());
      on.put("spurtPricePct", oi.spurtPricePct());
      on.put("oiDivergencePct", oi.oiDivergencePct());
      ScalperGateContext.Macro m = ctx.macro();
      ObjectNode mn = c.putObject("macro");
      mn.put("atmIv", m.atmIv());
      mn.put("ivRank", m.ivRank());
      mn.put("vixLevel", m.vixLevel());
      mn.put("vixRising", m.vixRising());
      mn.put("advances", m.advances());
      mn.put("declines", m.declines());
      mn.put("fiiLongPct", m.fiiLongPct());
      mn.put("ceIvAvg6", m.ceIvAvg6());
      mn.put("peIvAvg6", m.peIvAvg6());
      mn.put("constituentBias", m.constituentBias());
      mn.put("ceIvSlope", m.ceIvSlope());
      mn.put("peIvSlope", m.peIvSlope());
      mn.put("premiumSkewPct", m.premiumSkewPct());
      mn.put("dowUp", m.dowUp());
      mn.put("fiiBiasSign", m.fiiBiasSign());
    }
    return root.toString();
  }

  private void emit(
      Loaded strategy, String exchange, String tradingsymbol, String interval, String type,
      EngineCandle bar, SignalRepository.SignalRow anchor, String exitReason) {
    String side = "SELL".equals(anchor.side()) ? "BUY" : "SELL"; // the closing side
    OffsetDateTime generatedAt = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    // Insert + anchor transition commit atomically: a failure between them left the entry ACTIVE
    // next to a persisted EXIT — the next bar then emitted a duplicate EXIT for the same anchor.
    long id =
        tx.execute(
            status -> {
              long newId =
                  signals.insert(
                      strategy.versionId(), exchange, tradingsymbol, interval, type, side,
                      bar.close(), null, null, anchor.compositeScore(),
                      anchor.scoreBreakdown().toString(), generatedAt,
                      generatedAt.plusMinutes(signalTtlMinutes));
              signals.transition(anchor.id(), "EXPIRED"); // the entry resolved — the pair is closed
              return newId;
            });
    emitted.increment();
    publisher.publish(
        id, strategy.versionId(), strategy.name(), strategy.slug(), strategy.version(),
        strategy.checksum(), strategy.book(), exchange, tradingsymbol, interval, type, side,
        bar.close(), null, null, anchor.compositeScore(), anchor.scoreBreakdown(), generatedAt);
    // Close the anchor's paper position (a TAKEN entry has one open). The paper module listens
    // synchronously; a paper failure is logged there, never propagated into the eval loop.
    events.publishEvent(new SignalExited(anchor.id(), id, exitReason));
    log.info("EXIT signal #{} {} {}:{} at {} ({})", id, strategy.slug(), exchange, tradingsymbol,
        bar.close(), exitReason);
  }

  /** The 15:45 sweep: stale ACTIVE signals expire (C-2.14). */
  @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
  public void intradayExpirySweep() {
    int swept = signals.expireAllActive();
    if (swept > 0) {
      log.info("15:45 sweep expired {} stale ACTIVE signals", swept);
    }
  }

  /** Daily 08:40 IST: futures re-resolution (roll re-subscribe) + reload. */
  @Scheduled(cron = "0 40 8 * * MON-FRI", zone = "Asia/Kolkata")
  public void morningReload() {
    reloadRequested.set(true);
    evalExecutor.execute(this::drainReloadOnly);
  }

  private void drainReloadOnly() {
    if (reloadRequested.get()) {
      reload();
    }
  }

  /**
   * Safety net behind the push-based hot-swap: every 20s reconcile the loaded set against the
   * registry's published set and reload on drift. The strategy.changed event fires INSIDE the
   * publish transaction (so a reload it triggers can race the commit) and a Redis subscription may
   * not yet be established when the FIRST strategy is published on a fresh deployment — without
   * this, that first publish could never take effect. Cheap: one indexed query on a single-owner
   * registry, a reload only when the published-version set actually changed.
   */
  @Scheduled(fixedDelay = 20_000L, initialDelay = 20_000L)
  public void reconcilePublishedStrategies() {
    if (publishedSetDrifted()) {
      log.info("reconcile: published-strategy set changed in the registry — reloading");
      reloadRequested.set(true);
      evalExecutor.execute(this::drainReloadOnly);
    }
  }

  /**
   * The reconcile drift predicate: the registry's CURRENT published set differs from the set the last
   * reload was based on. Deliberately compared against the last-reload SNAPSHOT, NOT the LOADED subset
   * — the engine skips swing / non-rollable / empty-universe / failed strategies, so {@code loaded <
   * published} is the steady state, not drift; comparing against loaded would reload forever (fixed
   * #579). Package-visible so the regression test can assert convergence when a skipped (swing)
   * strategy is published.
   */
  boolean publishedSetDrifted() {
    return !publishedVersionSet().equals(lastReloadedPublishedSet);
  }

  private String publishedVersionSet() {
    return publishedVersionSetOf(registry.listAll());
  }

  /** The sorted, comma-joined published-version-id set of the enabled+published rows in {@code all}. */
  private static String publishedVersionSetOf(List<StrategyRepository.StrategyRow> all) {
    return all.stream()
        .filter(s -> s.enabled() && s.publishedVersionId() != null)
        .map(s -> s.publishedVersionId().toString())
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static boolean withinSessionWindow(StrategyDefinition definition, EngineCandle bar) {
    LocalTime barTime = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET).toLocalTime();
    StrategyDefinition.Session session = definition.session();
    if (session.windowFrom() != null && barTime.isBefore(LocalTime.parse(session.windowFrom()))) {
      return false;
    }
    if (session.windowTo() != null && !barTime.isBefore(LocalTime.parse(session.windowTo()))) {
      return false;
    }
    return session.squareOff() == null || barTime.isBefore(LocalTime.parse(session.squareOff()));
  }

  private static ExitEvaluator.Direction directionOf(StrategyDefinition definition) {
    return definition.direction() == StrategyDefinition.Direction.SHORT
        ? ExitEvaluator.Direction.SHORT
        : ExitEvaluator.Direction.LONG;
  }

  /**
   * The exit/stop direction for an OPEN scalper position. A long-premium scalper BUYS a CE on a bullish
   * (LONG-on-future) read and a PE on a bearish (SHORT-on-future) read — both legs are BUYs, but the
   * protective structural stop + the trailing/level exits are anchored on the INDEX FUTURE, so they must
   * use the HELD side (CE ⇒ LONG, PE ⇒ SHORT, from the persisted {@code scalper_detail}), NOT the
   * strategy's static {@code direction}. A {@code direction:both} bidirectional scalper (hero-zero /
   * btst / a future PE-mirror) would otherwise treat an open PE as LONG — stop on the wrong side, the
   * trailing read off the wrong extreme. Falls back to the definition direction for a non-scalper or a
   * sideless (neutral straddle) entry. Live-only path → engine goldens untouched (parity-safe).
   */
  private static ExitEvaluator.Direction scalperPositionDirection(
      Loaded strategy, SignalRepository.SignalRow activeEntry) {
    if (strategy.scalper() != null && activeEntry.scalperDetail() != null) {
      String heldSide = activeEntry.scalperDetail().path("side").asText("");
      if ("PE".equals(heldSide)) {
        return ExitEvaluator.Direction.SHORT;
      }
      if ("CE".equals(heldSide)) {
        return ExitEvaluator.Direction.LONG;
      }
    }
    return directionOf(strategy.definition());
  }

  /**
   * E9 D4: re-reads the OI confluence at the current bar and reports whether it now STRONGLY confirms
   * the opposite side to the one held — i.e. the read flipped against the open scalper position.
   * Live-only (the confluence gate never runs on the deterministic replay). The held side rides the
   * entry's {@code scalper_detail} side-channel; a neutral (straddle) entry has no side to flip.
   */
  private boolean confluenceFlipExit(
      Loaded strategy, BarValues bank, EngineSeries future, int index, EngineCandle bar,
      SignalRepository.SignalRow entry) {
    String heldSide = entry.scalperDetail().path("side").asText("");
    if (!"CE".equals(heldSide) && !"PE".equals(heldSide)) {
      return false;
    }
    OffsetDateTime istBar = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    Optional<ScalperConfluenceGate.Decision> now =
        scalperGate.get().evaluate(
            strategy.scalper(), bank, future, index, bar.bucketStart().toInstant(),
            istBar.toLocalTime(), istBar.toLocalDate());
    return now.isPresent()
        && !now.get().neutral()
        && ScalperGates.confluenceFlippedAgainst(heldSide, now.get().side().name());
  }

  /** A scalper structural stop fires when the bar touches the level: low ≤ stop (long), high ≥ stop (short). */
  private static boolean structuralStopHit(
      ExitEvaluator.Direction dir, EngineCandle bar, BigDecimal stop) {
    return dir == ExitEvaluator.Direction.LONG
        ? bar.low().compareTo(stop) <= 0
        : bar.high().compareTo(stop) >= 0;
  }

  private static BigDecimal levelFromRules(
      StrategyDefinition definition, BigDecimal entryPrice, String type) {
    for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
      if (!rule.type().equals(type) || !"premium_pct".equals(rule.params().get("basis"))) {
        continue;
      }
      Object value = rule.params().get("value");
      if (value == null) {
        continue;
      }
      BigDecimal pct = new BigDecimal(value.toString());
      BigDecimal distance =
          entryPrice.multiply(pct).divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
      return type.equals("stop_loss") ? entryPrice.subtract(distance) : entryPrice.add(distance);
    }
    return null;
  }

  /**
   * W3 PR-4: the level of a {@code stop_loss} rule with {@code basis: index_points} — entry minus the
   * point value for a long (stop below), plus it for a short (stop above) — or {@code null} when no
   * such rule is present. Package-private for a focused unit test.
   */
  static BigDecimal indexPointStopLevel(
      List<StrategyDefinition.ExitRuleSpec> rules, boolean shortDir, BigDecimal entryPrice) {
    for (StrategyDefinition.ExitRuleSpec rule : rules) {
      if (!"stop_loss".equals(rule.type()) || !"index_points".equals(rule.params().get("basis"))) {
        continue;
      }
      Object value = rule.params().get("value");
      if (value == null) {
        continue;
      }
      BigDecimal pts = new BigDecimal(value.toString());
      return shortDir ? entryPrice.add(pts) : entryPrice.subtract(pts);
    }
    return null;
  }

  /** PR-4 additive cap: the stop level closer to entry (the tighter of two) wins. */
  static BigDecimal closerToEntry(BigDecimal entryPrice, BigDecimal a, BigDecimal b) {
    return entryPrice.subtract(a).abs().compareTo(entryPrice.subtract(b).abs()) <= 0 ? a : b;
  }

  /** The coarse primaries the live engine can roll off the 1m stream (reload() enforces this). */
  static final Set<String> ROLLABLE_PRIMARIES = Set.of("1m", "3m", "5m", "15m", "1h");

  /**
   * Reconstructs the PRIMARY-series index of the bar an active entry was EVALUATED on — the exit
   * anchor every exit rule measures from (atrAtEntry, the favorable-extreme trail window start,
   * time-stop bar counting, initial risk). {@code generatedAt} is the persisted 1m TRIGGER bar
   * instant. For a 1m primary that trigger IS the evaluated bar, so the anchor is
   * {@code indexAtOrBefore(generatedAt)} unchanged. For a COARSE primary the trigger is the
   * boundary 1m bar that OPENS bucket k+1 while evaluation ran on the just-COMPLETED bucket k (the
   * series' last bar under the B1 in-progress filter) — anchoring at {@code generatedAt} directly
   * would resolve to k+1 once that bucket completes and appends: one bucket AFTER the evaluated
   * bar, diverging from the replay harness (which carries the evaluated index itself) and
   * flip-flopping on the intrabar path across the next boundary refresh. So the coarse anchor is
   * the bucket at or before {@code generatedAt − primaryDuration}: exactly k when buckets are
   * adjacent, and the nearest EARLIER bucket across session/holiday gaps ({@code indexAtOrBefore}
   * is a floor lookup by construction — the evaluated bar was the last bar at entry time, so its
   * bucket start is always ≤ that instant). A -1 miss (an entry older than the warmed window)
   * clamps to 0, matching the previous behavior.
   */
  static int entryAnchorIndex(EngineSeries primary, String primaryInterval, Instant generatedAt) {
    Instant evaluatedAt =
        "1m".equals(primaryInterval)
            ? generatedAt
            : generatedAt.minus(intervalDuration(primaryInterval));
    return Math.max(primary.indexAtOrBefore(evaluatedAt), 0);
  }

  static Duration intervalDuration(String interval) {
    return switch (interval) {
      case "3m" -> Duration.ofMinutes(3);
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      // Match the golden runner: an unknown primary must never silently mean "evaluate every 1m
      // bar". reload() refuses such strategies, so reaching this is a programming error.
      default ->
          throw new IllegalArgumentException("unsupported live primary interval: " + interval);
    };
  }

  /**
   * §3.2a: the distinct HIGHER timeframes declared on the SIGNAL future itself — a non-context
   * indicator ({@code instrument == null}) whose timeframe is neither the primary nor 1m (those are
   * already warmed by the universe loop). These series must be warmed at reload + refreshed at each
   * bucket boundary, else {@code IndicatorBank.build} throws "no series coverage" for e.g. a
   * {@code bias60m@1h} or {@code rsi@1d} and the strategy is silently disabled on the live path.
   */
  static Set<String> higherTimeframes(
      String primaryTimeframe, List<StrategyDefinition.IndicatorSpec> indicators) {
    Set<String> tfs = new LinkedHashSet<>();
    for (StrategyDefinition.IndicatorSpec spec : indicators) {
      if (spec.instrument() == null
          && !spec.timeframe().equals(primaryTimeframe)
          && !spec.timeframe().equals("1m")) {
        tfs.add(spec.timeframe());
      }
    }
    return tfs;
  }

  /** Loaded view for the IT + status surfaces. */
  public List<String> loadedSlugs() {
    return loaded.stream().map(Loaded::slug).toList();
  }

  /** slug → published version currently pinned (hot-swap assertions). */
  public Map<String, String> loadedVersions() {
    Map<String, String> versions = new LinkedHashMap<>();
    for (Loaded strategy : loaded) {
      versions.put(strategy.slug(), strategy.version());
    }
    return versions;
  }
}
