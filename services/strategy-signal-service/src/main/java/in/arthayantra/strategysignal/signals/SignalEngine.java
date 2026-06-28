package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
      ScalperConfig scalper) {}

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

  // candles.1m.* are NEVER conflated (A.7.2 bus contract — every closed bar matters). A FIFO
  // queue preserves each distinct bar in arrival order; the single-threaded evalExecutor drains it
  // in order. (A latest-value-wins map would drop a bar under a burst, leaving a permanent series
  // gap and potentially skipping a stop-loss EXIT.)
  private final java.util.Queue<Map.Entry<String, EngineCandle>> pending =
      new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final AtomicBoolean drainScheduled = new AtomicBoolean();
  private final AtomicBoolean reloadRequested = new AtomicBoolean(true);
  private final Map<String, LocalDate> preCloseDone = new ConcurrentHashMap<>();
  private final ExecutorService evalExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "signal-eval");
            t.setDaemon(true);
            return t;
          });

  private volatile List<Loaded> loaded = List.of();
  private volatile RedisMessageListenerContainer container;

  private final Timer evalTimer;
  private final Counter emitted;

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
      @Value("${artha.signals.ttl-minutes:60}") int signalTtlMinutes) {
    this.registry = registry;
    this.signals = signals;
    this.publisher = publisher;
    this.events = events;
    this.seriesStore = seriesStore;
    this.futuresResolver = futuresResolver;
    this.connectionFactory = connectionFactory;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.signalTtlMinutes = signalTtlMinutes;
    this.emissionGuard = emissionGuard;
    this.scalperGate = scalperGate;
    this.evalTimer = meterRegistry.timer("ay_signal_eval_duration_seconds");
    this.emitted = meterRegistry.counter("ay_signals_emitted_total");
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
  }

  /** (Re)loads published+enabled strategies and rebuilds subscriptions. */
  public synchronized void reload() {
    reloadRequested.set(false);
    List<Loaded> fresh = new ArrayList<>();
    for (StrategyRepository.StrategyRow strategy : registry.listAll()) {
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
                universe, keys, scalper));
      } catch (RuntimeException e) {
        log.error("strategy {} failed to load — skipped: {}", strategy.slug(), e.getMessage());
      }
    }
    this.loaded = List.copyOf(fresh);
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
      default -> {
        // index_constituents cannot publish until Phase 44 (the registry guard) — defensive
        log.warn("universe mode '{}' is not live-resolvable yet", mode);
        yield List.of();
      }
    };
  }

  private synchronized void resubscribe() {
    if (container != null) {
      container.stop();
    }
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
    this.container = fresh;
    log.info("subscribed {} candle channels (universes + context series only)", channels.size());
  }

  /** Redis receive thread: parse + conflate + hand off. NEVER evaluates here. */
  void onCandleMessage(String json) {
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
  }

  private void onClosedBar(String exchange, String tradingsymbol, EngineCandle bar) {
    seriesStore.append(new SeriesKey(exchange, tradingsymbol, "1m"), bar);
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
        log.error(
            "evaluation failed for {} on {}:{}: {}",
            strategy.slug(), exchange, tradingsymbol, e.getMessage());
      }
    }
  }

  private void evaluateAtBarClose(
      Loaded strategy, String exchange, String tradingsymbol, EngineCandle bar, String interval) {
    IndicatorBank bank =
        IndicatorBank.build(
            strategy.definition(),
            new StrategyDefinition.InstrumentRef(exchange, tradingsymbol),
            seriesStore);
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
              directionOf(strategy.definition()), primary.candle(index), activeEntry.get().stopLoss())) {
        emit(strategy, exchange, tradingsymbol, interval, "EXIT", bar, activeEntry.get());
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
        emit(strategy, exchange, tradingsymbol, interval, "EXIT", bar, activeEntry.get());
        return;
      }
      int entryIndex =
          primary.indexAtOrBefore(activeEntry.get().generatedAt().toInstant());
      Optional<ExitEvaluator.ExitDecision> exit =
          ExitEvaluator.evaluate(
              strategy.definition(), bank,
              new ExitEvaluator.Position(
                  directionOf(strategy.definition()),
                  activeEntry.get().entryPrice(),
                  Math.max(entryIndex, 0)),
              index);
      if (exit.isPresent()) {
        emit(strategy, exchange, tradingsymbol, interval, "EXIT", bar, activeEntry.get());
        return;
      }
    } else {
      Optional<EntryEvaluator.Evaluation> evaluation =
          EntryEvaluator.evaluate(strategy.definition(), bank, index);
      if (evaluation.isPresent() && evaluation.get().entry()) {
        if (strategy.scalper() != null) {
          scalperEntry(strategy, exchange, tradingsymbol, interval, bar, evaluation.get(), bank, primary, index);
        } else {
          emitEntry(strategy, exchange, tradingsymbol, interval, bar, evaluation.get(), null);
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
    Optional<ScalperConfluenceGate.Decision> decision =
        scalperGate.get().evaluate(
            strategy.scalper(), bank, future, index, bar.bucketStart().toInstant(),
            istBar.toLocalTime(), istBar.toLocalDate());
    if (decision.isEmpty()) {
      log.info("scalper confluence blocked entry: {} {}:{}", strategy.slug(), exchange, tradingsymbol);
      return;
    }
    emitEntry(strategy, exchange, tradingsymbol, interval, bar, evaluation, decision.get());
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
            Math.max(primary.indexAtOrBefore(activeEntry.get().generatedAt().toInstant()), 0);
        int entryOneMinuteIndex =
            Math.max(oneMinute.indexAtOrBefore(activeEntry.get().generatedAt().toInstant()), 0);
        Optional<ExitEvaluator.ExitDecision> exit =
            ExitEvaluator.evaluateIntrabarLevels(
                strategy.definition(), primary, entryPrimaryIndex, oneMinute,
                directionOf(strategy.definition()), activeEntry.get().entryPrice(),
                entryOneMinuteIndex, oneMinute.size() - 1);
        if (exit.isPresent()) {
          emit(strategy, exchange, tradingsymbol, "1m", "EXIT", bar, activeEntry.get());
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
    SeriesKey dailyKey =
        new SeriesKey(instrument.exchange(), instrument.tradingsymbol(), "1d");
    seriesStore.refreshFromRest(dailyKey);
    EngineSeries daily = seriesStore.series(dailyKey);
    if (daily == null) {
      return;
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
      emitEntry(
          strategy, instrument.exchange(), instrument.tradingsymbol(), "1d", lastOneMinute,
          evaluation.get(), null);
    }
  }

  private void emitEntry(
      Loaded strategy, String exchange, String tradingsymbol, String interval, EngineCandle bar,
      EntryEvaluator.Evaluation evaluation, ScalperConfluenceGate.Decision decision) {
    // A12 global risk gate: a daily-loss trip / kill switch / max-open cap pauses ENTRY emission
    // for the rest of the IST day — exit/stop evaluation (emit()) is deliberately NOT gated.
    if (emissionGuard.isPresent() && !emissionGuard.get().entryAllowed()) {
      log.info("ENTRY suppressed by global risk gate: {} {}:{}", strategy.slug(), exchange, tradingsymbol);
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
    // generated_at is the entry BAR's bucket instant, not wall-clock now(): it is persisted
    // explicitly (so the row and the channel payload carry the IDENTICAL instant), and it makes
    // the exit-side entry-index reconstruction (indexAtOrBefore) land exactly on the entry bar —
    // deterministic and identical to the replay harness.
    OffsetDateTime generatedAt = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    OffsetDateTime expiresAt = generatedAt.plusMinutes(signalTtlMinutes);
    long id =
        signals.insert(
            strategy.versionId(), exchange, tradingsymbol, interval, "ENTRY", side,
            entryPrice, stopLoss, target, evaluation.breakdown().composite(), breakdownJson,
            generatedAt, expiresAt);
    // A12 suggested qty (lot-rounded sizing vs paper equity), stamped OUTSIDE the score breakdown
    if (emissionGuard.isPresent()) {
      BigDecimal stopDistance = stopLoss == null ? null : entryPrice.subtract(stopLoss).abs();
      BigDecimal suggestedQty =
          emissionGuard.get().suggestedQty(
              strategy.definition().sizing(), exchange, tradingsymbol, entryPrice, stopDistance);
      if (suggestedQty != null) {
        signals.stampSuggestedQty(id, suggestedQty);
      }
    }
    // §12.9 Track-2 side-channel: the signal is keyed on the index future; record the option the
    // confluence picked (the order/paper layer trades it) + the confluence detail, OUTSIDE the
    // frozen score breakdown. Options trade on the same derivatives exchange as the index future.
    if (decision != null) {
      signals.stampScalperDetail(
          id, exchange, decision.pick().candidate().tradingsymbol(),
          scalperDetailJson(decision, strategy.scalper(), exchange));
    }
    emitted.increment();
    // the channel carries EXACTLY the persisted canonical bytes (divergence = FAIL criterion)
    JsonNode canonicalBreakdown;
    try {
      canonicalBreakdown = objectMapper.readTree(breakdownJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("canonical breakdown unparseable", e);
    }
    publisher.publish(
        id, strategy.versionId(), strategy.name(), strategy.slug(), strategy.version(),
        strategy.checksum(), exchange, tradingsymbol, interval, "ENTRY", side, entryPrice,
        stopLoss, target, evaluation.breakdown().composite(), canonicalBreakdown, generatedAt);
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
                decision.ohTier() == null ? null : OpenHighLow.probabilityPct(decision.ohTier()));
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

  private void emit(
      Loaded strategy, String exchange, String tradingsymbol, String interval, String type,
      EngineCandle bar, SignalRepository.SignalRow anchor) {
    String side = "SELL".equals(anchor.side()) ? "BUY" : "SELL"; // the closing side
    OffsetDateTime generatedAt = bar.bucketStart().withOffsetSameInstant(Ist.OFFSET);
    long id =
        signals.insert(
            strategy.versionId(), exchange, tradingsymbol, interval, type, side,
            bar.close(), null, null, anchor.compositeScore(),
            anchor.scoreBreakdown().toString(), generatedAt,
            generatedAt.plusMinutes(signalTtlMinutes));
    signals.transition(anchor.id(), "EXPIRED"); // the entry resolved — the pair is closed
    emitted.increment();
    publisher.publish(
        id, strategy.versionId(), strategy.name(), strategy.slug(), strategy.version(),
        strategy.checksum(), exchange, tradingsymbol, interval, type, side, bar.close(),
        null, null, anchor.compositeScore(), anchor.scoreBreakdown(), generatedAt);
    log.info("EXIT signal #{} {} {}:{} at {}", id, strategy.slug(), exchange, tradingsymbol,
        bar.close());
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
    if (!publishedVersionSet().equals(loadedVersionSet())) {
      log.info("reconcile: published-strategy set drifted from the engine — reloading");
      reloadRequested.set(true);
      evalExecutor.execute(this::drainReloadOnly);
    }
  }

  private String publishedVersionSet() {
    return registry.listAll().stream()
        .filter(s -> s.enabled() && s.publishedVersionId() != null)
        .map(s -> s.publishedVersionId().toString())
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
  }

  private String loadedVersionSet() {
    return loaded.stream()
        .map(l -> l.versionId().toString())
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

  private static Duration intervalDuration(String interval) {
    return switch (interval) {
      case "3m" -> Duration.ofMinutes(3);
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      default -> Duration.ofMinutes(1);
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
