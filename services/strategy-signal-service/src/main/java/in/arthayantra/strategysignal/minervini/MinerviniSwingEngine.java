package in.arthayantra.strategysignal.minervini;

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
import in.arthayantra.strategysignal.signals.EmissionGuard;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Phase-9 (MV-9.1) Minervini swing engine: a post-close DAILY batch that generates live-paper
 * swing signals for the published {@code session.style=swing} strategies over the SEPA-funnel
 * universe. It exists because the funnel equities do NOT tick (the platform's live feed is
 * index/options only), so the tick-driven {@code SignalEngine} — which refuses non-rollable 1d
 * primaries — never evaluates them. This batch drives them off the daily bar instead.
 *
 * <p><b>Parity:</b> it reuses the FROZEN engine evaluators ({@link EntryEvaluator}/{@link
 * ExitEvaluator}/{@link IndicatorBank}) verbatim — no new scoring/exit code — so the golden vectors
 * are untouched and the batch scores each bar identically to the backtest. The per-symbol VCP
 * geometry (pivot / cheat / thrust) is seeded exactly like the golden runner's context series (a
 * flat context bar the {@code VCP_PIVOT}/{@code CHEAT_PIVOT}/{@code THRUST} indicators read).
 *
 * <p>Emission mirrors {@code SignalEngine.emitEntry}/{@code emit}: an ENTRY row + {@code SignalEmitted}
 * (auto-papered by {@link in.arthayantra.strategysignal.paper.AutoPaperListener}), and on an exit an
 * EXIT row + {@code SignalExited} (closes the paper position). Swing positions are never squared off
 * intraday (the 15:45 mark-to-close filters {@code style='intraday'}); the exit doctrine (8% stop +
 * 50-day-MA trail) is checked here on the fresh daily bar.
 */
@Component
public class MinerviniSwingEngine {

