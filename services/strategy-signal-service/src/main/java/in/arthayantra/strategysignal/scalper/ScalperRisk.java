package in.arthayantra.strategysignal.scalper;

import in.arthayantra.strategyengine.config.StrategyDefinition.ExitRuleSpec;
import java.util.List;
import java.util.Set;

/**
 * Scalper risk rails (master plan §0B/§12.7). Enforces the §0B <b>hard-stop rule</b>: a scalper
 * must carry a bounding exit the LIVE ENGINE can actually fire — a {@code time_stop} or an
 * index-side {@code stop_loss} — so it can never hold an unbounded losing option position. A purely
 * structural exit ({@code signal_exit} alone) is not enough: if the structural trigger never fires,
 * the position has no time-boxed floor.
 *
 * <p>Hardened after T21 (#990 round-3 review): a {@code stop_loss} with {@code basis: premium_pct}
 * is an OPTION-leg band enforced only by the paper bracket path ({@code PremiumBracketRules} /
 * {@code PaperBracketEvaluator}) — it never produces an engine-side index level (a 25% move on the
 * INDEX series never happens intraday), and the paper bracket does not run when a signal is not
 * taken into paper. So premium_pct (and any unknown basis, which {@code ExitEvaluator.levelDistance}
 * nulls out to an inert rule) no longer counts as bounding; such a config must also carry a
 * {@code time_stop} or an engine-side stop to load.
 *
 * <p>The global paper limits (daily-loss / kill-switch / max-open via {@code RiskService}) and
 * no-averaging (the strategies' {@code max_positions_per_underlying: 1}) are already enforced
 * elsewhere; the 5-sub-account model (first-loss freeze, 5-wins/day cap) is the remaining §12.7 work.
 */
public final class ScalperRisk {

  private ScalperRisk() {}

  /**
   * The {@code stop_loss} bases that resolve to a fireable level on the index-future series the
   * engine evaluates ({@code ExitEvaluator.levelDistance}): percent-of-entry, ATR distance, and
   * fixed index points. Excluded: {@code premium_pct} (option-side, inert at index scale),
   * {@code r_multiple} (derives its distance from ANOTHER stop's initial risk — not self-sufficient
   * as the only stop), and unknown/absent bases (null distance → the rule never fires).
   */
  private static final Set<String> ENGINE_SIDE_STOP_BASES =
      Set.of("percent", "atr_multiple", "index_points");

  /**
   * True when the exits include a bounding exit the engine can fire: a {@code time_stop} or a
   * {@code stop_loss} on an engine-side basis (the §0B floor).
   */
  public static boolean hasBoundingExit(List<ExitRuleSpec> exitRules) {
    return exitRules.stream().anyMatch(ScalperRisk::isEngineFireableBound);
  }

  private static boolean isEngineFireableBound(ExitRuleSpec rule) {
    if ("time_stop".equals(rule.type())) {
      return true;
    }
    return "stop_loss".equals(rule.type())
        && rule.params() != null
        && ENGINE_SIDE_STOP_BASES.contains(String.valueOf(rule.params().get("basis")));
  }
}
