package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.eval.ScoreBreakdownJson;
import in.arthayantra.strategyengine.golden.TickwiseGoldenRunner;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.registry.StrategyRepository;
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

  /** One loaded (strategy, version) with its resolved universe. */
  record Loaded(
      UUID strategyId,
      UUID versionId,
      String slug,
      String name,
      String version,
      String checksum,
      StrategyDefinition definition,
      List<StrategyDefinition.InstrumentRef> universe,
      Set<SeriesKey> seriesKeys) {}

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
        StrategyDefinition definition = StrategyCompiler.compile(versionRow.get().config());
        List<StrategyDefinition.InstrumentRef> universe =
            resolveUniverse(versionRow.get().config());
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
        keys.forEach(seriesStore::ensureWarm);
        fresh.add(
            new Loaded(
                strategy.id(), versionRow.get().id(), strategy.slug(), strategy.name(),
                versionRow.get().version(), versionRow.get().checksum(), definition,
                universe, keys));
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
        log.warn(
            "options_of_underlying universes evaluate from Stage F (chain-driven resolution); "
                + "strategy stays unloaded");
        yield List.of();
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
        emitEntry(strategy, exchange, tradingsymbol, interval, bar, evaluation.get());
      }
    }
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
          evaluation.get());
    }
  }

  private void emitEntry(
      Loaded strategy, String exchange, String tradingsymbol, String interval, EngineCandle bar,
      EntryEvaluator.Evaluation evaluation) {
    BigDecimal entryPrice = bar.close();
    BigDecimal stopLoss = levelFromRules(strategy.definition(), entryPrice, "stop_loss");
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
    // in-process trigger for the Phase-41 notifier (push only ENTRY signals)
    events.publishEvent(
        new SignalEmitted(
            id, strategy.versionId(), exchange, tradingsymbol, side, entryPrice, stopLoss, target,
            evaluation.breakdown().composite(), evaluation.breakdown().threshold()));
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

  private static Duration intervalDuration(String interval) {
    return switch (interval) {
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      default -> Duration.ofMinutes(1);
    };
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