  private static final Logger log = LoggerFactory.getLogger(MinerviniSwingEngine.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";
  private static final String IV = "1d";

  /** A loaded published swing strategy (identity + compiled definition). */
  private record SwingStrategy(
      UUID versionId, String slug, String name, String version, String checksum,
      StrategyDefinition definition) {}

  /**
   * Summary of one batch run (for logging / the manual-trigger endpoint). {@code exitSkipped}
   * counts held anchors whose stop/trail could NOT be evaluated this run (no daily series even
   * after a retry) — this batch is the position's ONLY exit evaluator, so a non-zero value is an
   * ops signal, never routine.
   */
  public record SwingRun(int strategies, int candidates, int entries, int exits, int exitSkipped) {}

  /** Entry-pass outcome: how many funnel candidates were scanned + how many entries fired. */
  private record EntryResult(int candidates, int fired) {}

  /** Exit-pass outcome: closed anchors + held anchors whose exit could NOT be evaluated. */
  private record ExitResult(int closed, int skipped) {}

  private final StrategyRepository registry;
  private final MinerviniFunnelClient funnel;
  private final in.arthayantra.strategysignal.signals.MarketDataCandlesClient candles;
  private final SignalRepository signals;
  private final SignalPublisher publisher;
  private final org.springframework.context.ApplicationEventPublisher events;
  private final java.util.Optional<EmissionGuard> emissionGuard;
  private final TransactionTemplate tx;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final boolean enabled;
  private final int warmupDays;
  private final int minBars;
  private final long ttlMinutes;

  /** Wires the registry, funnel + candle clients, the signal repo/publisher, and the event bus. */
  public MinerviniSwingEngine(
      StrategyRepository registry,
      MinerviniFunnelClient funnel,
      in.arthayantra.strategysignal.signals.MarketDataCandlesClient candles,
      SignalRepository signals,
      SignalPublisher publisher,
      org.springframework.context.ApplicationEventPublisher events,
      java.util.Optional<EmissionGuard> emissionGuard,
      TransactionTemplate tx,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${artha.minervini.swing.enabled:false}") boolean enabled,
      @Value("${artha.minervini.swing.warmup-days:520}") int warmupDays,
      @Value("${artha.minervini.swing.min-bars:60}") int minBars,
      @Value("${artha.minervini.swing.signal-ttl-minutes:1440}") long ttlMinutes) {
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
  public SwingRun runDaily() {
    // The single arming gate for BOTH the scheduler AND the on-demand POST /run: with
    // artha.minervini.swing.enabled off the batch is a NO-OP — it can never emit a signal or open a
    // paper position, whoever calls it. The scheduler bean only exists when the flag is on, but the
    // on-demand endpoint is reachable regardless, so the engine itself must gate (audit P9 — else a
    // curious authenticated POST /run fires entries + auto-paper before the owner has armed the flag).
    if (!enabled) {
      log.debug("minervini swing batch disabled (artha.minervini.swing.enabled=false) — skipping");
      return new SwingRun(0, 0, 0, 0, 0);
    }
    List<SwingStrategy> swings = loadPublishedSwingStrategies();
    if (swings.isEmpty()) {
      return new SwingRun(0, 0, 0, 0, 0);
    }
    Map<String, List<EngineCandle>> seriesCache = new HashMap<>();
    AnchorResolution resolution = new AnchorResolution(swings);
    EntryResult entryResult = entryPass(swings, resolution, seriesCache);
    ExitResult exitResult = exitPass(resolution, seriesCache);
    log.info(
        "minervini swing batch: {} strategies, {} candidates, {} entries, {} exits,"
            + " {} exit-skipped",
        swings.size(), entryResult.candidates(), entryResult.fired(), exitResult.closed(),
        exitResult.skipped());
    return new SwingRun(
        swings.size(), entryResult.candidates(), entryResult.fired(), exitResult.closed(),
        exitResult.skipped());
  }

  // ---- anchor resolution (audit H2) -----------------------------------------------------------

  /**
   * Per-run anchor→strategy resolution that ADOPTS anchors of superseded/unpublished versions.
   * Keying the exit pass on the CURRENT published version id silently orphaned every open anchor
   * whenever a strategy was republished (the boot seeder re-syncs YAML → new draft → publish is
   * routine), disabled, or its config later failed to compile — the position's stop/trail was
   * never evaluated again AND the symbol dropped out of heldSymbols, so a new version could
   * re-enter it (double exposure). Resolution order: currently-published strategy, else the
   * anchor's OWN version row compiled on the fly (its frozen config — the doctrine the position
   * was opened under). Empty = another family's anchor (its own engine manages it) or genuinely
   * unmanageable (logged at ERROR).
   */
  private final class AnchorResolution {
    private final Map<UUID, SwingStrategy> published = new HashMap<>();
    private final Map<UUID, Optional<SwingStrategy>> adopted = new HashMap<>();

    AnchorResolution(List<SwingStrategy> swings) {
      swings.forEach(s -> published.put(s.versionId(), s));
    }

    Optional<SwingStrategy> resolve(UUID versionId) {
      SwingStrategy current = published.get(versionId);
      if (current != null) {
        return Optional.of(current);
      }
      return adopted.computeIfAbsent(versionId, MinerviniSwingEngine.this::adoptVersion);
    }
  }

  /**
   * Adopts one superseded/unpublished version for exit management. The strategy's {@code enabled}
   * flag is deliberately NOT checked — a disabled strategy no longer ENTERS (the published load
   * skips it) but its open positions must still be exit-managed to their close.
   */
  private Optional<SwingStrategy> adoptVersion(UUID versionId) {
    Optional<StrategyRepository.VersionRow> versionRow = registry.findVersionById(versionId);
    if (versionRow.isEmpty()) {
      log.error(
          "minervini swing: anchor version {} has no version row — OPEN POSITION UNMANAGED",
          versionId);
      return Optional.empty();
    }
    JsonNode config = versionRow.get().config();
    if (!"minervini_funnel".equals(config.path("universe").path("mode").asText())) {
      return Optional.empty(); // another family's anchor — its own engine manages it
    }
    try {
      StrategyDefinition definition = StrategyCompiler.compile(config);
      if (!"swing".equals(definition.session().style())) {
        return Optional.empty();
      }
      StrategyRepository.StrategyRow strategy =
          registry.findById(versionRow.get().strategyId()).orElse(null);
      if (strategy == null) {
        log.error(
            "minervini swing: strategy for anchor version {} not found — OPEN POSITION UNMANAGED",
            versionId);
        return Optional.empty();
      }
      log.warn(
          "minervini swing: adopting anchors of superseded version {} of {} — exit-managed with"
              + " the version's own frozen config",
          versionRow.get().version(), strategy.slug());
      return Optional.of(
          new SwingStrategy(
              versionId, strategy.slug(), strategy.name(), versionRow.get().version(),
              versionRow.get().checksum(), definition));
    } catch (RuntimeException e) {
      log.error(
          "minervini swing: anchor version {} failed to compile — OPEN POSITION UNMANAGED: {}",
          versionId, e.getMessage());
      return Optional.empty();
    }
  }

  // ---- entry pass ---------------------------------------------------------------------------

  private EntryResult entryPass(
      List<SwingStrategy> swings,
      AnchorResolution resolution,
      Map<String, List<EngineCandle>> seriesCache) {
    // Per-book risk governor early-out: a book already kill-switched / daily-loss-tripped at the
    // START of the run takes no entries at all (cheap — skips the whole candidate scan).
    if (entryBlocked()) {
      return new EntryResult(0, 0);
    }
    List<MinerviniFunnelClient.Candidate> candidates = funnel.buyableAndOnDeck();
    if (candidates.isEmpty()) {
      return new EntryResult(0, 0);
    }
    // A symbol already holding an open swing entry is skipped for ALL setups this run — the paper
    // book keeps one open position per (symbol, side), so a second setup would only average in.
    Set<String> held = heldSymbols(resolution);
    int fired = 0;
    for (MinerviniFunnelClient.Candidate c : candidates) {
      if (held.contains(c.symbol())) {
        continue;
      }
      List<EngineCandle> series = series(c.symbol(), seriesCache);
      if (series.size() < minBars) {
        continue;
      }
      for (SwingStrategy strat : swings) {
        IndicatorBank bank =
            buildBank(strat.definition(), c.symbol(), series, c.pivot(), c.cheatPivot(), c.thrust());
        Optional<EntryEvaluator.Evaluation> eval =
            EntryEvaluator.evaluate(strat.definition(), bank, series.size() - 1);
        if (eval.isPresent() && eval.get().entry()) {
          // Audit H3: re-check the per-book gate BEFORE each emit, not once per pass. Auto-paper is
          // a synchronous @EventListener, so positions opened earlier THIS run are already counted
          // — so max_open / max_deployment_pct / daily_loss actually bound a single batch. Without
          // this, a broad-breakout day fired every funnel candidate (>100% of the book) in one run.
          if (entryBlocked()) {
            log.info(
                "minervini swing: MINERVINI book gate tripped mid-run after {} entries — stopping",
                fired);
            return new EntryResult(candidates.size(), fired);
          }
          emitEntry(strat, c, series.get(series.size() - 1), eval.get(), bank, series.size() - 1);
          held.add(c.symbol()); // one setup per symbol per run
          fired++;
          break;
        }
      }
    }
    return new EntryResult(candidates.size(), fired);
  }

  /** True when the MINERVINI book's per-book gate (kill-switch / caps / daily-loss) blocks entry. */
  private boolean entryBlocked() {
    return emissionGuard
        .map(g -> !g.entryAllowed(in.arthayantra.strategysignal.signals.Books.MINERVINI))
        .orElse(false);
  }

  private Set<String> heldSymbols(AnchorResolution resolution) {
    // Resolution (not a current-published-version set) so a symbol held by a SUPERSEDED version's
    // anchor still blocks re-entry — the audit-H2 double-exposure path.
    Set<String> held = new HashSet<>();
    for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
      if (resolution.resolve(anchor.strategyVersionId()).isPresent()) {
        held.add(anchor.tradingsymbol());
      }
    }
    return held;
  }

  private void emitEntry(
      SwingStrategy strat, MinerviniFunnelClient.Candidate c, EngineCandle bar,
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
    String detailJson = minerviniDetailJson(strat, c);
    // The advisory (lot-rounded) size vs paper equity — REQUIRED for auto-paper: AutoPaperListener
    // skips any ENTRY whose suggested_qty is null. Computed before the tx (it reads the instrument
    // master / paper equity) so the row + its stamps commit in one tight transaction. Null when the
    // emission guard is absent (mock/no-risk contexts) — then the entry simply is not auto-papered.
    BigDecimal stopDistance = stopLoss == null ? null : entryPrice.subtract(stopLoss).abs();
    // Sized against the MINERVINI book's paper equity (its own ₹1.5 L capital, not the global book).
    BigDecimal suggestedQty =
        emissionGuard
            .map(
                g ->
                    g.suggestedQty(
                        strat.definition().sizing(), EX, c.symbol(), entryPrice, stopDistance,
                        in.arthayantra.strategysignal.signals.Books.MINERVINI))
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
              signals.stampMinerviniDetail(newId, detailJson);
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
        "minervini swing ENTRY #{} {} {} at {} (composite {})",
        id, strat.slug(), c.symbol(), entryPrice, eval.breakdown().composite());
  }

