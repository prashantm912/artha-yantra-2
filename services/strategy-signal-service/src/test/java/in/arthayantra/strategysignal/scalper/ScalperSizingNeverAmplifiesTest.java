package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ THE INVARIANT THE H20 ROUNDING FIX RESTS ON: the graded size multiplier can never exceed 1.0.
 *
 * <p>{@code PaperEmissionGuard} rounds the graded quantity HALF_UP rather than flooring it, because
 * flooring turned the documented "gentle taper" into a 50% cliff at small lot counts. Rounding UP is
 * safe there for exactly one reason — the multiplier is capped at ONE, so HALF_UP can reach the base
 * size and never pass it. It is a CLIFF REPAIR, not a size increase.
 *
 * <p><b>If any factor ever became amplifying, that reasoning silently inverts</b> and the guard would
 * size ABOVE the strategy's own budget while every existing test stayed green — the change would look
 * like a tuning tweak in {@code ScalperSizing} and land nowhere near the rounding it breaks. This test
 * exists so that combination cannot ship quietly: it fails in the file that caused it.
 *
 * <p>Deliberately a property sweep, not three hand-picked points. The three factors compose
 * multiplicatively and the aggregate one is a LINEAR TAPER, so the risk is an interior value, not an
 * endpoint — checking only the documented band edges would miss a taper that overshoots between them.
 *
 * <p>⚠️ <b>Two corrections from cross-vendor review, and both matter for how much this test is
 * worth.</b> (1) It exercises <b>76,356</b> triples (101 x 21 x 36), not the "~370k" first claimed —
 * an arithmetic error of ~5x that appeared in the PR body and the ledger before it was caught.
 * (2) More importantly, <b>the sweep is a SAMPLING check, not the guarantee</b>: aggregates are
 * stepped every 0.01, so it cannot prove the property. The exhaustive guarantee is the terminal
 * hard clamp to <= 1 inside {@code sizeMultiplier} itself. This test earns its place by failing
 * loudly IN THE FILE THAT CAUSED IT if that clamp is ever removed or a factor becomes amplifying —
 * not by exhausting the input space.
 */
class ScalperSizingNeverAmplifiesTest {

  @Test
  void noCombinationOfAggregateOiAndVixEverAmplifiesTheBudget() {
    // Sweeps well OUTSIDE the documented bands on purpose: the guard must hold for inputs the
    // engine is not supposed to produce, because "not supposed to" is not enforced anywhere.
    for (int agg = 0; agg <= 100; agg++) {
      BigDecimal aggregate = BigDecimal.valueOf(agg).movePointLeft(2);
      for (int oi = 0; oi <= 100; oi += 5) {
        BigDecimal oiImbalancePct = BigDecimal.valueOf(oi);
        for (int vix = 5; vix <= 40; vix++) {
          BigDecimal vixLevel = BigDecimal.valueOf(vix);

          BigDecimal m = ScalperSizing.sizeMultiplier(aggregate, oiImbalancePct, vixLevel);

          assertThat(m)
              .as(
                  "multiplier must never exceed 1.0 — PaperEmissionGuard's HALF_UP rounding would"
                      + " otherwise size ABOVE the strategy budget (aggregate=%s oi=%s vix=%s)",
                  aggregate, oiImbalancePct, vixLevel)
              .isLessThanOrEqualTo(BigDecimal.ONE);
          assertThat(m)
              .as("a multiplier at or below zero would silently zero out a fired entry")
              .isGreaterThan(BigDecimal.ZERO);
        }
      }
    }
  }

  /** Nulls are the live default before OI/VIX are resolved, and must not amplify either. */
  @Test
  void nullOiAndVixDoNotAmplify() {
    for (int agg = 0; agg <= 100; agg++) {
      BigDecimal aggregate = BigDecimal.valueOf(agg).movePointLeft(2);
      assertThat(ScalperSizing.sizeMultiplier(aggregate, null, null))
          .isLessThanOrEqualTo(BigDecimal.ONE)
          .isGreaterThan(BigDecimal.ZERO);
    }
    assertThat(ScalperSizing.sizeMultiplier(null, null, null)).isEqualByComparingTo(BigDecimal.ONE);
  }
}
