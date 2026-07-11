package in.arthayantra.backtest.replay.counterfactual;

import in.arthayantra.backtest.replay.options.PremiumExitEvaluator;
import java.math.BigDecimal;

/**
 * The premium-percent exit knobs a counterfactual variant varies — a THIN, faithful mirror of the
 * pinned {@link PremiumExitEvaluator.Rules} (contracts/fixtures/exit-equivalence.json, #505). The
 * counterfactual replay REUSES {@link PremiumExitEvaluator} unchanged (this is not a new exit engine);
 * it only feeds it different rule sets, so the frozen premium-exit semantics stay untouched.
 *
 * <p>Coverage note: this is exactly the premium-expressible subset of the runbook's §4.B grid —
 * take-profit % / stop %, a premium-percent trailing stop ({@code trailActivatePct} + {@code
 * trailByPct}), and a bar-count time stop. The runbook's INDICATOR-basis ST-line trail (a supertrend
 * function of the underlying signal series, not of the option's own premium) is deliberately out of
 * scope — it is not a function of the captured premium path and would require a full underlying replay,
 * not a premium counterfactual. Every field is optional (null = that rule absent); a variant with no
 * knobs holds each entry to the session-close square-off (END_OF_DATA), a valid baseline.
 *
 * <p>Percent convention: WHOLE-NUMBER percent of the entry premium — {@code takeProfitPct: 35}
 * means +35% (the pinned evaluator does {@code movePointLeft(2)}; the exit-equivalence fixture's
 * grammar). Passing {@code 0.35} intending 35% silently yields a 0.35% band.</p>
 */
public record ExitKnobs(
    BigDecimal takeProfitPct,
    BigDecimal stopLossPct,
    BigDecimal trailActivatePct,
    BigDecimal trailByPct,
    Integer timeStopBars) {

  /** Maps to the pinned evaluator's rule record (field-for-field; note SL/TP order differs there). */
  public PremiumExitEvaluator.Rules toRules() {
    return new PremiumExitEvaluator.Rules(
        stopLossPct, takeProfitPct, trailActivatePct, trailByPct, timeStopBars);
  }
}
