package in.arthayantra.strategyengine.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The ONE definition of a {@code premium_pct} stop/target level (ledger §9-04).
 *
 * <p>{@code round2(entry × (1 ± pct/100))} — {@code pct/100} taken at 6dp HALF_UP, the product
 * paise-rounded to 2dp HALF_UP. Real premiums trade in paise and the bracket order actually placed is
 * a paise value, so this rounding is the thing that makes a premium-pct STOP/TARGET fire at the
 * IDENTICAL bar in backtest and in paper (audit AY-SL-06).
 *
 * <p><b>Why this class exists.</b> The formula lived in two places — {@code
 * PremiumExitEvaluator.level} (backtest replay) and {@code PremiumBracketRules.resolve} (the live
 * signals slice) — kept in agreement only by a javadoc promising "the SAME derivation" and by the
 * shared {@code contracts/fixtures/exit-equivalence.json} fixture. A comment is not an enforcement
 * mechanism. Both now call this.
 *
 * <p>It lives in {@code libs/strategy-engine} because that is the ONLY place both callers can reach:
 * they sit in different SERVICES, and the live copy sits in the {@code signals} Modulith slice, which
 * may not import the {@code paper} slice. A shared library crosses neither boundary — which is why
 * the original "duplicated because Modulith forbids the import" rationale does not apply to it.
 *
 * <p><b>Do NOT route the backtest's trailing-arm maths through here.</b> {@code
 * PremiumExitEvaluator.rawLevel} is deliberately different — FULL precision, {@code
 * pct.movePointLeft(2)} rather than a 6dp divide, and no paise rounding — because the trailing arm and
 * trailing stop are backtest-only (the live bracket has no trailing) and therefore UNPINNED by the
 * equivalence fixture. Applying the AY-SL-06 paise rounding to them would silently shift exit bars
 * nothing tests.
 */
public final class PremiumLevels {

  private PremiumLevels() {}

  /**
   * The paise-rounded premium level, or {@code null} when {@code pct} is absent.
   *
   * @param entry the option's entry premium
   * @param pct the {@code premium_pct} percentage (e.g. {@code 35} for +35%)
   * @param up {@code true} for a target (entry × (1 + pct/100)), {@code false} for a stop
   */
  public static BigDecimal paiseRounded(BigDecimal entry, BigDecimal pct, boolean up) {
    if (entry == null || pct == null) {
      return null;
    }
    BigDecimal frac = pct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
    return entry
        .multiply(up ? BigDecimal.ONE.add(frac) : BigDecimal.ONE.subtract(frac))
        .setScale(2, RoundingMode.HALF_UP);
  }
}
