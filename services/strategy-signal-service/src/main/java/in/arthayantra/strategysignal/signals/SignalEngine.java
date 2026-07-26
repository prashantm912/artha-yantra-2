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
import in.arthayantra.strategyengine.eval.ScoreBreakdown;
import in.arthayantra.strategyengine.eval.ScoreBreakdownJson;
import in.arthayantra.strategyengine.eval.SeriesProvider;
import in.arthayantra.strategyengine.golden.TickwiseGoldenRunner;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.CanonicalJson;
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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
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
  // Canonical source: market-data's SessionStatusPublisher.STATUS_CHANNEL / STATUS_KEY. The CHANNEL
  // is edge-only (publish-on-change); the KEY is level-triggered (re-set every 5 min by
  // market-data's SessionHealthProbe) — start() reads the key so a CONNECTED published before this
  // engine subscribed cannot be missed forever (Redis pub/sub is fire-and-forget).
  private static final String KITE_STATUS_CHANNEL = "kite.status";
  private static final String KITE_STATUS_KEY = "kite:session:status";
  private static final String KITE_STATUS_CONNECTED = "CONNECTED";
  // The DELAY outlives the kite-rest breaker: it remains OPEN for 30s (market-data application.yml
  // wait-duration-in-open-state), so 35s guarantees every attempt gets a CLOSED-breaker call.
  // The BOUND is sized off measured faults instead. An attempt runs its reload SYNCHRONOUSLY and
  // only then schedules the next (see runKiteConnectedReloadAttempt), so with a failing-reload
  // duration D the chain issues its LAST attempt at (attempts-1) * (delay + D) and terminates one
  // reload later. It stays BOUNDED either way — an unbounded retry predicate reintroduces #579.
  //   * D ~= 37s (39 strategies x REST timeouts; the ~72s attempt2->attempt3 gap observed
  //     2026-07-17 minus the 35s delay) => last attempt at 7 * 72s ~= 8.4 min, chain ends ~9.0 min.
  //   * D -> 0 (breaker OPEN => the call is rejected immediately) => last attempt at 7 * 35s = 245s.
  // Only the D->0 figure is GUARANTEED, and at the old 3 attempts it was just 2 * 35s = 70s — SHORTER
  // than both measured cold-start faults (2026-07-16 ~73s, 2026-07-17 ~81s for market-data to serve
  // term-structure). Those drills recovered only because their failing reloads happened to be slow;
  // a fast-failing chain would have missed both. 8 attempts put the guaranteed floor at 245s ~= 3x
  // the ~81s worst case, with headroom for the TRUE fault (an expired token — market-data cannot warm
  // its lastGood cache at all until the owner logs in), which is slower still. Two data points is all
  // that exists; the floor, not the distribution, is what the bound is sized against.
  private static final int KITE_CONNECTED_RELOAD_MAX_ATTEMPTS = 8;
  private static final long KITE_CONNECTED_RELOAD_DELAY_MILLIS = 35_000L;

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

  private enum UniverseResolutionStatus {
    /** Resolved to at least one tradable instrument. */
    RESOLVED,
    /**
     * Resolved, with NOTHING to trade today — a legitimate STAND-ASIDE, not a fault. A
     * {@code futures_screener} whose movers screen picked nobody returns a present-but-empty list
     * on purpose ({@code FuturesUniverseResolver.resolveScreener}). Loading nothing IS the correct
     * outcome, so this is HEALTHY and must be INSTALLED: refusing it would leave the engine holding
     * — and trading — YESTERDAY's universe. (Whether an empty {@code contracts} array on an INDEX
     * ladder should instead be a FAULT is a different question, owned by chip task_f624fca7; that
     * classification lives in the resolver and is deliberately untouched here.)
     */
    RESOLVED_EMPTY,
    /** Resolution FAILED upstream (Kite/market-data) — RETRYABLE. */
    UNRESOLVED,
    /** A config/capability error — permanent, never retryable. */
    NOT_LIVE_RESOLVABLE
  }

  private record UniverseResolution(
      UniverseResolutionStatus status, List<StrategyDefinition.InstrumentRef> instruments) {}

  /**
   * The identity of one loaded entry — what the engine would LOSE if a reload dropped it. Counts
   * are not identity: {@code {A}} and {@code {B}} are both "1 loaded", but installing B over A
   * removes A from {@link #loaded}, and exit evaluation only ever walks {@code loaded} — so A's
   * open position would silently lose its exit evaluation for the session.
   *
   * <p>{@code book} rides along because it is derived from the identity row's tags rather than the
   * pinned version, so it can change without the versionId changing.
   */
  private record LoadedIdentity(
      UUID versionId, Set<StrategyDefinition.InstrumentRef> universe, String book) {}

  /**
   * What one {@link #reload()} COMPUTED — whether or not it was installed. Every way a reload can
   * fail RETRYABLY is counted EXPLICITLY here; nothing is inferred from the gap between "how many
   * could have loaded" and "how many did", because that gap cannot tell a strategy that correctly
   * loaded NOTHING (a screener standing aside) from one where everything blew up.
   *
   * @param loadedCount strategies actually loaded
   * @param unresolvedDrops strategies dropped on a FAILED (Kite-dependent) universe resolution
   * @param loadErrors strategies dropped by an exception thrown while loading — counted by neither
   *     of the other two, which is exactly how a totally dead engine used to report "0 loaded,
   *     0 unresolved" and be read as success
   */
  private record ReloadOutcome(
      int loadedCount,
      int unresolvedDrops,
      int loadErrors,
      long coverageGeneration,
      Map<String, StrategyCoverageSnapshot.Classification> coverageClassifications) {

    /**
     * The ONLY success signal: NOTHING failed in a way a retry could fix.
     *
     * <p>{@code loadedCount} is deliberately absent. {@code loaded > 0} is NOT health — a cold boot
     * legitimately loads a PARTIAL 32/39 — and {@code loaded == 0} is NOT sickness either, because
     * an all-swing registry, an all-not-live-resolvable one, and a screener with no movers today
     * all correctly load nothing. Health is about FAILURES, so only failures are consulted.
     *
     * <p>{@code unresolvedDrops == 0} alone was not enough: a drop is counted only for a resolution
     * FAILURE, so a reload where every strategy threw reported "0 loaded, 0 unresolved" and a
     * drops-only predicate logged "resolved every universe" over a dead engine — the 2026-07-15/16
     * outage's exact signature. Counting {@code loadErrors} closes that route at the source.
     */
    boolean healthy() {
      return unresolvedDrops == 0 && loadErrors == 0;
    }
  }

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
  // Bounded ASYNC writer (+ the id-coupled shadow-book open) so a DB stall can never park the sole
  // signal-eval thread (task_084d4d01, same #866 class as the risk-suppression writer below).
  private final RejectionWriter rejectionWriter;
  // PF-03 live-only: every ENTRY the per-book risk governor vetoed (which rail + would-be leg).
  // Bounded ASYNC writer so a DB stall can never park the sole signal-eval thread (#866 class).
  private final RiskSuppressionWriter riskSuppressions;
  // Live-only tuning record: every entry whose chart gates passed but whose A1 composite came in
  // under threshold (V044 composite_rejections). Bounded ASYNC writer so a DB stall can never park
  // the sole signal-eval thread (#866 class).
  private final CompositeRejectionWriter compositeRejections;
  // T15: durable reload ledger (strategy.engine_reloads, V046) — null in harnesses that construct
  // without it; the record call is skipped, never a substitute no-op bean.
  private final EngineReloadLedger reloadLedger;
  // Kill switch for the above. Default ON — the row is the whole point of the change — but flipping
  // ARTHA_SIGNALS_RECORD_COMPOSITE_REJECTIONS=false in .env stops the writes without an image
  // rebuild, which is the escape hatch if the added row rate (~192/session) ever bites.
  private final boolean recordCompositeRejections;

  /**
   * The verdict of ONE entry evaluation at a primary bar close (chip task_37ee83e0).
   *
   * <p>The engine used to record a no-entry ONLY when the §12.3 scalper confluence gate blocked a
   * chart-entry — i.e. only on bars whose CHART stage had already said yes. A chart-stage "no" left
   * NOTHING behind: no row, no log, no distinct metric. Four states therefore shared one DB
   * signature (zero rows in {@code strategy.signal_rejections}): the engine being dead, the chart
   * gate being closed, indicators warming, and the composite landing under threshold. That
   * ambiguity cost a false starvation alarm on 2026-07-17 — an 84-minute silence that was simply a
   * SuperTrend-DOWN leg (every published+enabled scalper — 63 of the 69 enabled strategies as of
   * 2026-07-17 — shares ONE composite: {@code rsi14} rsi_momentum w1 + {@code supertrend} direction
   * w1 at threshold 0.2 on one 3m NIFTY-future series, so ST DOWN + RSI &lt; 58 ⇒ composite &lt; 0.2
   * ⇒ every scalper goes silent together).
   *
   * <p>These ride a per-tag COUNTER, not a rejection row: a row per no-entry per strategy per 3m
   * bar would be ~63 writes every bar all session on the live eval thread — far too much write
   * volume for a boring "no". Counters are cheap and always-on.
   *
   * <p><b>THE INVARIANT: Σ(outcomes) == entry evaluations.</b> Every path out of {@link
   * #decideEntry} returns exactly one of these and the caller increments exactly once, so the sum
   * IS the evaluation count. This is load-bearing, not bookkeeping: if the sum could undercount,
   * "the engine performed no evaluations" would again be indistinguishable from "the engine
   * evaluated and fell through an uncounted path" — the very ambiguity this chip exists to close,
   * reproduced one level up. That is why {@code decideEntry} RETURNS an Outcome rather than
   * incrementing inline: a new silent {@code return;} is then a compile error, not a blind spot.
   */
  enum Outcome {
    /**
     * Gates passed AND composite &gt;= threshold. PROVISIONAL for a scalper — the confluence stage
     * runs next and can still downgrade the bar to {@link #CONFLUENCE_BLOCKED}.
     */
    FIRED("fired"),
    /**
     * The chart stage said yes and the confluence gate then blocked it. This is the ONLY outcome
     * the engine has ever recorded — it is the one that writes a {@code signal_rejections} row.
     */
    CONFLUENCE_BLOCKED("confluence-blocked"),
    /**
     * A scalper strategy is loaded but the §12.3 confluence seam is absent, so it can never fire
     * (fail-closed). A misconfiguration, not a market "no" — it also logs at WARN.
     */
    CONFLUENCE_GATE_ABSENT("confluence-gate-absent"),
    /**
     * The §12.7 five-account discipline (5 losses freeze / 5 wins bank the day) is holding scalper
     * entries for the rest of the session. A deliberate freeze — the chart said yes and the
     * confluence was never consulted.
     */
    DISCIPLINE_PAUSED("discipline-paused"),
    /** Every chart gate passed; the A1 composite came in under entry_rules.scoring.threshold. */
    COMPOSITE_BELOW_THRESHOLD("composite-below-threshold"),
    /** A chart gate rule said no. The composite is not consulted once a gate fails. */
    CHART_GATE_FAILED("chart-gate-failed"),
    /**
     * A REQUIRED scoring participant was null, so the bar could not be scored at all
     * ({@code CompositeScorer} returns empty rather than scoring a silent zero). The one outcome
     * that indicates a FAULT rather than a normal market "no" — see {@link #WARMUP_GRACE_UNTIL}.
     */
    UNSCOREABLE_INDICATORS_WARMING("unscoreable-indicators-warming");

    private final String tag;

    Outcome(String tag) {
      this.tag = tag;
    }

    /**
     * The stable wire tag — the {@code outcome} Micrometer tag value AND the {@code outcome} column
     * of {@code strategy.signal_eval_outcomes}. Persisted history is keyed by it, so RENAMING a tag
     * silently splits a series across the rename; add a new constant instead.
     */
    String tag() {
      return tag;
    }
  }

  /**
   * 10:00 IST — 45 minutes past the 09:15 open, after which {@link
   * Outcome#UNSCOREABLE_INDICATORS_WARMING} is logged at WARN. {@link LiveSeriesStore#ensureWarm}
   * back-fills every series from REST at load (4 days of 1m/3m bars, 10 of 5m/15m), so a required
   * indicator is normally ready at the session's FIRST live bar; the only way to still be
   * unscoreable is a series that started COLD because that warm fetch returned nothing — which is
   * itself the fault worth reporting. The cutoff merely keeps a legitimately cold-started series
   * quiet while it fills. Every OTHER no-entry outcome is a normal "no" and stays counter-only.
   */
  private static final LocalTime WARMUP_GRACE_UNTIL = LocalTime.of(10, 0);

  /**
   * Session date of the last unscoreable WARN per SERIES ({@code exchange:tradingsymbol:interval}).
   * The fault is per-series, not per-strategy: all published+enabled SCALPERS resolve to ONE 3m
   * NIFTY-future series, so an unwarmed series would emit one identical WARN per scalper every bar
   * that say nothing the first one does not. Keyed by series and stamped with the session date, so
   * it self-expires each day and stays bounded by the number of live series.
   */
  private final java.util.Map<String, LocalDate> unscoreableWarnedFor =
      new java.util.concurrent.ConcurrentHashMap<>();

  // candles.1m.* are NEVER conflated (A.7.2 bus contract — every closed bar matters). A FIFO
  // queue preserves each distinct bar in arrival order; the single-threaded evalExecutor drains it
  // in order. (A latest-value-wins map would drop a bar under a burst, leaving a permanent series
  // gap and potentially skipping a stop-loss EXIT.)
  private record PendingBar(String symbolKey, EngineCandle candle, long receivedAtMs) {}

  private final java.util.Queue<PendingBar> pending =
      new java.util.concurrent.ConcurrentLinkedQueue<>();
  // The causal receive stamp for the bar currently running on the eval thread. A ThreadLocal keeps
  // scheduled BTST/pre-close emissions (which have no Redis receipt) at zero even if they overlap a
  // live evaluation on the single-thread executor.
  private final ThreadLocal<Long> currentBarReceivedAtMs = ThreadLocal.withInitial(() -> 0L);
  private final AtomicBoolean drainScheduled = new AtomicBoolean();
  private final AtomicBoolean reloadRequested = new AtomicBoolean(true);
  private final AtomicBoolean kiteConnectedReloadInFlight = new AtomicBoolean();
  private final AtomicBoolean stopped = new AtomicBoolean();
  // How many strategies the LAST reload dropped because their universe would not resolve (the
  // Kite-dependent skip only — swing/non-rollable/load-failure skips are legitimate and excluded).
  // >0 means that reload was DEGRADED, which is what the CONNECTED retry chain converges on:
  // "loaded is non-empty" is NOT a recovery signal, because the drop is PER strategy and a breaker
  // that re-opens mid-reload can leave a partial set loaded (1 of 39) that looks like success.
  private volatile int lastReloadUnresolvedDrops;
  // Package-visible so tests can shorten it; ONE source of truth (no @Value default to drift from).
  long kiteConnectedReloadDelayMillis = KITE_CONNECTED_RELOAD_DELAY_MILLIS;
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
  private final ScheduledExecutorService kiteConnectedReloadScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "kite-connected-reload");
            t.setDaemon(true);
            return t;
          });

  private volatile List<Loaded> loaded = List.of();

  // T9: a total load-coverage snapshot, published only after a reload reaches a terminal
  // classification. The IN_FLIGHT marker is published before the reload body starts, so an abort
  // cannot be mistaken for the last healthy snapshot still in memory.
  private volatile StrategyCoverageSnapshot coverageSnapshot;

  // The generation the CONNECTED retry chain owns, held for the chain's whole life so every attempt
  // republishes the SAME marker instead of resetting the SNAPSHOT_MISSING grace clock. Written only
  // from the serial evalExecutor chain; cleared when the chain terminalizes.
  private volatile Long connectedChainGeneration;

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

  // Gauge sentinels ONLY — never read by the canaries. The stamps above are SEEDED at construction
  // (boot grace), so age alone cannot tell "a bar just arrived" from "no bar has EVER arrived": both
  // read ~0 and both would satisfy a freshness threshold. These make that distinction observable.
  private volatile boolean barEverReceived;
  private volatile boolean barEverEvaluated;
  /**
   * The registry's published+enabled version-id set AS OF the last {@link #reload()} — the reconcile
   * baseline. Comparing the CURRENT published set against this (not against the LOADED subset) is what
   * lets the 20s reconcile detect a genuine registry change while NOT looping forever on strategies
   * the engine deliberately skips at load (swing, non-rollable-primary, empty-universe, load-error).
   */
  private volatile String lastReloadedPublishedSet = "";
  private volatile RedisMessageListenerContainer container;

  private final Timer evalTimer;
  private final Timer barToEmitTimer;
  private final Counter emitted;
  private final Counter evalFailures;
  // One counter per Outcome — see the enum. evalTimer wraps onClosedBar INCLUDING its early
  // returns, so ay_signal_eval_duration_seconds_count can never tell these outcomes apart; that is
  // part of why the chart-stage blind spot hid for so long.
  private final java.util.Map<Outcome, Counter> outcomeCounters = new java.util.EnumMap<>(Outcome.class);
  // Identifies THIS generation of the counters above. Born with them and never reassigned, so a
  // restart necessarily yields both zeroed counters and a fresh epoch — the invariant that lets
  // SignalEvalOutcomeRollupJob keep its delta checkpoint in the DB instead of in memory (V045).
  private final java.util.UUID counterEpoch = java.util.UUID.randomUUID();
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
      RejectionWriter rejectionWriter,
      RiskSuppressionWriter riskSuppressions,
      CompositeRejectionWriter compositeRejections,
      java.util.Optional<EngineReloadLedger> reloadLedger,
      org.springframework.transaction.PlatformTransactionManager transactionManager,
      @Value("${artha.signals.ttl-minutes:60}") int signalTtlMinutes,
      @Value("${artha.signals.record-composite-rejections:true}") boolean recordCompositeRejections) {
    this.compositeRejections = compositeRejections;
    this.reloadLedger = reloadLedger.orElse(null);
    this.recordCompositeRejections = recordCompositeRejections;
    this.registry = registry;
    this.signals = signals;
    this.rejectionWriter = rejectionWriter;
    this.riskSuppressions = riskSuppressions;
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
    this.barToEmitTimer =
        Timer.builder("ay_signal_bar_to_emit_seconds")
            .publishPercentiles(0.5, 0.95)
            .register(meterRegistry);
    this.emitted = meterRegistry.counter("ay_signals_emitted_total");
    this.evalFailures = meterRegistry.counter("ay_signal_eval_failures_total");
    // ---- Engine-liveness read surface (chip task_0bed1621) --------------------------------------
    // AGE IN SECONDS, not the raw epoch stamp: an age is directly comparable to a threshold by an
    // operator, a scrape or a dashboard, and it needs no clock-skew reconciliation between the
    // container and the reader.
    //
    // These two are the ONLY unconfounded engine-liveness oracles, and until now NOTHING outside the
    // process could read them. `lastBarReceivedAtMs` is stamped as the FIRST line of
    // onCandleMessage, before any universe / session-window / position / loaded logic, so it is
    // direction-, window- and position-independent; `lastBarEvaluatedAtMs` is its evaluation-side
    // twin. Everything else an operator can reach admits a FALSE PASS: rejections are
    // direction-dependent (recordRejection runs only past the chart gate), `strategy.signals` mixes
    // the swing BATCH engine, `subscriber_health_events` is write-only fail-soft so its emptiness
    // proves nothing, and `signal_eval_outcomes` freshness only proves the ROLLUP thread is alive
    // because that job runs on its own scheduler and never writes from signal-eval.
    //
    // READ SURFACE ONLY — deliberately no alarm here. SignalStarvationCanary was retired on
    // 2026-07-26 for keying on a confounded signal; any new detector is a separate owner decision.
    Gauge.builder(
            "ay_signal_bar_received_age_seconds",
            this,
            e -> ageSeconds(e.barEverReceived, e.lastBarReceivedAtMs))
        .description("Seconds since the engine last RECEIVED a closed bar; NEGATIVE = not a valid age")
        .register(meterRegistry);
    Gauge.builder(
            "ay_signal_bar_evaluated_age_seconds",
            this,
            e -> ageSeconds(e.barEverEvaluated, e.lastBarEvaluatedAtMs))
        .description("Seconds since the engine last EVALUATED a closed bar; NEGATIVE = not a valid age")
        .register(meterRegistry);
    // Pre-register EVERY tag at boot so an outcome that has not happened yet still scrapes as 0.
    // Lazily created series would reintroduce exactly the ambiguity this closes: a MISSING
    // outcome="fired" series would again be indistinguishable from an engine that never evaluated.
    for (Outcome outcome : Outcome.values()) {
      outcomeCounters.put(
          outcome,
          Counter.builder("ay_signal_eval_outcome_total")
              .description("Entry evaluations at a primary bar close, by outcome")
              .tag("outcome", outcome.tag)
              .register(meterRegistry));
    }
    this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  }

  /** Boots subscriptions once the app is ready. */
  @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public synchronized void start() {
    ReloadOutcome boot = reload(false);
    // reload() -> resubscribe() has now registered the kite.status listener. The CHANNEL is edge-only
    // (SessionStatusPublisher publishes on CHANGE) and Redis pub/sub is fire-and-forget, so a
    // CONNECTED published while this engine was still booting is lost FOREVER — and no further
    // CONNECTED ever comes, because the state is already CONNECTED. That would strand the engine at
    // 0 strategies for the session: exactly the 2026-07-16 outage this fix exists to prevent.
    // The KEY is level-triggered, so read it once here to close that boot race.
    // Armed off the boot outcome's explicit retryable-failure state, NOT `drops > 0`: a boot where
    // every strategy threw yields 0 loaded / 0 unresolved, so a drops-only gate would never arm the
    // chain, the reconcile would see no registry drift, and the engine would stay dead ALL SESSION
    // — the 2026-07-16 shape, straight through the gate that exists to prevent it.
    if (boot != null && !boot.healthy() && KITE_STATUS_CONNECTED.equals(readKiteSessionStatus())) {
      log.info(
          "boot: kite session already CONNECTED and the load had retryable failures ({} unresolved,"
              + " {} load errors) — reloading",
          boot.unresolvedDrops(), boot.loadErrors());
      requestKiteConnectedReload();
    }
  }

  /** The level-triggered {@code kite:session:status} key; null when absent or Redis is unreachable. */
  private String readKiteSessionStatus() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      byte[] value =
          connection.stringCommands().get(KITE_STATUS_KEY.getBytes(StandardCharsets.UTF_8));
      return value == null ? null : new String(value, StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      log.warn("could not read {}: {}", KITE_STATUS_KEY, e.toString());
      return null;
    }
  }

  @EventListener(ContextClosedEvent.class)
  void stop() {
    stopped.set(true);
    kiteConnectedReloadInFlight.set(false);
    kiteConnectedReloadScheduler.shutdownNow();
    evalExecutor.shutdownNow();
    recoveryExecutor.shutdownNow();
    synchronized (this) {
      if (container != null) {
        container.stop();
        container = null;
      }
    }
  }

  /** (Re)loads published+enabled strategies and rebuilds subscriptions. */
  public synchronized void reload() {
    reload(false);
  }

  /**
   * (Re)loads published+enabled strategies and rebuilds subscriptions, reporting what it computed.
   *
   * @param keepBest true ⇒ carry the last-good entry forward for each strategy whose OWN load
   *     failed retryably, instead of dropping it (see {@link #retainLastGood}). ONLY the bounded
   *     CONNECTED retry chain sets this: its attempts all read the SAME registry state, so a
   *     per-strategy failure across them is upstream damage, never a registry edit. Every other
   *     path (hot-swap, the 08:40 roll cron, the 20s reconcile) installs unconditionally, so a
   *     stale contract can never survive a roll.
   * @return what this reload COMPUTED (installed or not), or null when the engine is stopped
   */
  private synchronized ReloadOutcome reload(boolean keepBest) {
    return reload(keepBest, true);
  }

  /**
   * (Re)loads published+enabled strategies with explicit coverage terminalization.
   *
   * <p>The CONNECTED retry chain keeps each completed unsuccessful attempt {@code IN_FLIGHT}; it
   * owns the final terminalization after the chain either becomes healthy or exhausts its attempts.
   * A thrown attempt remains {@code IN_FLIGHT} as an aborted reload. Direct reload callers retain
   * the historical immediate terminalization behavior.
   */
  private ReloadOutcome reload(boolean keepBest, boolean terminalizeCoverage) {
    return reload(keepBest, terminalizeCoverage, null);
  }

  /** As above; {@code chainGeneration} makes a retry REUSE its chain's generation + grace clock. */
  private synchronized ReloadOutcome reload(
      boolean keepBest, boolean terminalizeCoverage, Long chainGeneration) {
    if (stopped.get()) {
      return null;
    }
    long coverageGeneration = beginCoverageReload(chainGeneration);
    reloadRequested.set(false);
    List<Loaded> fresh = new ArrayList<>();
    int unresolvedDrops = 0;
    int loadErrors = 0;
    int retained = 0;
    List<StrategyRepository.StrategyRow> all = registry.listAll();
    Map<String, StrategyCoverageSnapshot.Classification> classifications = new LinkedHashMap<>();
    for (StrategyRepository.StrategyRow strategy : all) {
      if (!strategy.enabled() || strategy.publishedVersionId() == null) {
        continue;
      }
      Optional<StrategyRepository.VersionRow> versionRow =
          registry.findVersionById(strategy.publishedVersionId());
      if (versionRow.isEmpty()) {
        classifications.put(
            strategy.slug(), StrategyCoverageSnapshot.Classification.MISSING_VERSION_ROW);
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
        // §0B hard-stop rule (hardened, T21 #990 round-3): a scalper without an ENGINE-fireable
        // bounding exit — a time_stop or an index-side stop_loss — could ride an unbounded losing
        // option. A premium_pct stop does NOT count: it is enforced only by the paper bracket path,
        // which does not run when a signal is not taken into paper. Refuse to load it rather than
        // emit signals it can never safely exit.
        if (scalper != null && !ScalperRisk.hasBoundingExit(definition.exitRules())) {
          log.warn(
              "scalper {} has no engine-fireable bounding exit (time_stop / index-side stop_loss)"
                  + " — not loaded (§0B hard-SL rule)",
              strategy.slug());
          classifications.put(
              strategy.slug(), StrategyCoverageSnapshot.Classification.NO_BOUNDING_EXIT);
          continue;
        }
        // Phase-9: swing strategies (session.style=swing, 1d primary) are driven by the daily
        // SwingBatchEngine (per family), NOT the tick loop — their equities do not tick. Skip
        // them here cleanly (not an error) so the batch owns them and the ROLLABLE check below never
        // logs a spurious "not live-rollable" warning for a strategy that is working as designed.
        if ("swing".equals(definition.session().style())) {
          classifications.put(
              strategy.slug(), StrategyCoverageSnapshot.Classification.NOT_APPLICABLE_SWING);
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
          classifications.put(
              strategy.slug(), StrategyCoverageSnapshot.Classification.NOT_ROLLABLE_PRIMARY);
          continue;
        }
        UniverseResolution resolution = resolveUniverse(config);
        if (resolution.status() == UniverseResolutionStatus.UNRESOLVED) {
          unresolvedDrops++;
          retained += retainLastGood(fresh, strategy.id(), keepBest);
          classifications.put(
              strategy.slug(), StrategyCoverageSnapshot.Classification.UNRESOLVED);
          log.warn("strategy {} has an unresolved universe — not loaded", strategy.slug());
          continue;
        }
        if (resolution.status() == UniverseResolutionStatus.NOT_LIVE_RESOLVABLE) {
          // NOT counted: a config/capability error is not an upstream fault, and retrying it is
          // pointless (it would burn the CONNECTED chain to a permanent false DEGRADED). But it
          // must never be SILENT — name the slug, or a published-and-enabled strategy vanishes
          // with the engine still reporting "0 dropped on an unresolved universe".
          log.warn(
              "strategy {} universe mode is not live-resolvable — not loaded", strategy.slug());
          classifications.put(
              strategy.slug(), StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE);
          continue;
        }
        if (resolution.status() == UniverseResolutionStatus.RESOLVED_EMPTY) {
          // NOT a failure: the universe resolved and there is genuinely nothing to trade today (a
          // screener that picked no movers). Loading nothing is the CORRECT outcome and this result
          // must still be installed — standing aside is the whole point. Never counted, so it can
          // neither block the retry chain from completing nor make the engine look degraded.
          log.info(
              "strategy {} resolves to an empty universe — standing aside today", strategy.slug());
          classifications.put(
              strategy.slug(), StrategyCoverageSnapshot.Classification.RESOLVED_EMPTY);
          continue;
        }
        List<StrategyDefinition.InstrumentRef> universe = resolution.instruments();
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
        classifications.put(strategy.slug(), StrategyCoverageSnapshot.Classification.RESOLVED);
      } catch (RuntimeException e) {
        // RETRYABLE by construction: this catch spans the market-data-dependent work (universe
        // resolution, series warm-up), so a transient upstream fault lands here — and a boot where
        // EVERY strategy lands here is the 0-loaded/0-unresolved outage shape. A permanent config
        // error also lands here and will burn the chain to a bounded DEGRADED; that is the correct
        // trade, because the alternative is a silently dead engine.
        loadErrors++;
        retained += retainLastGood(fresh, strategy.id(), keepBest);
        classifications.put(strategy.slug(), StrategyCoverageSnapshot.Classification.LOAD_ERROR);
        log.error("strategy {} failed to load — skipped: {}", strategy.slug(), e.getMessage());
      }
    }
    if (retained > 0) {
      log.warn(
          "KEEP_BEST_RETAINED_LAST_GOOD: {} strategies failed this retry ({} unresolved, {} load "
              + "errors) and kept their last-good entry instead of being dropped — a retry must "
              + "never leave the engine holding less than it already did",
          retained, unresolvedDrops, loadErrors);
    }
    ReloadOutcome outcome =
        new ReloadOutcome(
            fresh.size(), unresolvedDrops, loadErrors, coverageGeneration, classifications);
    Set<LoadedIdentity> freshIdentities = identitiesOf(fresh);
    Set<LoadedIdentity> installedIdentities = identitiesOf(loaded);

    // Structurally identical to what is already installed ⇒ nothing to install. Reassigning would
    // be a no-op, but bankCache.clear() would NOT: it cold-starts every ta4j indicator, which then
    // recomputes recursively from bar 0 in BigDecimal math on the single eval thread (the D17/P1-12
    // lesson). A persistent DEGRADED state now drives up to KITE_CONNECTED_RELOAD_MAX_ATTEMPTS
    // reloads per CONNECTED, so re-clearing warm banks each time is the thrash that constrains this
    // whole design. `container != null` keeps BOOT on the install path — it MUST resubscribe, since
    // that is what registers the kite.status listener the retry chain depends on. Telemetry still
    // refreshes: the published-set snapshot must stay current or the 20s reconcile would see
    // phantom drift. (The cheap half of chip task_f10a03; its broader unconditional-clear question
    // is untouched.)
    if (container != null && freshIdentities.equals(installedIdentities)) {
      this.lastReloadUnresolvedDrops = unresolvedDrops;
      this.lastReloadedPublishedSet = publishedVersionSetOf(all);
      log.info(
          "signal engine reload unchanged ({} loaded, {} unresolved, {} load errors) — indicator "
              + "banks and subscriptions retained",
          fresh.size(), unresolvedDrops, loadErrors);
      if (reloadLedger != null) {
        reloadLedger.record(fresh.size(), unresolvedDrops, loadErrors, false);
      }
      if (terminalizeCoverage) {
        completeCoverageReload(coverageGeneration, classifications);
      }
      return outcome;
    }
    bankCache.clear(); // definitions/universes may have changed — banks rebuild on next bar (P1-12)
    this.loaded = List.copyOf(fresh);
    this.lastReloadUnresolvedDrops = unresolvedDrops;
    // Snapshot the published set THIS reload was based on (from the same registry read), so the 20s
    // reconcile compares registry-vs-registry and converges even though `loaded` is a subset.
    this.lastReloadedPublishedSet = publishedVersionSetOf(all);
    resubscribe();
    log.info(
        "signal engine loaded {} published strategies ({} dropped on an unresolved universe, {} "
            + "failed to load)",
        fresh.size(), unresolvedDrops, loadErrors);
    // T15: the line above is the evidence every post-close deploy used to destroy — persist it.
    if (reloadLedger != null) {
      reloadLedger.record(fresh.size(), unresolvedDrops, loadErrors, true);
    }
    if (terminalizeCoverage) {
      completeCoverageReload(coverageGeneration, classifications);
    }
    return outcome;
  }

  /** Current T9 snapshot; volatile publication makes the reload thread the sole snapshot writer. */
  public StrategyCoverageSnapshot strategyCoverageSnapshot() {
    return coverageSnapshot;
  }

  private long beginCoverageReload() {
    return beginCoverageReload(null);
  }

  /**
   * Publishes the pre-body IN_FLIGHT marker and returns the generation this reload owns.
   *
   * <p><b>A retry chain must REUSE its generation, not mint a fresh one per attempt.</b> Each call
   * allocates a generation AND stamps {@code reloadTimestamp} with the current instant, and
   * {@code StrategyCoverageWatchdog.sweepMissing()} measures the SNAPSHOT_MISSING grace from that
   * stamp. So a chain retrying every ~35 s used to reset its own grace clock on every attempt: the
   * marker never aged past the 180 s grace, and a chain that was logically incomplete for its entire
   * ~245 s window — or ~9 minutes with slower production attempts — stayed invisible to predicate B.
   * That is the precise blindness predicate B exists to prevent. Passing the chain's existing
   * generation republishes the SAME marker with its ORIGINAL timestamp, so the grace clock runs from
   * when the chain actually started. (Cross-vendor review Major, 2026-07-26.)
   */
  private long beginCoverageReload(Long chainGeneration) {
    StrategyCoverageSnapshot current = coverageSnapshot;
    long completedGeneration = current == null ? 0L : current.completedGeneration();
    if (chainGeneration != null
        && current != null
        && current.requestedGeneration() == chainGeneration) {
      // Same chain, later attempt: republish unchanged so reloadTimestamp keeps the chain start time.
      coverageSnapshot =
          StrategyCoverageSnapshot.inFlight(
              chainGeneration, completedGeneration, current.reloadTimestamp());
      return chainGeneration;
    }
    long nextGeneration = current == null ? 1L : current.requestedGeneration() + 1L;
    coverageSnapshot =
        StrategyCoverageSnapshot.inFlight(
            nextGeneration, completedGeneration, OffsetDateTime.ofInstant(clock.instant(), Ist.ZONE));
    return nextGeneration;
  }

  private void completeCoverageReload(
      long generation, Map<String, StrategyCoverageSnapshot.Classification> classifications) {
    boolean abnormal = classifications.values().stream().anyMatch(
        StrategyCoverageSnapshot.Classification::abnormal);
    coverageSnapshot =
        new StrategyCoverageSnapshot(
            generation,
            generation,
            generation,
            OffsetDateTime.ofInstant(clock.instant(), Ist.ZONE),
            abnormal
                ? StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL
                : StrategyCoverageSnapshot.TerminalState.HEALTHY,
            classifications);
  }

  /** The {@link LoadedIdentity} of every entry — what the engine would LOSE by not installing. */
  private static Set<LoadedIdentity> identitiesOf(List<Loaded> entries) {
    Set<LoadedIdentity> out = new java.util.HashSet<>();
    for (Loaded entry : entries) {
      out.add(new LoadedIdentity(entry.versionId(), Set.copyOf(entry.universe()), entry.book()));
    }
    return out;
  }

  /**
   * Carries the last-good entry forward for each strategy whose OWN resolution/load failed this
   * retry, and returns how many were retained. The retry chain's "ends worse than no retry at all"
   * hazard: the terminal state used to be unconditionally the last attempt's result, so a
   * market-data regression across the retry window could take a live 19-of-39 engine to 0 for the
   * session — where simply not retrying would have kept 19.
   *
   * <p>Reconciled PER STRATEGY, because a fault is per strategy. Suppressing the whole reload
   * instead (the previous shape) meant one broken strategy could veto every OTHER strategy's
   * update: a screener that legitimately resolved to NOTHING would keep its stale universe loaded
   * — and {@code onClosedBar} walks {@code loaded}, so it would go on trading yesterday's movers
   * until an unrelated fault cleared. Another strategy's fault is not evidence about this one.
   *
   * <p>Three outcomes, and the distinction between the last two is the entire safety argument:
   *
   * <ul>
   *   <li>Loaded ⇒ its new state installs.
   *   <li>Own resolution/load FAILED ⇒ retain its last-good entry (nothing is lost to a fault).
   *   <li>Anything else — genuinely absent from the registry, disabled, unpublished, swing,
   *       not-live-resolvable, or a {@code RESOLVED_EMPTY} STAND-ASIDE ⇒ DROPPED. None of these
   *       failed; they are the registry's truth and must land immediately. This is why per-strategy
   *       reconciliation never degenerates into a permanent union: only a genuine FAILURE retains,
   *       and failure is recorded when it happens rather than inferred from a set difference.
   * </ul>
   *
   * <p>Chain-only ({@code keepBest}). Every other path — hot-swap, the 08:40 roll cron, the 20s
   * reconcile — installs unconditionally, so a stale contract can never survive a roll.
   *
   * <p><b>Called AT the failing strategy's position in the registry walk, and that is load-bearing,
   * not tidiness.</b> {@code loaded} order IS evaluation order ({@code onClosedBar} iterates it),
   * and strategies sharing a book are NOT independent: they compete for one
   * {@code RiskService} MAX_OPEN slot per book, so whichever evaluates first takes it. Appending
   * retained entries after the successes instead would make an {@code {A,B}} registry trade B after
   * a retry but A after a clean reload — the same registry and the same market producing a
   * different trade because an unrelated strategy happened to fail. Retaining in place keeps the
   * reconciled order byte-for-byte what a clean reload of the same registry would have produced.
   */
  private int retainLastGood(List<Loaded> fresh, UUID strategyId, boolean keepBest) {
    if (!keepBest) {
      return 0;
    }
    for (Loaded previous : loaded) {
      // The caller has already `continue`d past fresh.add for this strategy, so no duplicate.
      if (previous.strategyId().equals(strategyId)) {
        fresh.add(previous);
        return 1;
      }
    }
    return 0; // nothing to retain — it was not loaded before either
  }

  private UniverseResolution resolveUniverse(JsonNode config) {
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
        yield resolvedUniverse(instruments);
      }
      case "futures_of_underlying" ->
          // A7/A11: live trades the ACTUAL front/next contract; roll re-subscribe is the
          // daily re-resolution below
          fromResolver(
              futuresResolver.resolve(
                  universe.path("underlying").path("exchange").asText(),
                  universe.path("underlying").path("tradingsymbol").asText(),
                  universe.path("futures").path("contract").asText("front_month"),
                  universe.path("futures").path("roll_days_before_expiry").asInt(1)));
      case "options_of_underlying" -> {
        // Phase 3 / Model A: the scalper EVALUATES + CHARTS on the index FRONT FUTURE (it carries the
        // volume the §0B VWAP/VWMA gates need); the option to TRADE is picked at signal time by the
        // confluence seam. 2c decoupling: a SENSEX variant signals on the NIFTY future, so the signal
        // future is resolved from the SIGNAL index (universe.signal_underlying mapped to its index),
        // not the option-root underlying. Absent signal_underlying ⇒ the underlying (unchanged).
        ScalperConfig.IndexRef sig = ScalperConfig.signalIndex(universe);
        yield fromResolver(
            futuresResolver.resolve(
                sig.exchange(),
                sig.tradingsymbol(),
                universe.path("futures").path("contract").asText("front_month"),
                universe.path("futures").path("roll_days_before_expiry").asInt(2)));
      }
      case "futures_screener" ->
          // E1 §3.3: the dynamic Market-Movers stock-future universe — re-screened each reload
          // (08:40 + hot-swap), each picked mover mapped to its front contract + auto-subscribed.
          fromResolver(
              futuresResolver.resolveScreener(
                  universe.path("side").asText("long"),
                  universe.path("max_picks").asInt(5),
                  universe.path("source").asText("captured")));
      default -> {
        // This schema-supported mode is not live-resolvable yet — defensive
        log.warn("universe mode '{}' is not live-resolvable yet", mode);
        yield new UniverseResolution(UniverseResolutionStatus.NOT_LIVE_RESOLVABLE, List.of());
      }
    };
  }

  /**
   * Maps the resolver's Optional to the resolution model: absent = FAILED, present = resolved, and
   * present-but-EMPTY = a legitimate stand-aside. Reading the resolver's own contract only — how it
   * DECIDES that an empty result is genuine rather than a fault stays entirely its business.
   */
  private static UniverseResolution fromResolver(
      Optional<List<StrategyDefinition.InstrumentRef>> resolved) {
    if (resolved == null) {
      return new UniverseResolution(UniverseResolutionStatus.UNRESOLVED, List.of());
    }
    return resolved
        .map(SignalEngine::resolvedUniverse)
        .orElseGet(() -> new UniverseResolution(UniverseResolutionStatus.UNRESOLVED, List.of()));
  }

  private static UniverseResolution resolvedUniverse(
      List<StrategyDefinition.InstrumentRef> instruments) {
    return new UniverseResolution(
        instruments.isEmpty()
            ? UniverseResolutionStatus.RESOLVED_EMPTY
            : UniverseResolutionStatus.RESOLVED,
        instruments);
  }

  private synchronized void resubscribe() {
    if (stopped.get()) {
      return;
    }
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
    fresh.addMessageListener(
        (message, pattern) -> {
          if (KITE_STATUS_CONNECTED.equals(new String(message.getBody(), StandardCharsets.UTF_8))) {
            requestKiteConnectedReload();
          }
        },
        new ChannelTopic(KITE_STATUS_CHANNEL));
    fresh.afterPropertiesSet();
    fresh.start();
    if (stopped.get()) {
      fresh.stop();
      return;
    }
    RedisMessageListenerContainer old = this.container;
    this.container = fresh;
    if (old != null) {
      old.stop();
    }
    log.info("subscribed {} candle channels (universes + context series only)", channels.size());
  }

  /** Wall-clock millis of the last candle message received — the subscriber-liveness heartbeat. */
  /**
   * Age of a heartbeat stamp in seconds, for the liveness gauges (chip task_0bed1621).
   *
   * <p><b>Any NEGATIVE value means "this is not a valid age" and must never be read as healthy.</b>
   * Two distinct causes, deliberately given distinct values so an operator can tell them apart:
   *
   * <ul>
   *   <li>{@code -1} — no bar has EVER been received/evaluated on this boot. The stamps are SEEDED
   *       at construction (boot grace for {@code SubscriberHealthCanary}), so a plain age would read
   *       ~0 here and be indistinguishable from a bar that just arrived. An earlier draft of this
   *       method did exactly that, and it is the same defect class as the detector this replaced: a
   *       number that cannot tell healthy from never-started.
   *   <li>{@code < -1} — the clock stepped BACKWARDS past the stamp. Also not floored: clamping hid
   *       a genuine clock fault (the very thing that produced an 87-minute drift on this host in
   *       July 2026) behind a value that reads as freshly alive.
   * </ul>
   */
  private double ageSeconds(boolean everStamped, long stampMs) {
    if (!everStamped) {
      return -1.0;
    }
    long deltaMs = clock.millis() - stampMs;
    return deltaMs < 0 ? Math.min(-1.001, deltaMs / 1000.0) : deltaMs / 1000.0;
  }

  /** Test seam: stamps the receive heartbeat so gauge behaviour can be driven without Redis. */
  void markBarReceivedForTest(long atMs) {
    lastBarReceivedAtMs = atMs;
    barEverReceived = true;
  }

  long lastBarReceivedAtMs() {
    return lastBarReceivedAtMs;
  }

  /** Wall-clock millis of the last completed {@code drain()} batch — the eval-side heartbeat. */
  long lastBarEvaluatedAtMs() {
    return lastBarEvaluatedAtMs;
  }

  /**
   * One read of every {@link Outcome} counter, stamped with the epoch those counters belong to.
   *
   * <p>The two travel together on purpose. {@link SignalEvalOutcomeRollupJob} persists deltas
   * against a checkpoint it reads back from the database, scoped by {@code epoch}; if the epoch
   * could ever be stale relative to the counts, the job would difference this boot's counters
   * against a previous boot's checkpoint and silently over- or under-count. Returning both from a
   * single call makes that mismatch unrepresentable.
   *
   * @param epoch identifies this counter generation — see {@link SignalEngine#counterEpoch}
   * @param counts every {@code Outcome} tag, including those still at zero
   */
  record OutcomeSnapshot(java.util.UUID epoch, java.util.Map<Outcome, Long> counts) {}

  /**
   * A point-in-time read of every {@link Outcome} counter, for {@link SignalEvalOutcomeRollupJob} to
   * persist (the counters are in-memory and reset on restart, so liveness was not answerable
   * retroactively — see {@code V045__signal_eval_outcomes.sql}).
   *
   * <p><b>Costs the eval thread nothing.</b> This is a read of counters already maintained in
   * memory, called from the rollup's own scheduler thread. Each {@code Counter.count()} is a
   * {@code DoubleAdder} sum — non-blocking, no lock the eval thread could ever contend on. There is
   * deliberately no inline write anywhere in the evaluation path.
   *
   * <p>Returns a snapshot over ALL {@code Outcome.values()} — every tag is pre-registered at boot
   * (see the constructor), so an outcome that has not happened yet reads 0 rather than going
   * missing. That is the same reasoning as the meter registration: a MISSING series would again be
   * indistinguishable from an engine that never evaluated. Increments are always +1, so the
   * {@code double} is exact at these magnitudes and the {@code long} cast is lossless.
   */
  OutcomeSnapshot outcomeSnapshot() {
    java.util.EnumMap<Outcome, Long> counts = new java.util.EnumMap<>(Outcome.class);
    outcomeCounters.forEach((outcome, counter) -> counts.put(outcome, (long) counter.count()));
    return new OutcomeSnapshot(counterEpoch, counts);
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
    long receivedAtMs = clock.millis();
    lastBarReceivedAtMs = receivedAtMs; // subscriber-liveness heartbeat (SubscriberHealthCanary)
    barEverReceived = true; // gauge sentinel: distinguishes "fresh" from "never started"
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
      pending.add(new PendingBar(symbolKey, candle, receivedAtMs)); // queue, never collapse
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
    PendingBar head;
    while ((head = pending.poll()) != null) {
      String[] parts = head.symbolKey().split(":", 2);
      EngineCandle bar = head.candle();
      long receivedAtMs = head.receivedAtMs();
      withBarReceiptTimestamp(
          receivedAtMs, () -> evalTimer.record(() -> onClosedBar(parts[0], parts[1], bar)));
    }
    // Eval-side heartbeat (audit A13): stamped on THIS (signal-eval) thread only when the batch has
    // fully drained, so a stall INSIDE onClosedBar freezes it while bars keep being received — the
    // signature SubscriberHealthCanary alarms on. See lastBarEvaluatedAtMs.
    lastBarEvaluatedAtMs = clock.millis();
    barEverEvaluated = true;
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
      // Record the outcome of EVERY evaluation (chip task_37ee83e0). THE ONLY increment site —
      // decideEntry returns exactly one Outcome on every path, so Σ(outcomes) == evaluations by
      // construction. Counter-only: this decides nothing and must never alter what is traded.
      Outcome outcome =
          decideEntry(strategy, exchange, tradingsymbol, interval, bar, bank, primary, index);
      outcomeCounters.get(outcome).increment();
      warnIfUnscoreablePastWarmup(outcome, strategy, exchange, tradingsymbol, interval, bar);
    }
  }

  /**
   * The entry decision for one bar: performs the emit / rejection side effects and RETURNS what
   * happened. The return type is the guard — every path must yield an {@link Outcome}, so a future
   * branch cannot silently skip counting the way the pre-chip chart-stage "no" did.
   */
  private Outcome decideEntry(
      Loaded strategy, String exchange, String tradingsymbol, String interval, EngineCandle bar,
      IndicatorBank bank, EngineSeries primary, int index) {
    // EntryEvaluator ALREADY builds the full breakdown on the no-entry path and returns it; the
    // engine used to compute it and throw it away. This uses what is already in hand.
    Optional<EntryEvaluator.Evaluation> evaluation =
        EntryEvaluator.evaluate(strategy.definition(), bank, index);
    Outcome chart = classifyEntryOutcome(evaluation);
    if (chart != Outcome.FIRED) {
      // Observability only: records what already happened, returns nothing, and cannot throw — the
      // Outcome returned below is identical to the one this path returned before. The
      // Σ(outcomes) == evaluations invariant is untouched: still exactly one Outcome out of this
      // path, still counted exactly once by the sole caller.
      recordCompositeRejection(chart, strategy, exchange, tradingsymbol, interval, bar, evaluation);
      return chart;
    }
    if (strategy.scalper() != null) {
      return scalperEntry(
          strategy, exchange, tradingsymbol, interval, bar, evaluation.get(), bank, primary, index);
    }
    emitEntry(strategy, exchange, tradingsymbol, interval, bar, evaluation.get(), null, null);
    return Outcome.FIRED;
  }

  /**
   * The chart-stage verdict for one evaluated bar — a pure function of what {@link EntryEvaluator}
   * already returns. Gate before composite: that is the order {@code EntryEvaluator} itself applies
   * ({@code entry = gate.passed() && thresholdMet}), so when BOTH fail the gate is the reported
   * cause. {@link Outcome#FIRED} means only that the CHART stage said yes — for a scalper the
   * confluence stage runs next and may still block.
   */
  static Outcome classifyEntryOutcome(Optional<EntryEvaluator.Evaluation> evaluation) {
    if (evaluation.isEmpty()) {
      return Outcome.UNSCOREABLE_INDICATORS_WARMING; // a required participant scored null
    }
    if (evaluation.get().entry()) {
      return Outcome.FIRED;
    }
    return evaluation.get().breakdown().gate().passed()
        ? Outcome.COMPOSITE_BELOW_THRESHOLD
        : Outcome.CHART_GATE_FAILED;
  }

  /**
   * Persists ONE {@code composite_rejections} row (V044) when an entry evaluation ended in {@link
   * Outcome#COMPOSITE_BELOW_THRESHOLD} — the outcome that carries the tuning signal. All 38
   * surviving published scalpers share ONE composite ({@code rsi14} rsi_momentum w1 + {@code
   * supertrend} direction w1 at threshold 0.2 on one 3m NIFTY-future series), so this outcome is
   * effectively "RSI did not clear", and the row makes the RSI operand distribution vs the ~58 it
   * needs analysable. Everything persisted is ALREADY computed: {@link EntryEvaluator} builds the
   * full {@link ScoreBreakdown} on the no-entry path and returns it, and the engine used to discard
   * it here.
   *
   * <p><b>Deliberately NOT recorded: {@link Outcome#CHART_GATE_FAILED}.</b> Measured 2026-07-20 it
   * runs ~3.4x the volume (504 vs 192 in a session) and carries no tuning signal — a gate said no,
   * and the composite was never consulted, so there is no operand distribution to study. The
   * exclusion is load-bearing, not incidental; {@code SignalEngineCompositeRejectionTest} asserts it
   * exhaustively over every {@link Outcome}. The other five outcomes are excluded for the same
   * reason: none of them is a composite verdict.
   *
   * <p><b>Its own table, NOT a new {@code signal_rejections} rail.</b> That table records why the
   * §12.3 CONFLUENCE gate blocked a chart-entry, and all of its consumers assume every row carries
   * the confluence diagnostic. Sharing it was built and rejected: it forced a filter into
   * {@link DotHealthCanary}'s query to stop ~38 dot-less rows per SuperTrend-DOWN bar flooding its
   * 40-row window and paging every required dot as DEAD. See the V044 header.
   *
   * <p><b>Never breaks the live path.</b> The write is an O(1) enqueue onto the bounded async
   * {@link CompositeRejectionWriter} (a saturated queue drops + counts, never back-pressures the
   * sole {@code signal-eval} thread), and a persistence failure is swallowed and logged inside the
   * writer — the same doctrine {@link #recordRejection} already follows.
   *
   * <p><b>Live-only by construction:</b> the deterministic golden replay drives
   * {@code TickwiseGoldenRunner}, never this engine, so no rows exist on backtest and parity is
   * unaffected.
   *
   * <p>Package-visible so the exhaustive per-outcome test can drive it directly.
   */
  void recordCompositeRejection(
      Outcome outcome, Loaded strategy, String exchange, String tradingsymbol, String interval,
      EngineCandle bar, Optional<EntryEvaluator.Evaluation> evaluation) {
    // evaluation.isEmpty() cannot co-occur with this outcome (classifyEntryOutcome returns it only
    // from the present branch) — folded into the same early return so the method is total and can
    // never throw onto the eval thread.
    if (!recordCompositeRejections
        || outcome != Outcome.COMPOSITE_BELOW_THRESHOLD
        || evaluation.isEmpty()) {
      return;
    }
    ScoreBreakdown breakdown = evaluation.get().breakdown();
    // CompositeScorer divides at scale 8, so a raw composite reads `0.12500000`. Normalize through
    // the SAME canonicalization the persisted breakdown uses (no exponent, no trailing zeros) so the
    // numeric columns and the JSON they summarize agree — otherwise the row's `composite` column
    // reads 0.12500000 while its own score_breakdown reads 0.125.
    BigDecimal composite = CanonicalJson.normalize(breakdown.composite());
    BigDecimal threshold = CanonicalJson.normalize(breakdown.threshold());
    compositeRejections.record(
        strategy.versionId(),
        strategy.slug(),
        exchange,
        tradingsymbol,
        interval,
        composite,
        threshold,
        CanonicalJson.normalize(composite.subtract(threshold)), // signed; negative = how far short
        // The FROZEN canonical writer — the same one the live signals.score_breakdown column and the
        // replay use. A READ of the frozen DTO: no new shape, and nothing here can move parity.
        ScoreBreakdownJson.write(breakdown),
        bar.bucketStart().withOffsetSameInstant(Ist.OFFSET));
  }

  /** True once the bar is past {@link #WARMUP_GRACE_UNTIL} in IST — i.e. mid-session. */
  static boolean pastWarmupGrace(OffsetDateTime barStart) {
    return !barStart.withOffsetSameInstant(Ist.OFFSET).toLocalTime().isBefore(WARMUP_GRACE_UNTIL);
  }

  /**
   * True the FIRST time a series is seen unscoreable in a session, false for every repeat within
   * it. {@code put} returns the previous stamp, so this claims-and-tests in one step and a new
   * session date re-arms the WARN.
   */
  static boolean firstUnscoreableForSeries(
      java.util.Map<String, LocalDate> seen, String seriesKey, LocalDate session) {
    return !session.equals(seen.put(seriesKey, session));
  }

  /**
   * The one no-entry outcome that is a FAULT, not a market "no": a required indicator still
   * unscoreable mid-session means the series never warmed. Everything else stays counter-only.
   * Logged ONCE per series per session — see {@link #unscoreableWarnedFor}.
   */
  private void warnIfUnscoreablePastWarmup(
      Outcome outcome, Loaded strategy, String exchange, String tradingsymbol, String interval,
      EngineCandle bar) {
    if (outcome != Outcome.UNSCOREABLE_INDICATORS_WARMING || !pastWarmupGrace(bar.bucketStart())) {
      return;
    }
    OffsetDateTime istBar = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    String seriesKey = exchange + ":" + tradingsymbol + ":" + interval;
    if (!firstUnscoreableForSeries(unscoreableWarnedFor, seriesKey, istBar.toLocalDate())) {
      return;
    }
    log.warn(
        "required indicator STILL unscoreable mid-session on {} at {} — {} (and every strategy"
            + " sharing this series) cannot be evaluated; the series likely never warmed (REST"
            + " back-fill empty at load). Logged ONCE per series per session. Every other no-entry"
            + " outcome is a normal market 'no'; this one is not.",
        seriesKey,
        istBar,
        strategy.slug());
  }

  /**
   * Track-2 entry: the chart gate passed; now the §12.3 confluence seam must also confirm and pick
   * the option, or the entry is blocked. Fail-closed — a scalper strategy without the gate never
   * fires. The signal is keyed on the index FUTURE (this {@code exchange}/{@code tradingsymbol}); the
   * picked option rides the side-channel. RETURNS the outcome; the single caller counts it once.
   */
  private Outcome scalperEntry(
      Loaded strategy, String exchange, String tradingsymbol, String interval, EngineCandle bar,
      EntryEvaluator.Evaluation evaluation, BarValues bank, EngineSeries future, int index) {
    if (scalperGate.isEmpty()) {
      log.warn("scalper strategy {} loaded but confluence gate absent — entry suppressed", strategy.slug());
      return Outcome.CONFLUENCE_GATE_ABSENT;
    }
    // §12.7 scalper 5-account discipline: 5 losses freeze all sub-accounts / 5 wins bank the day.
    // Consulted IN ADDITION to the global risk gate (checked later in emitEntry); scalper entries only.
    if (emissionGuard.isPresent() && !emissionGuard.get().scalperEntryAllowed()) {
      log.info("scalper ENTRY paused by the 5-account discipline: {} {}:{}", strategy.slug(), exchange, tradingsymbol);
      return Outcome.DISCIPLINE_PAUSED;
    }
    OffsetDateTime istBar = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    ScalperConfluenceGate.Result result =
        scalperGate.get().evaluateWithDiagnostic(
            strategy.scalper(), bank, future, index, bar.bucketStart().toInstant(),
            istBar.toLocalTime(), istBar.toLocalDate());
    if (result.blocked()) {
      recordRejection(strategy, exchange, tradingsymbol, interval, istBar, result.rejection());
      return Outcome.CONFLUENCE_BLOCKED;
    }
    emitEntry(
        strategy, exchange, tradingsymbol, interval, bar, evaluation, result.decision().get(),
        result.fired());
    return Outcome.FIRED;
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

  // Package-visible for BtstPreCloseExitIntegrationTest (drives the exit sweep directly).
  void preCloseEvaluate(
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
    int index = daily.size() - 1;
    // P0-5 live port (chip task_3e95fade): the sim (TickwiseGoldenRunner btst branch) evaluates the
    // strategy's exit_rules at each SUBSEQUENT pre-close daily bar, so a btst carry EXITS next session
    // (close→close; time_stop max_holding_days:1 fires one trading day later) instead of never exiting
    // live. Sweep an active carry from a PRIOR session HERE, before the entry evaluation — mirroring the
    // runner's exit-before-entry ordering. Runs even for an avoid-friday-carry strategy (a Thursday carry
    // must still EXIT on Friday; only a fresh Friday ENTRY is skipped below). An active carry is never
    // ALSO re-opened on the same clock (return after the sweep — conservative vs the sim's same-bar
    // re-entry). Live-only — the golden replay never runs preCloseClock, so vectors stay byte-identical.
    // Roll-orphaning corner (review LOW, accepted 2026-07-12): activeEntry is keyed (versionId,
    // exchange, tradingsymbol) — if the ~08:40 futures roll re-resolves the universe to a NEW front
    // contract, a carry anchored on the PRIOR contract's symbol is no longer reached by this sweep
    // (the old symbol left the universe). Out of scope here; the orphan's operative exits remain the
    // paper leg's premium brackets (PaperBracketEvaluator) + the 21:15 paper reconcilers.
    Optional<SignalRepository.SignalRow> activeCarry =
        signals.activeEntry(
            strategy.versionId(), instrument.exchange(), instrument.tradingsymbol());
    if (activeCarry.isPresent()) {
      sweepBtstExit(
          strategy, instrument, daily, index, dayBars.get(dayBars.size() - 1), activeCarry.get());
      return;
    }
    // E12 §3.8 avoid-Friday carry (tag avoid-friday-carry): a fresh BTST/STBT ENTRY on a Friday carries
    // weekend-gap risk (2+ nights, beyond the strategy's "<=1 night" mandate) — skip OPENING the carry on
    // Fridays. Default-OFF; only the btst YAMLs carrying the tag opt in. The EXIT sweep above already ran,
    // so a carry opened earlier in the week still exits today.
    if (strategy.scalper() != null
        && strategy.scalper().has("avoid-friday-carry")
        && today.getDayOfWeek() == java.time.DayOfWeek.FRIDAY) {
      return;
    }
    IndicatorBank bank = IndicatorBank.build(strategy.definition(), instrument, seriesStore);
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
   * The BTST exit sweep (P0-5 live port of {@code TickwiseGoldenRunner}'s btst branch): evaluate the
   * strategy's exit_rules against the pre-close DAILY series — one bar per session — exactly as the sim
   * does, so a live carry exits close→close (a {@code time_stop max_holding_days:1} fires one trading day
   * after entry).
   *
   * <p><b>PARITY BOUNDARY (review MED, 2026-07-12):</b> sim-vs-live equivalence holds ONLY for exit
   * types that read the persisted {@code entryPrice} + today's appended pre-close bar — {@code
   * time_stop} and the percent-of-entry level rules ({@code premium_pct}/{@code percent} stop/take).
   * Price-HISTORY-reading types ({@code atr_multiple}, {@code trailing_stop}, {@code square_off},
   * {@code r_multiple}, {@code signal_exit}) would compute off DIFFERENT bars sim-vs-live — the sim's
   * primary holds synthetic 15:20 pre-close daily bars while the live {@code 1d} series holds native
   * ~15:30 cagg/bhavcopy bars — and would drift SILENTLY. {@code BtstExitRuleParityBoundaryTest}
   * fences the btst YAMLs to the safe set so an out-of-set rule fails loudly, not as reconciliation
   * noise. Note also: the {@code premium_pct} stop evaluated HERE runs on the INDEX-FUTURE series and
   * is effectively inert live (it would need a 50% index move); the REAL 50%-premium stop on the
   * option leg rides the paper bracket levels ({@code PaperSignalListener.premiumBrackets} →
   * {@code PaperBracketEvaluator} — the live counterpart of the sim's {@code PremiumExitEvaluator}).
   *
   * <p>The sim repurposes the primaryTimeframe-keyed series to HOLD the pre-close daily bars, so its
   * {@code bank.primarySeries()} IS that daily series; this mirrors that with a provider that maps the
   * primary timeframe to {@code daily} (non-primary indicator timeframes — e.g. a 1h bias — resolve
   * through the live store, already refreshed by the caller). The entry anchor is the daily bar at or
   * before the signal's {@code generated_at} (the entry session's bar); the direction is the HELD side
   * ({@link #scalperPositionDirection}). Emits the EXIT (which closes the linked paper position via
   * {@code SignalExited}) only when a rule fires. Live-only — never runs on the deterministic replay.
   */
  private void sweepBtstExit(
      Loaded strategy,
      StrategyDefinition.InstrumentRef instrument,
      EngineSeries daily,
      int index,
      EngineCandle preCloseBar,
      SignalRepository.SignalRow activeCarry) {
    SeriesProvider dailyPrimary =
        key ->
            key.exchange().equals(instrument.exchange())
                    && key.tradingsymbol().equals(instrument.tradingsymbol())
                    && key.interval().equals(strategy.definition().primaryTimeframe())
                ? daily
                : seriesStore.series(key);
    IndicatorBank bank = IndicatorBank.build(strategy.definition(), instrument, dailyPrimary);
    // The entry session's daily-bar index: generated_at is the entry-day pre-close instant (~15:20 IST),
    // and each daily bar starts at 00:00 IST, so indexAtOrBefore lands on the entry session's bar. Clamp
    // to [0, index] (a warmup-window miss floors to 0; it can never be after today's appended bar).
    int entryIndex =
        Math.min(Math.max(daily.indexAtOrBefore(activeCarry.generatedAt().toInstant()), 0), index);
    Optional<ExitEvaluator.ExitDecision> exit =
        ExitEvaluator.evaluate(
            strategy.definition(),
            bank,
            new ExitEvaluator.Position(
                scalperPositionDirection(strategy, activeCarry), activeCarry.entryPrice(), entryIndex),
            index);
    if (exit.isPresent()) {
      emit(
          strategy, instrument.exchange(), instrument.tradingsymbol(), "1d", "EXIT", preCloseBar,
          activeCarry, exit.get().type().toUpperCase(java.util.Locale.ROOT));
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
    // entryVeto is behaviourally identical to entryAllowed (a single call, same decision AND audit
    // side-effects — see EmissionGuard.entryVeto) but surfaces WHICH rail vetoed. PF-03: a veto now
    // writes a durable risk_suppressions record (observability only — the veto itself is unchanged).
    if (emissionGuard.isPresent()) {
      java.util.Optional<String> veto = emissionGuard.get().entryVeto(strategy.book());
      if (veto.isPresent()) {
        log.info("ENTRY suppressed by {} risk gate ({}): {} {}:{}",
            strategy.book(), veto.get(), strategy.slug(), exchange, tradingsymbol);
        recordRiskSuppression(
            strategy, exchange, tradingsymbol, interval, bar, decision, veto.get());
        return;
      }
    }
    BigDecimal entryPrice = bar.close();
    // T21 review round 2 (Critical): premium_pct rules are OPTION-side bands — resolving them
    // against the INDEX entry price here produced nonsense levels (25% of a 25,000 future = a
    // 6,250-point "stop"), and for a held-PE (SHORT-direction) position that below-entry stop made
    // structuralStopHit true on EVERY bar — a one-bar force-exit. Index-side levels come ONLY from
    // the structural stop and the index_points rule below; the premium band is enforced on the
    // option leg by the paper bracket path (PremiumBracketRules / PaperSignalListener), which
    // filters by basis and resolves against the option LTP.
    BigDecimal stopLoss = null;
    // §3.1/§3.6 structural stop: a scalper anchors its stop on the 1st-candle (Two-Candle) or
    // entry-candle (Golden-Cross) extreme of the index future, captured at entry. It overrides the
    // (absent) YAML rule level and is the price the bar-close structural-stop exit check fires on.
    if (decision != null && decision.structuralStop() != null) {
      stopLoss = decision.structuralStop();
    }
    // W3 PR-4 (S24 ratification D36/D37/D30/D46, additive fallback/cap): an index_points stop_loss
    // rule bounds the stop to a fixed point distance (BN ~100 / N ~50-60 / SENSEX ~200-250) — the
    // FALLBACK when no other stop is set, and a CAP that clamps a too-wide structural stop to that
    // distance (the tighter of the two wins). Carried today by the connect-the-dots and trending-oi
    // families (12 YAMLs). The stop side follows the HELD option exposure, not the definition
    // direction — see entryExposureIsShort.
    boolean shortDir =
        entryExposureIsShort(
            decision == null ? null : decision.side(), strategy.definition().direction());
    BigDecimal pointStop =
        indexPointStopLevel(strategy.definition().exitRules(), shortDir, entryPrice);
    if (pointStop != null) {
      stopLoss = (stopLoss == null) ? pointStop : closerToEntry(entryPrice, stopLoss, pointStop);
    }
    // T21 round 2: the take_profit premium band is likewise option-side only. The old
    // premium_pct→index resolution persisted entry×1.35 "targets" for the bracketed families —
    // inert for exits but garbage in signals.target; null is the honest index-side value.
    BigDecimal target = null;
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
    stampEmissionLatency(id);
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
   * Records the live-only rejection diagnostic (WHY the confluence gate blocked this scalper
   * chart-entry) and logs the structured reason. LIVE path only — the deterministic replay never
   * reaches the gate, so this is never invoked on backtest (no rows there → parity-safe). The persist
   * (INSERT + the id-coupled shadow-book open) is ENQUEUED to the bounded async {@link RejectionWriter}
   * so a DB stall can never park the sole {@code signal-eval} thread (task_084d4d01, #866 class): the
   * enqueue is O(1) here and fail-soft inside the writer. The diagnostic JSON is built HERE (in-memory,
   * off the DB round trip) but that is cheap CPU on already-loaded objects, not the stall exposure.
   */
  private void recordRejection(
      Loaded strategy, String exchange, String tradingsymbol, String interval, OffsetDateTime barTime,
      ScalperConfluenceGate.RejectionDiagnostic d) {
    if (d == null) {
      log.info("scalper confluence blocked entry: {} {}:{} (no diagnostic)", strategy.slug(), exchange, tradingsymbol);
      return;
    }
    rejectionWriter.record(
        strategy.versionId(), strategy.slug(), exchange, tradingsymbol, interval,
        d.side() == null ? null : d.side().name(), d.blockingRail(), d.operand(), d.threshold(),
        d.margin(), d.reason(), d.compositeScore(), d.compositeThreshold(),
        rejectionDiagnosticJson(d), barTime, d);
    log.info(
        "scalper confluence blocked entry: {} {}:{} rail={} operand={} threshold={} margin={} composite={}/{} ({})",
        strategy.slug(), exchange, tradingsymbol, d.blockingRail(), d.operand(), d.threshold(),
        d.margin(), d.compositeScore(), d.compositeThreshold(), d.reason());
  }

  /**
   * PF-03: record a durable {@code risk_suppressions} row when the per-book risk governor vetoed an
   * ENTRY on THIS (tick/paper-engine) entry path — scalper, non-scalper intraday, and the btst carry.
   * (The daily {@code SwingBatchEngine}, {@code session.style=swing}, has its own veto and stays
   * log-only — not covered here.) OBSERVABILITY ONLY — called AFTER the veto decision is made; it
   * records, never decides. The persist is ENQUEUED to the bounded async {@link RiskSuppressionWriter}
   * so a DB stall can never park the sole {@code signal-eval} thread (the write is O(1) here and
   * fail-soft inside the writer). Everything computed below is in-memory: the would-be option leg
   * ({@code side}/{@code optionType}/{@code optionTradingsymbol}) is read cheaply from the confluence
   * {@link ScalperConfluenceGate.Decision} in hand — no new state; {@code decision} is null on the
   * non-scalper path, leaving the option columns null.
   */
  private void recordRiskSuppression(
      Loaded strategy, String exchange, String tradingsymbol, String interval, EngineCandle bar,
      ScalperConfluenceGate.Decision decision, String rail) {
    OffsetDateTime barTime = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    String side =
        strategy.definition().direction() == StrategyDefinition.Direction.SHORT ? "SELL" : "BUY";
    String optionType = null;
    String optionTradingsymbol = null;
    if (decision != null) {
      // side() is null for the #11 neutral straddle; pick() is the primary leg (never empty).
      optionType = decision.side() == null ? null : decision.side().name();
      optionTradingsymbol = decision.pick().candidate().tradingsymbol();
    }
    riskSuppressions.record(
        strategy.versionId(), strategy.slug(), strategy.book(), rail, exchange, tradingsymbol,
        interval, side, optionType, optionTradingsymbol, barTime);
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
    stampEmissionLatency(id);
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

  /** Live-only wall-clock side-channel; deterministic replay never enters either emit method. */
  void stampEmissionLatency(long signalId) {
    OffsetDateTime emittedAt = OffsetDateTime.now(clock);
    Long latencyMs =
        emitLatencyMs(emittedAt.toInstant().toEpochMilli(), currentBarReceivedAtMs.get());
    try {
      signals.stampEmittedAt(signalId, emittedAt, latencyMs);
    } catch (RuntimeException e) {
      // Telemetry is a post-commit side-channel. Never strand a committed ENTRY before publish, or
      // a committed EXIT before SignalExited closes its paper position.
      log.warn("failed to stamp emission latency for signal #{}: {}", signalId, e.toString());
    }
    if (latencyMs != null && latencyMs >= 0) {
      try {
        barToEmitTimer.record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (RuntimeException e) {
        log.warn("failed to record emission-latency metric for signal #{}: {}", signalId, e.toString());
      }
    }
  }

  /** Scopes one causal Redis receipt timestamp to its evaluation, clearing it on every exit path. */
  void withBarReceiptTimestamp(long receivedAtMs, Runnable evaluation) {
    currentBarReceivedAtMs.set(receivedAtMs);
    try {
      evaluation.run();
    } finally {
      currentBarReceivedAtMs.remove();
    }
  }

  /** Null rather than a bogus duration until the receive path has observed a bar timestamp. */
  static Long emitLatencyMs(long nowMs, long lastBarReceivedAtMs) {
    return lastBarReceivedAtMs == 0 ? null : nowMs - lastBarReceivedAtMs;
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

  /** True while a CONNECTED retry chain is still running (tests wait for quiescence). */
  boolean kiteConnectedReloadInFlight() {
    return kiteConnectedReloadInFlight.get();
  }

  private void requestKiteConnectedReload() {
    if (stopped.get()) {
      return;
    }
    if (!kiteConnectedReloadInFlight.compareAndSet(false, true)) {
      log.info("kite.status CONNECTED received — reload retry already in flight");
      return;
    }
    log.info("kite.status CONNECTED received — requesting bounded strategy reload");
    submitKiteConnectedReloadAttempt(1, null);
  }

  private void submitKiteConnectedReloadAttempt(int attempt, ReloadOutcome firstOutcome) {
    boolean handedOff = false;
    try {
      if (stopped.get()) {
        return;
      }
      evalExecutor.execute(() -> runKiteConnectedReloadAttempt(attempt, firstOutcome));
      handedOff = true;
    } catch (RejectedExecutionException e) {
      if (!evalExecutor.isShutdown()) {
        log.warn("kite.status reload attempt {} was rejected: {}", attempt, e.toString());
      }
    } finally {
      // Clear unless the attempt now OWNS the flag. `finally` (not scattered set(false) calls) so an
      // Error — not just a RuntimeException — can never strand the flag true: a stuck flag would make
      // the CAS in requestKiteConnectedReload() reject every future CONNECTED, silently disabling
      // this self-heal for the life of the JVM.
      if (!handedOff) {
        kiteConnectedReloadInFlight.set(false);
      }
    }
  }

  private void runKiteConnectedReloadAttempt(int attempt, ReloadOutcome firstOutcome) {
    boolean retryScheduled = false;
    try {
      if (stopped.get()) {
        return;
      }
      ReloadOutcome outcome = null;
      try {
        // keep-best: a RETRY must never leave the engine holding less than it already does.
        // Keep coverage IN_FLIGHT until this coordinator knows whether the chain converged or
        // exhausted. An exception still leaves the pre-body marker in place so an aborted attempt
        // cannot be mistaken for the last completed snapshot.
        // Reuse the chain's generation so the SNAPSHOT_MISSING grace clock runs from when the CHAIN
        // started, not from this attempt — otherwise every retry resets it and predicate B goes blind
        // for the whole retry window.
        outcome = reload(true, false, connectedChainGeneration);
      } catch (RuntimeException e) {
        log.warn("kite.status reload attempt {} failed: {}", attempt, e.toString());
      } finally {
        // Capture the generation even when the attempt THREW — beginCoverageReload already published
        // the marker, so without this a chain whose first attempt throws would mint a fresh
        // generation on attempt 2 and reset its own grace clock once.
        StrategyCoverageSnapshot published = coverageSnapshot;
        if (connectedChainGeneration == null && published != null) {
          connectedChainGeneration = published.requestedGeneration();
        }
      }
      ReloadOutcome first = attempt == 1 ? outcome : firstOutcome;

      // Converge on a HEALTHY reload — NOT on "something loaded", and NOT on "0 unresolved" alone.
      // The drop is per strategy, so a breaker re-opening partway through leaves 1-of-39 loaded (a
      // non-empty `loaded` that is really a DEGRADED session); and a reload where every strategy
      // took an UNCOUNTED skip reports 0 drops over a DEAD engine. ReloadOutcome.healthy rejects
      // both, so success can never be reported while the engine holds zero usable strategies. A
      // legitimately all-swing registry has no live candidates ⇒ healthy on attempt 1.
      if (outcome != null && (outcome.healthy() || attempt >= KITE_CONNECTED_RELOAD_MAX_ATTEMPTS)) {
        completeCoverageReload(outcome.coverageGeneration(), outcome.coverageClassifications());
        connectedChainGeneration = null; // chain over — the next one starts a fresh grace clock
      }
      if (outcome != null && outcome.healthy()) {
        log.info(
            "kite.status reload attempt {} resolved every universe ({} loaded) — retry complete",
            attempt, outcome.loadedCount());
        return;
      }
      if (attempt >= KITE_CONNECTED_RELOAD_MAX_ATTEMPTS) {
        // Both states, no derived verdict: keep-best reconciles PER STRATEGY, so no aggregate
        // comparison here could honestly say what it retained — and a misleading telemetry line is
        // worse than none. KEEP_BEST_RETAINED_LAST_GOOD is the factual signal, logged by the reload
        // itself with the count, at the moment the retention happens.
        log.error(
            "kite.status reload exhausted {} attempts — the engine holds {} loaded / {} unresolved "
                + "and is DEGRADED until the next 08:40 reload or a republish; attempt 1 computed "
                + "{} loaded / {} unresolved / {} load errors",
            KITE_CONNECTED_RELOAD_MAX_ATTEMPTS, loaded.size(), lastReloadUnresolvedDrops,
            first == null ? -1 : first.loadedCount(),
            first == null ? -1 : first.unresolvedDrops(),
            first == null ? -1 : first.loadErrors());
        return;
      }

      log.info(
          "kite.status reload attempt {} left the engine at {} loaded / {} unresolved — retrying "
              + "in {} ms",
          attempt, loaded.size(), lastReloadUnresolvedDrops, kiteConnectedReloadDelayMillis);
      try {
        kiteConnectedReloadScheduler.schedule(
            () -> submitKiteConnectedReloadAttempt(attempt + 1, first),
            kiteConnectedReloadDelayMillis,
            TimeUnit.MILLISECONDS);
        retryScheduled = true;
      } catch (RejectedExecutionException e) {
        if (!kiteConnectedReloadScheduler.isShutdown()) {
          log.warn("kite.status reload retry scheduling failed: {}", e.toString());
        }
      }
    } finally {
      if (!retryScheduled) {
        kiteConnectedReloadInFlight.set(false);
      }
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

  /**
   * The direction the persisted index-side stop must protect — the entry-time counterpart of
   * {@link #scalperPositionDirection}. A scalper take holding a PE has SHORT index exposure
   * regardless of the definition's direction: the seam derives CE/PE per entry from price vs VWAP
   * and every {@code -pe} mirror YAML declares {@code direction: long}, so keying the stop side off
   * {@code definition.direction()} put every PE-side point stop BELOW entry — which
   * {@code structuralStopHit(SHORT, ...)} ({@code bar.high >= stop}) trips on the very next bar: a
   * one-bar force-exit (same defect class as the T21/#990 premium_pct resolution). The held option
   * side wins; a neutral straddle ({@code side} null) and a non-scalper entry fall back to the
   * definition direction, matching {@link #scalperPositionDirection}'s fallback.
   */
  static boolean entryExposureIsShort(
      OptionType heldSide, StrategyDefinition.Direction definitionDirection) {
    return heldSide != null
        ? heldSide == OptionType.PE
        : definitionDirection == StrategyDefinition.Direction.SHORT;
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
