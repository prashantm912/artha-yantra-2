package in.arthayantra.backtest.replay.options;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Evaluates the {@code premium_pct} exits of a long-premium options leg against the option's OWN
 * premium series (Part 2). These are the exits the underlying-signal model can't compute — a stop /
 * target / trailing-stop fires off the OPTION price mid-hold, not off the index. Premium-buying only
 * (long the option, per the frozen schema), so a stop is a premium DROP and a target a premium RISE.
 *
 * <p>Scans the premium closes from the entry bar forward and returns the FIRST trigger by this fixed,
 * deterministic precedence (a backtest golden will pin it): <b>STOP_LOSS → TAKE_PROFIT → TRAILING_STOP
 * → TIME_STOP → SIGNAL_EXIT</b>, else END_OF_DATA at the last bar. Close-based (the series is 1m
 * closes), matching the rest of the candle replay — no intra-bar touch.
 */
public final class PremiumExitEvaluator {

  private static final BigDecimal ONE = BigDecimal.ONE;

  /** The leg's premium-pct exit thresholds (each null when the strategy omits that rule). */
  public record Rules(
      BigDecimal stopLossPct,
      BigDecimal takeProfitPct,
      BigDecimal trailActivatePct,
      BigDecimal trailByPct,
      Integer timeStopBars) {}

  /** The resolved exit — the bar offset from entry (1 = next bar), the premium there, and the reason. */
  public record Exit(int barOffset, BigDecimal premium, String reason) {}

  private PremiumExitEvaluator() {}

  /**
   * @param entryPremium the fill price the leg was entered at (levels are relative to it)
   * @param premiumsFromEntry the premium (1m close) series, index 0 = the entry bar
   * @param rules the premium-pct thresholds
   * @param signalExitOffset the bar offset of the strategy's signal-driven exit (≥1); use the series
   *     length to mean "no signal exit before end of data"
   */
  public static Exit evaluate(
      BigDecimal entryPremium,
      List<BigDecimal> premiumsFromEntry,
      Rules rules,
      int signalExitOffset) {
    BigDecimal slLevel = level(entryPremium, rules.stopLossPct(), false);
    BigDecimal tpLevel = level(entryPremium, rules.takeProfitPct(), true);
    BigDecimal trailArm = level(entryPremium, rules.trailActivatePct(), true);
    boolean armed = rules.trailByPct() != null && rules.trailActivatePct() == null;
    BigDecimal peak = entryPremium;

    for (int i = 1; i < premiumsFromEntry.size(); i++) {
      BigDecimal p = premiumsFromEntry.get(i);

      if (slLevel != null && p.compareTo(slLevel) <= 0) {
        return new Exit(i, p, "STOP_LOSS");
      }
      if (tpLevel != null && p.compareTo(tpLevel) >= 0) {
        return new Exit(i, p, "TAKE_PROFIT");
      }
      if (rules.trailByPct() != null) {
        if (p.compareTo(peak) > 0) {
          peak = p;
        }
        if (!armed && trailArm != null && p.compareTo(trailArm) >= 0) {
          armed = true;
        }
        if (armed) {
          BigDecimal trailStop = level(peak, rules.trailByPct(), false);
          if (p.compareTo(trailStop) <= 0) {
            return new Exit(i, p, "TRAILING_STOP");
          }
        }
      }
      if (rules.timeStopBars() != null && i >= rules.timeStopBars()) {
        return new Exit(i, p, "TIME_STOP");
      }
      if (i >= signalExitOffset) {
        return new Exit(i, p, "SIGNAL_EXIT");
      }
    }
    int last = premiumsFromEntry.size() - 1;
    return new Exit(last, premiumsFromEntry.get(Math.max(last, 0)), "END_OF_DATA");
  }

  /**
   * {@code round2(base × (1 ± pct/100))} — paise-rounded (2dp HALF_UP, pct/100 taken at 6dp), the SAME
   * derivation the live bracket ({@code PremiumBracketRules.resolve}) uses, so a premium-pct level fires
   * at the identical bar in backtest and paper (audit AY-SL-06; real premiums trade in paise, and the
   * bracket order actually placed is a paise value). {@code up = +} (target / trailing-arm) else −
   * (stop / trailing-off-peak). {@code base} is the entry premium for SL/TP/arm, the running peak for
   * the trailing stop. Null when the pct is absent.
   */
  private static BigDecimal level(BigDecimal base, BigDecimal pct, boolean up) {
    if (pct == null) {
      return null;
    }
    BigDecimal frac = pct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
    return base.multiply(up ? ONE.add(frac) : ONE.subtract(frac)).setScale(2, RoundingMode.HALF_UP);
  }
}