  private String minerviniDetailJson(SwingStrategy strat, MinerviniFunnelClient.Candidate c) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("setup", strat.slug());
    if (c.stage() != null) {
      root.put("stage", c.stage());
    }
    if (c.footprint() != null) {
      root.put("footprint", c.footprint());
    }
    if (c.pivot() != null) {
      root.put("pivot", c.pivot().toPlainString());
    }
    if (c.cheatPivot() != null) {
      root.put("cheatPivot", c.cheatPivot().toPlainString());
    }
    root.put("thrust", c.thrust());
    return root.toString();
  }

  // ---- exit pass ----------------------------------------------------------------------------

  private ExitResult exitPass(
      AnchorResolution resolution, Map<String, List<EngineCandle>> seriesCache) {
    int closed = 0;
    int skipped = 0;
    for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
      SwingStrategy strat = resolution.resolve(anchor.strategyVersionId()).orElse(null);
      if (strat == null) {
        continue; // another family's anchor, or unmanageable (already logged at ERROR)
      }
      List<EngineCandle> series = series(anchor.tradingsymbol(), seriesCache);
      if (series.isEmpty()) {
        // One retry OUTSIDE the per-run cache: MarketDataCandlesClient fail-softs to an empty list
        // on any REST hiccup and the cache pins it — silently skipping the position's ONLY daily
        // stop/trail evaluation (audit P0-3). A held anchor's series is worth a second round-trip.
        series = retryFetch(anchor.tradingsymbol(), seriesCache);
      }
      if (series.isEmpty()) {
        log.error(
            "minervini swing exit: no daily series for held anchor #{} {} even after retry —"
                + " STOP NOT EVALUATED TODAY",
            anchor.id(), anchor.tradingsymbol());
        skipped++;
        continue;
      }
      // geometry is irrelevant to the exit rules (percent stop + 50-day-MA trail), so the context
      // sentinels are seeded neutral — the bank still builds, the exit eval never reads them.
      IndicatorBank bank =
          buildBank(strat.definition(), anchor.tradingsymbol(), series, BigDecimal.ZERO,
              BigDecimal.ZERO, false);
      int entryIndex = bank.primarySeries().indexAtOrBefore(anchor.generatedAt().toInstant());
      if (entryIndex < 0) {
        // the entry bar fell outside the fetched window (position held > warmupDays, or a dropped
        // entry bucket) — any entry-relative exit distance would be unreliable, so skip this anchor
        // rather than default to bar 0. The shipped exits (percent stop + indicator trail) do not read
        // entryIndex, but a future time_stop / atr_multiple rule would silently mis-compute from bar 0.
        log.error(
            "minervini swing exit: entry bar for #{} {} is outside the fetched window —"
                + " STOP NOT EVALUATED TODAY",
            anchor.id(), anchor.tradingsymbol());
        skipped++;
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
    return new ExitResult(closed, skipped);
  }

  /**
   * Re-fetches a held anchor's daily series once, bypassing (and on success repairing) the per-run
   * cache — the fail-soft empty from a transient REST hiccup must not stand between an open
   * position and its only daily stop evaluation.
   */
  private List<EngineCandle> retryFetch(String symbol, Map<String, List<EngineCandle>> cache) {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
    List<EngineCandle> fresh = candles.fetch(EX, symbol, IV, now.minusDays(warmupDays), now);
    if (!fresh.isEmpty()) {
      cache.put(symbol, fresh);
    }
    return fresh;
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
    // close would book breakeven (DEFECT-2). The price rides the SignalExited event to closeForSignal.
    events.publishEvent(new SignalExited(anchor.id(), id, reason, bar.close()));
    log.info(
        "minervini swing EXIT #{} {} {} at {} ({})",
        id, strat.slug(), anchor.tradingsymbol(), bar.close(), reason);
  }

  // ---- shared helpers -----------------------------------------------------------------------

  /**
   * Builds the indicator bank for one symbol over its daily {@code series} with the per-symbol VCP
   * geometry seeded as flat context series ({@code VCP_PIVOT}/{@code CHEAT_PIVOT}/{@code THRUST}) —
   * the same context-close mechanism the golden runner uses. Package-visible for unit tests.
   */
  static IndicatorBank buildBank(
      StrategyDefinition definition, String symbol, List<EngineCandle> series,
      BigDecimal pivot, BigDecimal cheat, boolean thrust) {
    SeriesKey primaryKey = new SeriesKey(EX, symbol, definition.primaryTimeframe());
    EngineSeries primary = new EngineSeries(primaryKey);
    for (EngineCandle candle : series) {
      primary.append(candle);
    }
    OffsetDateTime start = series.get(0).bucketStart();
    EngineSeries pivotCtx = flat("MINERVINI_PIVOT", start, pivot);
    EngineSeries cheatCtx = flat("MINERVINI_CHEAT", start, cheat);
    EngineSeries thrustCtx = flat("MINERVINI_THRUST", start, thrust ? BigDecimal.ONE : BigDecimal.ZERO);
    SeriesProvider provider =
        key -> {
          if (key.equals(primaryKey)) {
            return primary;
          }
          return switch (key.tradingsymbol()) {
            case "MINERVINI_PIVOT" -> pivotCtx;
            case "MINERVINI_CHEAT" -> cheatCtx;
            case "MINERVINI_THRUST" -> thrustCtx;
            default -> null;
          };
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
        // Scope this engine to its OWN funnel universe: only strategies whose universe.mode is
        // minervini_funnel are evaluated against the SEPA funnel here. A NO-OP for the 4 existing
        // Minervini setups (all minervini_funnel), it stops a sibling swing family (e.g. the
        // manas_arora_funnel strategies, also session.style=swing) from being wrongly evaluated
        // against the Minervini funnel — each funnel engine owns exactly its own universe mode.
        if (!"minervini_funnel"
            .equals(versionRow.get().config().path("universe").path("mode").asText())) {
          continue;
        }
        out.add(
            new SwingStrategy(
                strategy.publishedVersionId(), strategy.slug(), strategy.name(),
                versionRow.get().version(), versionRow.get().checksum(), definition));
      } catch (RuntimeException e) {
        log.warn("minervini swing strategy {} failed to compile — skipped: {}",
            strategy.slug(), e.getMessage());
      }
    }
    return out;
  }
}
