package in.arthayantra.strategysignal.swing;

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
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.springframework.stereotype.Service;

/**
 * The one daily sell-decision triad for every swing family (audit M31 consolidation): for each open
 * holding, the "would I buy it now / why am I holding / where am I a seller" read. Reuses the shared
 * {@link SwingBatchEngine#buildBank} + the FROZEN evaluators over the held anchor's fresh daily series,
 * seeded from the persisted {@code *_detail} via {@link SwingDoctrine#contextSeedsFromDetail}. Read-only
 * — it never emits a signal or moves the book; the owner reads it to manage the holdings.
 */
@Service
public class SwingSellDecisionService {

  private static final Logger log = LoggerFactory.getLogger(SwingSellDecisionService.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";
  private static final String IV = "1d";

  /**
   * One holding's triad. Carries BOTH families' descriptors ({@code stage} for Minervini, {@code
   * setupType} for Manas — the other is null), so the FE renders either book from one shape.
   */
  public record SwingSellDecision(
      long signalId,
      String symbol,
      @Schema(types = {"string", "null"}) String setup,
      @Schema(types = {"integer", "null"}) Integer stage,
      @Schema(types = {"string", "null"}) String setupType,
      @Schema(types = {"string", "null"}) String footprint,
      @Schema(types = {"number", "null"}) BigDecimal entryPrice,
      BigDecimal currentPrice,
      @Schema(types = {"number", "null"}) BigDecimal unrealizedPct,
      @Schema(types = {"number", "null"}) BigDecimal stopLevel,
      @Schema(types = {"number", "null"}) BigDecimal trailLevel,
      boolean stillBuyable,
      boolean sellingNow,
      @Schema(types = {"string", "null"}) String sellReason,
      String verdict) {}

  /** The {asOf, items} envelope. */
  public record SwingSellReport(OffsetDateTime asOf, List<SwingSellDecision> items) {}

  private final StrategyRepository registry;
  private final MarketDataCandlesClient candles;
  private final SignalRepository signals;
  private final SellDecisionRepository sellDecisions;
  private final Clock clock;

  /** Wires the registry, candle client, signal repo + the durable sell-decision store (family-neutral). */
  public SwingSellDecisionService(
      StrategyRepository registry, MarketDataCandlesClient candles, SignalRepository signals,
      SellDecisionRepository sellDecisions, Clock clock) {
    this.registry = registry;
    this.candles = candles;
    this.signals = signals;
    this.sellDecisions = sellDecisions;
    this.clock = clock;
  }

  /**
   * Recomputes the family's sell-decision triad and PERSISTS one durable row per holding (V037) — the
   * batch-time write behind the acknowledgeable history + the INT SELL_DECISION substrate. Called
   * fail-soft by {@link SwingBatchRecorder} AFTER the batch's passes, so the swing positions' only exit
   * evaluator is never touched by a persist defect; each row is also written under its own try/catch so
   * one bad holding cannot lose the rest. Returns how many rows were written. The recompute-on-read {@link
   * #report} stays the live view.
   */
  public int persist(SwingDoctrine doctrine) {
    SwingSellReport report = report(doctrine);
    java.time.LocalDate runDate = report.asOf().toLocalDate();
    int written = 0;
    for (SwingSellDecision d : report.items()) {
      try {
        sellDecisions.upsert(
            doctrine.book(), runDate, d.signalId(), EX, d.symbol(), d.setup(), d.stage(),
            d.setupType(), d.footprint(), d.entryPrice(), d.currentPrice(), d.unrealizedPct(),
            d.stopLevel(), d.trailLevel(), d.stillBuyable(), d.sellingNow(), d.sellReason(), d.verdict());
        written++;
      } catch (RuntimeException e) {
        log.warn(
            "{} sell-decision persist for {} skipped: {}",
            doctrine.batchName(), d.symbol(), e.getMessage());
      }
    }
    return written;
  }

  /** Builds the sell-decision triad for every open swing position of the given family. */
  public SwingSellReport report(SwingDoctrine doctrine) {
    Map<UUID, StrategyDefinition> swings = loadSwingDefs(doctrine);
    Map<UUID, Optional<StrategyDefinition>> adopted = new HashMap<>();
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
    List<SwingSellDecision> items = new ArrayList<>();
    for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
      StrategyDefinition def = swings.get(anchor.strategyVersionId());
      if (def == null) {
        // Adopt a superseded/unpublished version's anchor (audit H2): the triad must keep showing
        // holdings whose strategy was republished/disabled — a vanishing row IS the failure.
        def = adopted.computeIfAbsent(anchor.strategyVersionId(), v -> adoptDef(doctrine, v)).orElse(null);
      }
      if (def == null) {
        continue; // another family's anchor, or unmanageable
      }
      try {
        items.add(decide(doctrine, def, anchor, now));
      } catch (RuntimeException e) {
        log.warn(
            "{} sell-decision for {} skipped: {}",
            doctrine.batchName(), anchor.tradingsymbol(), e.getMessage());
      }
    }
    return new SwingSellReport(now, items);
  }

