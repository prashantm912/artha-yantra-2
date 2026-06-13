package in.arthayantra.strategyengine.fills;

/**
 * How a trade's exit level was detected (A9 [FP-5]). The parity floor is the closed 1m bar — NEVER
 * tick-level. {@code CLOSE_EVAL}: exit evaluated on the primary-bar close. {@code INTRABAR_1M}: the
 * level was touched on a closed 1m bar inside the primary bar. {@code BAR_HL_WORSTOF}: 1m coverage
 * was missing, so the primary bar's high/low worst-of was used with a gap-through fill at the bar
 * open. Persisted on {@code backtest_trades.touch_basis}.
 */
public enum TouchBasis {
  CLOSE_EVAL,
  INTRABAR_1M,
  BAR_HL_WORSTOF
}
