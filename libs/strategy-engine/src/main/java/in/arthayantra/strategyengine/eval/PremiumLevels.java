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
 * <p><b>Why this class exists.</b> The formula lived in FOUR places, kept in agreement only by
 * javadocs promising "the SAME derivation" and by the shared {@code
 * contracts/fixtures/exit-equivalence.json} fixture. A comment is not an enforcement mechanism.
 * All four now call this:
 *
 * <ul>
 *   <li>{@code PremiumExitEvaluator.level} — the backtest's exit levels.
 *   <li>{@code OptionsPremiumReplay.level} — the protective levels RECORDED on the {@code Trade}
 *       row, so a backtest trade card shows what the identical paper trade would.
 *   <li>{@code PaperSignalListener.premiumBrackets} — the real paper-OPEN path. The fixture's own
 *       header called this a "genuine THIRD copy".
 *   <li>{@code PremiumBracketRules.resolve} — the SHADOW book. Despite the name it is not the live
 *       bracket chain, which is what {@code PaperSignalListener} feeds; the first cut of this
 *       consolidation missed two copies by believing otherwise (cross-vendor review, round 1).
 * </ul>
 *
 * <p>It lives in {@code libs/strategy-engine} because that is the ONLY place every caller can reach:
 * they sit in different SERVICES and in different Modulith slices ({@code signals} may not import
 * {@code paper}). A shared library crosses neither boundary — which is why
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
   * The paise-rounded premium level, or {@code null} when EITHER input is absent.
   *
   * <p>The null-on-absent-{@code entry} branch is defensive: today's callers cannot reach it
   * ({@code PremiumBracketRules.resolve} returns {@code Brackets.NONE} for a null entry one line
   * earlier, and the replay paths dereference the entry premium in their sizing arithmetic above the
   * call). It is here so a future caller gets a null level rather than a {@code NullPointerException}
   * out of a shared helper.
   *
   * @param entry the option's entry premium; {@code null} yields {@code null}
   * @param pct the {@code premium_pct} percentage (e.g. {@code 35} for +35%); {@code null} yields
   *     {@code null}, which is how "this rule is not configured" is expressed
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