  private SwingSellDecision decide(
      SwingDoctrine doctrine, StrategyDefinition def, SignalRepository.SignalRow anchor,
      OffsetDateTime now) {
    JsonNode detail = doctrine.detailOf(anchor);
    List<EngineCandle> series =
        candles.fetch(EX, anchor.tradingsymbol(), IV, now.minusDays(doctrine.warmupDays()), now);
    if (series.isEmpty()) {
      throw new IllegalStateException("no daily series");
    }
    int last = series.size() - 1;
    IndicatorBank bank =
        SwingBatchEngine.buildBank(
            def, anchor.tradingsymbol(), series, doctrine.contextSeedsFromDetail(detail));
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
    // where am I a seller? — the entry-time stop + the family's advisory trail
    ExitEvaluator.Position pos =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, Math.max(entryIndex, 0));
    BigDecimal stopLevel = ExitEvaluator.entryLevels(def, bank, pos).stopLoss();
    BigDecimal trailLevel = doctrine.trailLevel(def, bank, pos, last, entryIndex);
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
    return new SwingSellDecision(
        anchor.id(), anchor.tradingsymbol(), text(detail, "setup"), integer(detail, "stage"),
        text(detail, "setupType"), text(detail, "footprint"), entryPrice, currentPrice, unrealizedPct,
        stopLevel, trailLevel, stillBuyable, sellingNow, sellReason, verdict);
  }

  private Map<UUID, StrategyDefinition> loadSwingDefs(SwingDoctrine doctrine) {
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
                  if ("swing".equals(def.session().style())
                      && doctrine.universeMode()
                          .equals(v.config().path("universe").path("mode").asText())) {
                    out.put(strategy.publishedVersionId(), def);
                  }
                } catch (RuntimeException ignored) {
                  // a bad config is skipped — the batch logs it on its own load
                }
              });
    }
    return out;
  }

  /** Compiles a superseded/unpublished version's own frozen config (family-scoped). */
  private Optional<StrategyDefinition> adoptDef(SwingDoctrine doctrine, UUID versionId) {
    return registry
        .findVersionById(versionId)
        .filter(v -> doctrine.universeMode().equals(v.config().path("universe").path("mode").asText()))
        .flatMap(
            v -> {
              try {
                StrategyDefinition def = StrategyCompiler.compile(v.config());
                return "swing".equals(def.session().style()) ? Optional.of(def) : Optional.empty();
              } catch (RuntimeException e) {
                log.warn(
                    "{} sell-decision: anchor version {} failed to compile — skipped: {}",
                    doctrine.batchName(), versionId, e.getMessage());
                return Optional.empty();
              }
            });
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
  }

  private static Integer integer(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.path(field).asInt() : null;
  }
}
