package in.arthayantra.backtest.regime;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The per-fold regime attribution that fills {@code fold_metrics[i].regimeMix} (the Phase 31 null
 * seam) plus the per-fold per-regime OOS aggregates and the per-trade entry-day tags (guard 6,
 * §D.4). All four are deterministic functions of the persisted benchmark candles and the fold's OOS
 * trades — no hand labels, no same-session look-ahead.
 *
 * @param mix the distribution of regime labels over the fold's OOS <b>test trading days</b> (every
 *     trading day in the OOS window mapped to its computed label, ordered label→count over the four
 *     {@link RegimeLabel}s) — the data source for Stage E's regimes-covered badge
 * @param perRegime per-label OOS aggregates (Sharpe + expectancy) over the trades whose ENTRY day
 *     carries that label — ordered by {@link RegimeLabel}; only labels with ≥ 1 entry-tagged trade
 *     are present
 * @param tradeTags each closed OOS trade's entry-day regime label, in trade ({@code seq}) order —
 *     persisted WITHIN {@code fold_metrics}, never on every {@code backtest_trades} row (§D.3)
 */
public record RegimeAttribution(
    Map<RegimeLabel, Integer> mix,
    Map<RegimeLabel, RegimeStat> perRegime,
    List<RegimeTradeTag> tradeTags) {

  /**
   * One per-regime OOS aggregate.
   *
   * @param sharpe the annualized Sharpe over the entry-tagged trades' P&amp;L sequence (returns vs
   *     initial equity), {@code null} when fewer than two such trades
   * @param expectancy mean net P&amp;L per entry-tagged trade (₹), {@code null} when no such trade
   * @param tradeCount the number of entry-tagged closed trades in this regime
   */
  public record RegimeStat(BigDecimal sharpe, BigDecimal expectancy, int tradeCount) {}

  /**
   * One closed OOS trade's entry-day regime tag.
   *
   * @param seq the trade's sequence number within the fold's OOS replay
   * @param label the computed regime label for the trade's entry day, or {@code null} when the entry
   *     day predates warm-up (defensive — pre-flight normally precludes this)
   */
  public record RegimeTradeTag(int seq, RegimeLabel label) {}
}
