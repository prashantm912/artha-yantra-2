package in.arthayantra.strategysignal.scalper;

import in.arthayantra.strategyengine.config.StrategyDefinition.ExitRuleSpec;
import java.util.List;

/**
 * Scalper risk rails (master plan §0B/§12.7). v1 enforces the §0B <b>hard-stop rule</b>: a scalper
 * must carry a bounding exit — a fixed {@code stop_loss} OR a {@code time_stop} — so it can never
 * hold an unbounded losing option position. A purely structural exit ({@code signal_exit} alone) is
 * not enough: if the structural trigger never fires, the position has no time-boxed floor.
 *
 * <p>The global paper limits (daily-loss / kill-switch / max-open via {@code RiskService}) and
 * no-averaging (the strategies' {@code max_positions_per_underlying: 1}) are already enforced
 * elsewhere; the 5-sub-account model (first-loss freeze, 5-wins/day cap) is the remaining §12.7 work.
 */
public final class ScalperRisk {

  private ScalperRisk() {}

  /** True when the exits include a hard {@code stop_loss} or a {@code time_stop} (the §0B floor). */
  public static boolean hasBoundingExit(List<ExitRuleSpec> exitRules) {
    return exitRules.stream()
        .anyMatch(r -> "stop_loss".equals(r.type()) || "time_stop".equals(r.type()));
  }
}
