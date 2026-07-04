package in.arthayantra.strategysignal.minervini;

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
 * The Phase-9 (MV-9.3) daily sell-decision triad — for every open Minervini swing position, the
 * §2.10/§3.6.D "would I buy it now / why am I holding / where am I a seller" read. Reuses the engine's
 * per-symbol bank build ({@link MinerviniSwingEngine#buildBank}) + the FROZEN evaluators over each
 * held anchor's fresh daily series, with the persisted VCP geometry from its {@code minervini_detail}.
 * Read-only — it never emits a signal or moves the book; the owner reads it to manage the holdings.
 */
@Service
public class MinerviniSellDecisionService {

  private static final Logger log = LoggerFactory.getLogger(MinerviniSellDecisionService.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";

  /** One holding's triad: the buy-now check, the hold status, and where the exits sit. */
  public record SellDecision(
      String symbol,
      String setup,
      Integer stage,
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
  public record Report(OffsetDateTime asOf, List<SellDecision> items) {}

  private final StrategyRepository registry;
  private final MarketDataCandlesClient candles;
  private final SignalRepository signals;
  private final Clock clock;
  private final int warmupDays;

  /** Wires the registry, candle client, and signal repo. */
  public MinerviniSellDecisionService(
      StrategyRepository registry,
      MarketDataCandlesClient candles,
      SignalRepository signals,
      Clock clock,
      @Value("${artha.minervini.swing.warmup-days:520}") int warmupDays) {
    this.registry = registry;
    this.candles = candles;
    this.signals = signals;
    this.clock = clock;
    this.warmupDays = warmupDays;
  }

  /** Builds the sell-decision triad for every open swing position. */
  public Report report() {
    Map<UUID, StrategyDefinition> swings = loadSwingDefs();
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
    List<SellDecision> items = new ArrayList<>();
    for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
      StrategyDefinition def = swings.get(anchor.strategyVersionId());
      if (def == null) {
        continue; // not a published swing anchor
      }
      try {
        items.add(decide(def, anchor, now));
      } catch (RuntimeException e) {
        log.warn("sell-decision for {} skipped: {}", anchor.tradingsymbol(), e.getMessage());
      }
    }
    return new Report(now, items);
  }

  private SellDecision decide(
      StrategyDefinition def, SignalRepository.SignalRow anchor, OffsetDateTime now) {
    JsonNode detail = anchor.minerviniDetail();
    BigDecimal pivot = decimal(detail, "pivot");
    BigDecimal cheat = decimal(detail, "cheatPivot");
    boolean thrust = detail != null && detail.path("thrust").asBoolean(false);
    List<EngineCandle> series =
        candles.fetch(EX, anchor.tradingsymbol(), "1d", now.minusDays(warmupDays), now);
    if (series.isEmpty()) {
      throw new IllegalStateException("no daily series");
    }
    int last = series.size() - 1;
    IndicatorBank bank = MinerviniSwingEngine.buildBank(def, anchor.tradingsymbol(), series, pivot, cheat, thrust);
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
    // where am I a seller? — the entry-time stop + the current 50-day-MA trail level
    BigDecimal stopLevel =
        ExitEvaluator.entryLevels(
                def, bank, new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice,
                    Math.max(entryIndex, 0)))
            .stopLoss();
    BigDecimal trailLevel = bank.has("sma50") ? bank.valueAt("sma50", last) : null;
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
    return new SellDecision(
        anchor.tradingsymbol(), text(detail, "setup"), integer(detail, "stage"),
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
                  if ("swing".equals(def.session().style())) {
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

  private static Integer integer(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.path(field).asInt() : null;
  }
}
