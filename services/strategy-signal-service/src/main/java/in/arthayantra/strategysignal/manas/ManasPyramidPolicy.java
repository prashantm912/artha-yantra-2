package in.arthayantra.strategysignal.manas;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.swing.PyramidPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Manas Arora §3.4 pyramiding policy (flag-gated {@code artha.manas-arora.pyramid.enabled}, default
 * OFF): a held winner may ADD a lot when it has moved up ≥ {@code min-gain-pct} since its most-recent
 * lot AND makes a fresh valid pivot — up to {@code max-lots} — provided the book's aggregate open risk
 * stays ≤ {@code max-portfolio-risk-pct} (§3.4.3 "add without increasing risk exposure"). With the flag
 * OFF this returns "no room", so a held symbol is skipped before any fetch (the pre-F2 behaviour).
 */
@Component
public class ManasPyramidPolicy implements PyramidPolicy {

  private final boolean enabled;
  private final BigDecimal minGainPct;
  private final int maxLots;
  private final BigDecimal maxPortfolioRiskPct;

  /** Wires the §3.4 knobs (all default to the pre-F2 owner numbers). */
  public ManasPyramidPolicy(
      @Value("${artha.manas-arora.pyramid.enabled:false}") boolean enabled,
      @Value("${artha.manas-arora.pyramid.min-gain-pct:5.0}") BigDecimal minGainPct,
      @Value("${artha.manas-arora.pyramid.max-lots:3}") int maxLots,
      @Value("${artha.manas-arora.pyramid.max-portfolio-risk-pct:6.0}")
          BigDecimal maxPortfolioRiskPct) {
    this.enabled = enabled;
    this.minGainPct = minGainPct;
    this.maxLots = maxLots;
    this.maxPortfolioRiskPct = maxPortfolioRiskPct;
  }

  @Override
  public boolean hasRoom(List<SignalRepository.SignalRow> lots) {
    return enabled && lots.size() < maxLots;
  }

  @Override
  public Map<String, Object> describe() {
    Map<String, Object> regime = new LinkedHashMap<>();
    regime.put("enabled", enabled);
    regime.put("minGainPct", minGainPct.toPlainString());
    regime.put("maxLots", maxLots);
    regime.put("maxPortfolioRiskPct", maxPortfolioRiskPct.toPlainString());
    return regime;
  }

  @Override
  public boolean qualifiesForAdd(List<SignalRepository.SignalRow> lots, EngineCandle bar) {
    return movedUp(bar.close(), lastLotEntry(lots), minGainPct);
  }

  @Override
  public boolean wouldBreachRiskCap(
      StrategyDefinition definition,
      String exchange,
      String symbol,
      IndicatorBank bank,
      int index,
      BigDecimal entryPrice,
      EmissionGuard guard) {
    // §3.4.3 portfolio-risk gate: true when opening the prospective new lot would push the book's
    // aggregate open risk over the cap. The new lot's risk = its advisory qty × the stop distance (the
    // very sizing/stop the emit will use). For an ADD this is a CONSERVATIVE proxy — the add averages
    // into one position keeping the ORIGINAL lot-1 bracket, so the true post-add risk is
    // (newAvg − origStop) × totalQty; summing existing + newQty × newStopDistance never understates
    // (the original stop only trails up), the safe direction for a "do not increase risk" gate. M40
    // (2026-08-02) reuses this SAME method for a FRESH (first) entry too (SwingBatchEngine's entry
    // pass). ⚠️ Restated (round 4, cross-vendor review, 2026-08-02): this is an EMISSION-TIME PREVIEW
    // off the candle close, not the authority — a symbol with no OPEN, ACTIVE-ANCHOR lot has none of
    // ITS OWN existing brackets to average into, so newQty × stopDistance is exact for what this
    // preview can see, but it cannot see a dead-anchor paper row (a held position whose signal anchor
    // has expired/superseded), a delayed swing-effect retry, or the slippage-adjusted real fill price.
    // {@code RiskService#manasAggregateRiskCheck} is the AUTHORITATIVE write-time check that can
    // refuse what this preview passed. Either way, existingRiskInr already reflects every OTHER open
    // position in the book (both Manas strategies share one Books.MANAS_ARORA key), so this call
    // aggregates across the whole book, not just the calling strategy. False (does not block) when the
    // guard is absent or equity/qty/stop is unknown — then the position is simply not auto-papered (no
    // risk to gate).
    if (guard == null) {
      return false;
    }
    ExitEvaluator.EntryLevels levels =
        ExitEvaluator.entryLevels(
            definition, bank,
            new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, index));
    BigDecimal stop = levels.stopLoss();
    if (stop == null) {
      return false;
    }
    BigDecimal stopDistance = entryPrice.subtract(stop).abs();
    BigDecimal newQty =
        guard.suggestedQty(
            definition.sizing(), exchange, symbol, entryPrice, stopDistance, Books.MANAS_ARORA);
    return breachesRiskCap(
        guard.openRiskInr(Books.MANAS_ARORA),
        newQty,
        stopDistance,
        guard.bookEquity(Books.MANAS_ARORA),
        maxPortfolioRiskPct);
  }

  /** The most-recent lot's entry price (§3.4.1 measures the fresh move since the LAST add). */
  private static BigDecimal lastLotEntry(List<SignalRepository.SignalRow> lots) {
    return lots.stream()
        .max(
            Comparator.comparing(SignalRepository.SignalRow::generatedAt)
                .thenComparing(SignalRepository.SignalRow::id))
        .map(SignalRepository.SignalRow::entryPrice)
        .orElse(null);
  }

  /**
   * §3.4.1 pyramid trigger: the current close is ≥ {@code minGainPct}% above the most-recent lot's
   * entry. False when the entry is unknown / non-positive. Pure for a unit test.
   */
  static boolean movedUp(BigDecimal currentClose, BigDecimal lastLotEntry, BigDecimal minGainPct) {
    if (currentClose == null || lastLotEntry == null || lastLotEntry.signum() <= 0) {
      return false;
    }
    BigDecimal gainPct =
        currentClose
            .subtract(lastLotEntry)
            .divide(lastLotEntry, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    return gainPct.compareTo(minGainPct) >= 0;
  }

  /**
   * §3.4.3: does the existing book open risk plus the prospective new lot's risk exceed the cap? Pure
   * for a unit test. Returns false (never blocks) when equity/qty/stop is missing or non-positive.
   */
  static boolean breachesRiskCap(
      BigDecimal existingRiskInr,
      BigDecimal newQty,
      BigDecimal stopDistance,
      BigDecimal equity,
      BigDecimal capPct) {
    if (equity == null
        || equity.signum() <= 0
        || newQty == null
        || newQty.signum() <= 0
        || stopDistance == null
        || stopDistance.signum() <= 0) {
      return false;
    }
    BigDecimal totalRisk =
        (existingRiskInr == null ? BigDecimal.ZERO : existingRiskInr)
            .add(newQty.multiply(stopDistance));
    BigDecimal totalPct =
        totalRisk.divide(equity, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    return totalPct.compareTo(capPct) > 0;
  }
}
