package in.arthayantra.backtest.replay.counterfactual;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/backtests/counterfactual/{runId}} body (EVO E3 item 9) — the per-variant metrics of
 * one completed counterfactual replay. A typed record (spec-visible, ratchet-safe).
 *
 * @param runId the counterfactual_runs id (the {@code resultRef})
 * @param jobId the originating COUNTERFACTUAL job
 * @param windowFrom the request's [from, to) entry-eligibility window
 * @param windowTo the request's [from, to) entry-eligibility window
 * @param entryCount observed entries replayed
 * @param variantCount exit-knob variants scored
 * @param variants per-variant realized metrics, request order preserved
 * @param ranAt when the replay completed
 */
public record CounterfactualResult(
    String runId,
    String jobId,
    String windowFrom,
    String windowTo,
    int entryCount,
    int variantCount,
    List<VariantResult> variants,
    String ranAt) {

  /**
   * One exit-knob variant's realized outcome over ALL entries — the runbook §4.B "expectancy per band"
   * row. {@code netPnlInr} is cost-inclusive (the shared {@code FillSimulator}: slippage + Rs20/lot
   * brokerage + the statutory stack) — the decision metric; {@code grossPremiumPoints} is the scale-free
   * per-unit premium move summed across trades (the runbook's points comparison). {@code maxDrawdownInr}
   * is peak-to-trough over the cumulative-PnL trade sequence (entries ordered by entry time).
   *
   * <p>Unit notes (deliberate divergences from the run-metrics catalog): {@code winRate} is a
   * FRACTION in [0,1] (the catalog's winRate is a percentage — a cross-surface compare misreads
   * by 100x without this note); {@code maxDrawdownInr} is absolute rupees anchored at 0 (no
   * notional base exists here), not the catalog's percentage. {@code netPnlInr} re-costs the
   * entry leg through the shared fill simulator even though {@code entryPremium} is an
   * already-executed real fill — a small constant conservative double-count (~1 tick of
   * slippage), identical across variants, so ranking is unaffected.</p>
   */
  public record VariantResult(
      String name,
      int tradeCount,
      BigDecimal netPnlInr,
      BigDecimal grossPremiumPoints,
      BigDecimal winRate,
      BigDecimal expectancyInr,
      BigDecimal maxDrawdownInr,
      Map<String, Integer> exitReasonCounts) {}
}
