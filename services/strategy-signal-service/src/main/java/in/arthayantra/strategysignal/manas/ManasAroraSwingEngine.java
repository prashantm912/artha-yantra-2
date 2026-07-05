package in.arthayantra.strategysignal.manas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.eval.ScoreBreakdownJson;
import in.arthayantra.strategyengine.eval.SeriesProvider;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalExited;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Manas Arora daily swing engine: a post-close DAILY batch that generates live-paper swing
 * signals for the published Manas-Arora {@code session.style=swing} strategies over the selection
 * funnel. It is the direct sibling of {@link
 * in.arthayantra.strategysignal.minervini.MinerviniSwingEngine} and exists for the same reason: the
 * funnel equities do NOT tick (the platform's live feed is index/options only), so the tick-driven
 * {@code SignalEngine} — which refuses non-rollable 1d primaries — never evaluates them. This batch
 * drives them off the daily bar instead.
 *
 * <p><b>Parity:</b> it reuses the FROZEN engine evaluators ({@link EntryEvaluator}/{@link
 * ExitEvaluator}/{@link IndicatorBank}) verbatim — no new scoring/exit code — so the golden vectors
 * are untouched and the batch scores each bar identically to the backtest. The per-symbol base pivot
 * is seeded exactly like the Minervini engine's context series (a flat context bar the {@code
 * VCP_PIVOT} indicator, bound to {@code MANAS_PIVOT}, reads).
 *
 * <p><b>Cross-family isolation:</b> only strategies whose {@code universe.mode == manas_arora_funnel}
 * are loaded here (the mirror of the Minervini engine's own minervini_funnel scoping) — so the two
 * swing families never evaluate each other's universe.
 *
 * <p><b>Exit doctrine (parity-safe):</b> the ~10% protective stop ({@code stop_loss percent}) + a
 * 20-day-MA close-below trail ({@code trailing_stop indicator sma20}) — a live-v1 PROXY for the
 * "+8-10% then 2×ATR trail" doctrine. The true 2×ATR trail + the too-fast/parabolic square-off are
 * modelled in the BACKTEST, not the live v1 (exactly as Minervini deferred its Stage-3/4 exits).
 *
 * <p><b>Pyramiding:</b> in the live paper ledger a same-book same-symbol re-entry AVERAGES (the
 * per-book open-key), so live pyramiding = add-on-strength-averaging; a symbol already holding an open
 * Manas swing entry is skipped for the run. The true multi-lot pyramiding is the backtest's job.
 */
@Component
public class ManasAroraSwingEngine {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraSwingEngine.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";
  private static final String IV = "1d";
  private static final String UNIVERSE_MODE = "manas_arora_funnel";
  private static final String PIVOT_CTX = "MANAS_PIVOT";

  /** A loaded published Manas swing strategy (identity + compiled definition). */
  private record SwingStrategy(
      UUID versionId, String slug, String name, String version, String checksum,
      StrategyDefinition definition) {}

  /** Summary of one batch run (for logging / the manual-trigger endpoint). */
  public record ManasSwingRun(int strategies, int candidates, int entries, int exits) {}

  private final StrategyRepository registry;
  private final ManasFunnelClient funnel;
  private final MarketDataCandlesClient candles;
  private final SignalRepository signals;
  private final SignalPublisher publisher;
  private final ApplicationEventPublisher events;
  private final Optional<EmissionGuard> emissionGuard;
  private final TransactionTemplate tx;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final boolean enabled;
  private final int warmupDays;
  private final int minBars;
  private final long ttlMinutes;

  /** Wires the registry, funnel + candle clients, the signal repo/publisher, and the event bus. */
  public ManasAroraSwingEngine(
      StrategyRepository registry,
      ManasFunnelClient funnel,
      MarketDataCandlesClient candles,
      SignalRepository signals,
      SignalPublisher publisher,
      ApplicationEventPublisher events,
      Optional<EmissionGuard> emissionGuard,
      TransactionTemplate tx,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${artha.manas-arora.swing.enabled:false}") boolean enabled,
      @Value("${artha.manas-arora.swing.warmup-days:520}") int warmupDays,
      @Value("${artha.manas-arora.swing.min-bars:60}") int minBars,
      @Value("${artha.manas-arora.swing.signal-ttl-minutes:1440}") long ttlMinutes) {
    this.registry = registry;
    this.funnel = funnel;
    this.candles = candles;
    this.signals = signals;
    this.publisher = publisher;
    this.events = events;
    this.emissionGuard = emissionGuard;
    this.tx = tx;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.enabled = enabled;
    this.warmupDays = warmupDays;
    this.minBars = minBars;
    this.ttlMinutes = ttlMinutes;
  }

  /** Runs one full daily batch: the entry pass over the funnel, then the exit pass over open swings. */
  public ManasSwingRun runDaily() {
    // The single arming gate for BOTH the scheduler AND the on-demand POST /run: with
    // artha.manas-arora.swing.enabled off the batch is a NO-OP — it can never emit a signal or open a
    // paper position, whoever calls it. The scheduler bean only exists when the flag is on, but the
    // on-demand endpoint is reachable regardless, so the engine itself must gate (else a curious
    // authenticated POST /run fires entries + auto-paper before the owner has armed the flag).
    if (!enabled) {
      log.debug("manas swing batch disabled (artha.manas-arora.swing.enabled=false) — skipping");
      return new ManasSwingRun(0, 0, 0, 0);
    }
    List<SwingStrategy> swings = loadPublishedSwingStrategies();
    if (swings.isEmpty()) {
      return new ManasSwingRun(0, 0, 0, 0);
    }
    Map<String, List<EngineCandle>> seriesCache = new HashMap<>();
    int entries = entryPass(swings, seriesCache);
    int exits = exitPass(swings, seriesCache);
    log.info("manas swing batch: {} strategies, {} entries, {} exits", swings.size(), entries, exits);
    return new ManasSwingRun(swings.size(), 0, entries, exits);
  }

  // ---- entry pass ---------------------------------------------------------------------------

  private int entryPass(List<SwingStrategy> swings, Map<String, List<EngineCandle>> seriesCache) {
    List<ManasFunnelClient.Candidate> candidates = funnel.buyableAndOnDeck();
    if (candidates.isEmpty()) {
      return 0;
    }
    // A symbol already holding an open swing entry is skipped for ALL setups this run — the paper book
    // keeps one open position per (symbol, side), so a second setup would only average in.
    Set<String> held = heldSymbols(swings);
    int fired = 0;
    for (ManasFunnelClient.Candidate c : candidates) {
      if (held.contains(c.symbol())) {
        continue;
      }
      List<EngineCandle> series = series(c.symbol(), seriesCache);
      if (series.size() < minBars) {
        continue;
      }
      for (SwingStrategy strat : swings) {
        IndicatorBank bank = buildBank(strat.definition(), c.symbol(), series, c.pivot());
        Optional<EntryEvaluator.Evaluation> eval =
            EntryEvaluator.evaluate(strat.definition(), bank, series.size() - 1);
        if (eval.isPresent() && eval.get().entry()) {
          emitEntry(strat, c, series.get(series.size() - 1), eval.get(), bank, series.size() - 1);
          held.add(c.symbol()); // one setup per symbol per run
          fired++;
          break;
        }
      }
    }
    return fired;
  }

  private Set<String> heldSymbols(List<SwingStrategy> swings) {
    Set<UUID> versions = new HashSet<>();
    swings.forEach(s -> versions.add(s.versionId()));
    Set<String> held = new HashSet<>();
    for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
      if (versions.contains(anchor.strategyVersionId())) {
        held.add(anchor.tradingsymbol());
      }
    }
    return held;
  }

  private void emitEntry(
      SwingStrategy strat, ManasFunnelClient.Candidate c, EngineCandle bar,
      EntryEvaluator.Evaluation eval, IndicatorBank bank, int index) {
    BigDecimal entryPrice = bar.close();
    ExitEvaluator.EntryLevels levels =
        ExitEvaluator.entryLevels(
            strat.definition(), bank,
            new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, index));
    BigDecimal stopLoss = levels.stopLoss();
    BigDecimal target = levels.takeProfit();
    String breakdownJson = ScoreBreakdownJson.write(eval.breakdown());
    OffsetDateTime generatedAt = bar.bucketStart().withOffsetSameInstant(IST);
    OffsetDateTime expiresAt = generatedAt.plusMinutes(ttlMinutes);
    String detailJson = manasDetailJson(strat, c);
    // The advisory (lot-rounded) size vs paper equity — REQUIRED for auto-paper: AutoPaperListener
    // skips any ENTRY whose suggested_qty is null. atr_risk sizing reads the stopDistance off the
    // percent stop. Null when the emission guard is absent (mock/no-risk contexts) — then the entry
    // simply is not auto-papered.
    BigDecimal stopDistance = stopLoss == null ? null : entryPrice.subtract(stopLoss).abs();
    // Sized against the MANAS-ARORA book's paper equity (its own capital, not the global book).
    BigDecimal suggestedQty =
        emissionGuard
            .map(
                g ->
                    g.suggestedQty(
                        strat.definition().sizing(), EX, c.symbol(), entryPrice, stopDistance,
                        Books.MANAS_ARORA))
            .orElse(null);
    long id =
        tx.execute(
            status -> {
              long newId =
                  signals.insert(
                      strat.versionId(), EX, c.symbol(), IV, "ENTRY", "BUY", entryPrice, stopLoss,
                      target, eval.breakdown().composite(), breakdownJson, generatedAt, expiresAt);
              if (suggestedQty != null) {
                signals.stampSuggestedQty(newId, suggestedQty);
              }
              signals.stampManasAroraDetail(newId, detailJson);
              return newId;
            });
    JsonNode canonical;
    try {
      canonical = objectMapper.readTree(breakdownJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("canonical breakdown unparseable", e);
    }
    publisher.publish(
        id, strat.versionId(), strat.name(), strat.slug(), strat.version(), strat.checksum(), EX,
        c.symbol(), IV, "ENTRY", "BUY", entryPrice, stopLoss, target, eval.breakdown().composite(),
        canonical, generatedAt);
    events.publishEvent(
        new SignalEmitted(
            id, strat.versionId(), EX, c.symbol(), "BUY", entryPrice, stopLoss, target,
            eval.breakdown().composite(), eval.breakdown().threshold(), null));
    log.info(
        "manas swing ENTRY #{} {} {} at {} (composite {})",
        id, strat.slug(), c.symbol(), entryPrice, eval.breakdown().composite());
  }

  private String manasDetailJson(SwingStrategy strat, ManasFunnelClient.Candidate c) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("setup", strat.slug());
    if (c.setupType() != null) {
      root.put("setupType", c.setupType());
    }
    if (c.footprint() != null) {
      root.put("footprint", c.footprint());
    }
    if (c.pivot() != null) {
      root.put("pivot", c.pivot().toPlainString());
    }
    return root.toString();
  }

  // ---- exit pass ----------------------------------------------------------------------------

  private int exitPass(List<SwingStrategy> swings, Map<String, List<EngineCandle>> seriesCache) {
    Map<UUID, SwingStrategy> byVersion = new HashMap<>();
    swings.forEach(s -> byVersion.put(s.versionId(), s));
    int closed = 0;
    for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
      SwingStrategy strat = byVersion.get(anchor.strategyVersionId());
      if (strat == null) {
        continue; // not a published manas swing anchor
      }
      List<EngineCandle> series = series(anchor.tradingsymbol(), seriesCache);
      if (series.isEmpty()) {
        continue;
      }
      // geometry is irrelevant to the exit rules (percent stop + 20-day-MA trail), so the pivot
      // context is seeded neutral — the bank still builds, the exit eval never reads it.
      IndicatorBank bank = buildBank(strat.definition(), anchor.tradingsymbol(), series, BigDecimal.ZERO);
      int entryIndex = bank.primarySeries().indexAtOrBefore(anchor.generatedAt().toInstant());
      if (entryIndex < 0) {
        // the entry bar fell outside the fetched window (position held > warmupDays, or a dropped
        // entry bucket) — any entry-relative exit distance would be unreliable, so skip this anchor
        // rather than default to bar 0. The shipped exits (percent stop + indicator trail) do not read
        // entryIndex, but a future time_stop / atr_multiple rule would silently mis-compute from bar 0.
        log.warn(
            "manas swing exit: entry bar for #{} {} is outside the fetched window — skipped",
            anchor.id(), anchor.tradingsymbol());
        continue;
      }
      Optional<ExitEvaluator.ExitDecision> exit =
          ExitEvaluator.evaluate(
              strat.definition(), bank,
              new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, anchor.entryPrice(), entryIndex),
              series.size() - 1);
      if (exit.isPresent()) {
        emitExit(strat, anchor, series.get(series.size() - 1), exit.get());
        closed++;
      }
    }
    return closed;
  }

  private void emitExit(
      SwingStrategy strat, SignalRepository.SignalRow anchor, EngineCandle bar,
      ExitEvaluator.ExitDecision exit) {
    OffsetDateTime generatedAt = bar.bucketStart().withOffsetSameInstant(IST);
    // the paper close_reason taxonomy is UPPERCASE (STOP_LOSS / TRAILING_STOP / SIGNAL_EXIT / …)
    String reason = exit.type().toUpperCase(java.util.Locale.ROOT);
    long id =
        tx.execute(
            status -> {
              long newId =
                  signals.insert(
                      strat.versionId(), EX, anchor.tradingsymbol(), IV, "EXIT", "SELL", bar.close(),
                      null, null, anchor.compositeScore(), anchor.scoreBreakdown().toString(),
                      generatedAt, generatedAt.plusMinutes(ttlMinutes));
              signals.transition(anchor.id(), "EXPIRED");
              return newId;
            });
    publisher.publish(
        id, strat.versionId(), strat.name(), strat.slug(), strat.version(), strat.checksum(), EX,
        anchor.tradingsymbol(), IV, "EXIT", "SELL", bar.close(), null, null, anchor.compositeScore(),
        anchor.scoreBreakdown(), generatedAt);
    // settle the paper position at the fresh DAILY-BAR close — the equities don't tick, so an LTP
    // close would book breakeven. The price rides the SignalExited event to closeForSignal.
    events.publishEvent(new SignalExited(anchor.id(), id, reason, bar.close()));
    log.info(
        "manas swing EXIT #{} {} {} at {} ({})",
        id, strat.slug(), anchor.tradingsymbol(), bar.close(), reason);
  }

  // ---- shared helpers -----------------------------------------------------------------------

  /**
   * Builds the indicator bank for one symbol over its daily {@code series} with the per-symbol base
   * pivot seeded as a flat context series ({@code MANAS_PIVOT}, read by the {@code VCP_PIVOT}
   * indicator) — the same context-close mechanism the golden runner uses. Package-visible for tests.
   */
  static IndicatorBank buildBank(
      StrategyDefinition definition, String symbol, List<EngineCandle> series, BigDecimal pivot) {
    SeriesKey primaryKey = new SeriesKey(EX, symbol, definition.primaryTimeframe());
    EngineSeries primary = new EngineSeries(primaryKey);
    for (EngineCandle candle : series) {
      primary.append(candle);
    }
    OffsetDateTime start = series.get(0).bucketStart();
    EngineSeries pivotCtx = flat(PIVOT_CTX, start, pivot);
    SeriesProvider provider =
        key -> {
          if (key.equals(primaryKey)) {
            return primary;
          }
          return PIVOT_CTX.equals(key.tradingsymbol()) ? pivotCtx : null;
        };
    return IndicatorBank.build(
        definition, new StrategyDefinition.InstrumentRef(EX, symbol), provider);
  }

  /** A single flat context bar at {@code at} carrying {@code value} (null → 0) — read by contextLevel. */
  private static EngineSeries flat(String symbol, OffsetDateTime at, BigDecimal value) {
    EngineSeries s = new EngineSeries(new SeriesKey(EX, symbol, IV));
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    s.append(new EngineCandle(at, v, v, v, v, 0L, null));
    return s;
  }

  private List<EngineCandle> series(String symbol, Map<String, List<EngineCandle>> cache) {
    return cache.computeIfAbsent(
        symbol,
        s -> {
          OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
          return candles.fetch(EX, s, IV, now.minusDays(warmupDays), now);
        });
  }

  private List<SwingStrategy> loadPublishedSwingStrategies() {
    List<SwingStrategy> out = new ArrayList<>();
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
        if (!"swing".equals(definition.session().style())) {
          continue;
        }
        // Scope this engine to its OWN funnel universe: only manas_arora_funnel strategies are
        // evaluated against the Manas funnel here (the mirror of the Minervini engine's own scoping),
        // so the two swing families never evaluate each other's universe.
        if (!UNIVERSE_MODE.equals(
            versionRow.get().config().path("universe").path("mode").asText())) {
          continue;
        }
        out.add(
            new SwingStrategy(
                strategy.publishedVersionId(), strategy.slug(), strategy.name(),
                versionRow.get().version(), versionRow.get().checksum(), definition));
      } catch (RuntimeException e) {
        log.warn("manas swing strategy {} failed to compile — skipped: {}",
            strategy.slug(), e.getMessage());
      }
    }
    return out;
  }
}
