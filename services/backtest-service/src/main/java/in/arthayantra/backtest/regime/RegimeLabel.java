package in.arthayantra.backtest.regime;

/**
 * One of the four <b>computed</b> daily regime labels (guard 6, §D.4) — the cross product of a
 * binary trend dimension and a binary volatility dimension. Labels are deterministic functions of
 * persisted benchmark candles; the schema carries <b>no hand-labeled date ranges</b> and no
 * regime-filter expressions (the D18 grammar stays closed).
 *
 * <ul>
 *   <li><b>trend</b> = benchmark close vs its 200-day SMA → {@code UP} (close ≥ SMA) / {@code DOWN}
 *   <li><b>volatility</b> = 20-day realized vol vs its trailing 1-year median → {@code QUIET} (≤
 *       median) / {@code TURBULENT}
 * </ul>
 *
 * <p>Both dimensions for day {@code T} are computed from benchmark data <b>through the prior
 * session's close (T−1) only</b> — never day {@code T}'s own close — so attribution carries no
 * same-session look-ahead.
 */
public enum RegimeLabel {
  /** Up-trend, low volatility. */
  UP_QUIET,
  /** Up-trend, high volatility. */
  UP_TURBULENT,
  /** Down-trend, low volatility. */
  DOWN_QUIET,
  /** Down-trend, high volatility. */
  DOWN_TURBULENT;

  /** Composes the two dimensions into one of the four labels. */
  static RegimeLabel of(boolean up, boolean turbulent) {
    if (up) {
      return turbulent ? UP_TURBULENT : UP_QUIET;
    }
    return turbulent ? DOWN_TURBULENT : DOWN_QUIET;
  }
}
