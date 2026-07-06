package in.arthayantra.strategysignal.manas;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The Manas Arora daily sell-decision triad — for every open Manas swing position, the "would I buy it
 * now / where am I a seller / am I a seller today" read. The direct sibling of {@code
 * MinerviniSellDecisionService}: reuses the engine's per-symbol bank build ({@link
 * ManasAroraSwingEngine#buildBank}) + the FROZEN evaluators over each held anchor's fresh daily series,
 * with the persisted base pivot from its {@code manas_arora_detail}. Read-only — it never emits a
 * signal or moves the book; the owner reads it to manage the holdings.
 */
@Service
public class ManasAroraSellDecisionService {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraSellDecisionService.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";
  private static final String UNIVERSE_MODE = "manas_arora_funnel";

  /** One holding's triad: the buy-now check, the hold status, and where the exits sit. */
  public record ManasSellDecision(
      String symbol,
      String setup,
      String setupType,
      String footprint,
      BigDecimal entryPrice,
      BigDecimal currentPrice,
      BigDecimal unrealizedPct,
      BigDecimal stopLevel,
      BigDecimal trailLevel,
      boolean stillBuyable,
      boolean sellingNow,
      String sellReason,
      String verdict) {}

  /** The {items} envelope. */
  public record ManasSellReport(OffsetDateTime asOf, List<ManasSellDecision> items) {}

  private final StrategyRepository registry;
  private final MarketDataCandlesClient candles;
  private final SignalRepository signals;
  private final Clock clock;
  private final int warmupDays;

  /** Wires the registry, candle client, and signal repo. */
  public ManasAroraSellDecisionService(
      StrategyRepository registry,
      MarketDataCandlesClient candles,
      SignalRepository signals,
      Clock clock,
      @Value("${artha.manas-arora.swing.warmup-days:520}") int warmupDays) {
    this.registry = registry;
    this.candles = candles;
    this.signals = signals;
    this.clock = clock;
    this.warmupDays = warmupDays;
  }

  /** Builds the sell-decision triad for every open Manas swing position. */
  public ManasSellReport report() {
    Map<UUID, StrategyDefinition> swings = loadSwingDefs();
    Map<UUID, Optional<StrategyDefinition>> adopted = new HashMap<>();
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
    List<ManasSellDecision> items = new ArrayList<>();
    for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
      StrategyDefinition def = swings.get(anchor.strategyVersionId());
      if (def == null) {
        // Adopt a superseded/unpublished version's anchor (audit H2, mirror of the engine): the
        // triad must keep showing holdings whose strategy was republished/disabled — the owner
        // manages them off this report, so a vanishing row IS the failure.
        def = adopted.computeIfAbsent(anchor.strategyVersionId(), this::adoptDef).orElse(null);
      }
      if (def == null) {
        continue; // another family's anchor, or unmanageable
      }
      try {
        items.add(decide(def, anchor, now));
      } catch (RuntimeException e) {
        log.warn("manas sell-decision for {} skipped: {}", anchor.tradingsymbol(), e.getMessage());
      }
    }
    return new ManasSellReport(now, items);
  }

  /** Compiles a superseded/unpublished version's own frozen config (family-scoped). */
  private Optional<StrategyDefinition> adoptDef(UUID versionId) {
    return registry
        .findVersionById(versionId)
        .filter(v -> UNIVERSE_MODE.equals(v.config().path("universe").path("mode").asText()))
        .flatMap(
            v -> {
              try {
                StrategyDefinition def = StrategyCompiler.compile(v.config());
                return "swing".equals(def.session().style()) ? Optional.of(def) : Optional.empty();
              } catch (RuntimeException e) {
                log.warn(
                    "manas sell-decision: anchor version {} failed to compile — skipped: {}",
                    versionId, e.getMessage());
                return Optional.empty();
              }
            });
  }

  private ManasSellDecision decide(
      StrategyDefinition def, SignalRepository.SignalRow anchor, OffsetDateTime now) {
    JsonNode detail = anchor.manasAroraDetail();
    BigDecimal pivot = decimal(detail, "pivot");
    List<EngineCandle> series =
        candles.fetch(EX, anchor.tradingsymbol(), "1d", now.minusDays(warmupDays), now);
    if (series.isEmpty()) {
      throw new IllegalStateException("no daily series");
    }
    int last = series.size() - 1;
    // Seed the persisted base pivot into the context the anchor's setup reads (the breakout strategy's
    // crossover binds to MANAS_BREAKOUT_PIVOT, the VCP's to MANAS_VCP_PIVOT). The detail's "setup" field
    // is the emitting strategy's slug (manas-arora-breakout / -vcp); "vcp"-suffixed → the VCP slot.
    boolean vcp = text(detail, "setup") != null && text(detail, "setup").endsWith("-vcp");
    IndicatorBank bank =
        ManasAroraSwingEngine.buildBank(
            def, anchor.tradingsymbol(), series,
            pivot, vcp ? null : pivot, vcp ? pivot : null);
    BigDecimal entryPrice = anchor.entryPrice();
    BigDecimal currentPrice = series.get(last).close();
    BigDecimal unrealizedPct =
        entryPrice == null || entryPrice.signum() == 0
            ? null
            : currentPrice.subtract(entryPrice).divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

    int entryIndex = bank.primarySeries().indexAtOrBefore(anchor.generatedAt().toInstant());
    // would I buy it now? — the entry gate re-evaluated on today's bar
    boolean stillBuyable =
        EntryEvaluator.evaluate(def, bank, last).map(EntryEvaluator.Evaluation::entry).orElse(false);
    // where am I a seller? — the entry-time stop + the CURRENT trail level. The Manas doctrine trail
    // is 2×ATR below the high-water since entry (armed at +9%), NOT sma20 (audit M33 — sma20 is only
    // the entry gate). trailStop mirrors the frozen exit arithmetic, so this equals the price the
    // live trailing_stop fires on; null until the trail arms.
    ExitEvaluator.Position pos =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, Math.max(entryIndex, 0));
    BigDecimal stopLevel = ExitEvaluator.entryLevels(def, bank, pos).stopLoss();
    // The trail level scans the high-water over [entryIndex..last], so it is only meaningful when the
    // entry bar is still in the window — with the entry bar gone a clamped index 0 would report a
    // trail off the whole warmup peak. Skip it then (same guard as the sellingNow block below).
    BigDecimal trailLevel =
        entryIndex >= 0 ? ExitEvaluator.trailStop(def, bank, pos, last).orElse(null) : null;
    // am I a seller today? — the frozen exit doctrine on today's bar (skipped if the entry bar is gone)
    boolean sellingNow = false;
    String sellReason = null;
    if (entryIndex >= 0) {
      Optional<ExitEvaluator.ExitDecision> exit =
          ExitEvaluator.evaluate(
              def, bank,
              new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, entryIndex), last);
      sellingNow = exit.isPresent();
      sellReason = exit.map(ExitEvaluator.ExitDecision::type).orElse(null);
    }
    String verdict = sellingNow ? "SELL (" + sellReason + ")" : "HOLD";
    return new ManasSellDecision(
        anchor.tradingsymbol(), text(detail, "setup"), text(detail, "setupType"),
        text(detail, "footprint"), entryPrice, currentPrice, unrealizedPct, stopLevel, trailLevel,
        stillBuyable, sellingNow, sellReason, verdict);
  }

  private Map<UUID, StrategyDefinition> loadSwingDefs() {
    Map<UUID, StrategyDefinition> out = new HashMap<>();
    for (StrategyRepository.StrategyRow strategy : registry.listAll()) {
      if (!strategy.enabled() || strategy.publishedVersionId() == null) {
        continue;
      }
      registry
          .findVersionById(strategy.publishedVersionId())
          .ifPresent(
              v -> {
                try {
                  StrategyDefinition def = StrategyCompiler.compile(v.config());
                  // scoped to this family's own universe mode, so the triad never lists another
                  // swing family's holdings (the mirror of the engine's own scoping).
                  if ("swing".equals(def.session().style())
                      && UNIVERSE_MODE.equals(v.config().path("universe").path("mode").asText())) {
                    out.put(strategy.publishedVersionId(), def);
                  }
                } catch (RuntimeException ignored) {
                  // a bad config is skipped — the batch logs it on its own load
                }
              });
    }
    return out;
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? new BigDecimal(node.path(field).asText()) : null;
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
  }
}
