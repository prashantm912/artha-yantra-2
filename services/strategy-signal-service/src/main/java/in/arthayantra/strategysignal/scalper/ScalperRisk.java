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
 * is an OPTION-leg band enforced by the paper bracket path ({@code PremiumBracketRules} /
 * {@code PaperBracketEvaluator}), which does not run when a signal is not taken into paper — so it
 * cannot be the §0B floor for a signal that is not. So premium_pct (and any unknown basis, which
 * {@code ExitEvaluator.levelDistance} nulls out to an inert rule) does not count as bounding; such
 * a config must also carry a {@code time_stop} or an engine-side stop to load.
 *
 * <p><b>This rule is only ever asked about an {@code options_of_underlying} strategy</b> — its one
 * caller gates on the scalper tag AND that universe mode ({@code SignalEngine}), which is the only
 * strategy kind with TWO price planes (the index-future series the engine evaluates, and the option
 * premium the position is actually held in). {@link #ENGINE_SIDE_STOP_BASES} is therefore a
 * statement about the OPTIONS plane, not about level bases in general.
 *
 * <p>The global paper limits (daily-loss / kill-switch / max-open via {@code RiskService}) and
 * no-averaging (the strategies' {@code max_positions_per_underlying: 1}) are already enforced
 * elsewhere; the 5-sub-account model (first-loss freeze, 5-wins/day cap) is the remaining §12.7 work.
 */
public final class ScalperRisk {

  private ScalperRisk() {}

  /**
   * The {@code stop_loss} bases that, <b>on an options strategy</b>, both resolve to a fireable
   * level on the index-future series the engine evaluates ({@code ExitEvaluator.levelDistance})
   * AND name unambiguously which of the two price planes they mean: ATR distance and fixed index
   * points. Excluded:
   *
   * <ul>
   *   <li>{@code premium_pct} — its enforcement plane is the OPTION premium ({@code
   *       PremiumBracketRules} / {@code PaperBracketEvaluator} / the backtest's {@code
   *       OptionsPremiumReplay} all key on that exact literal), and that path runs only when the
   *       signal is taken into paper, so it cannot be the §0B floor for a signal that is not. The
   *       engine does also resolve it index-side through the shared percent-of-entry formula, but
   *       against a value authored on the PREMIUM scale (25–50 in every seeded config), which on a
   *       ~24,000 index is 6,000–12,000 points.
   *   <li>{@code percent} — removed in the PR that added {@code ScalperStopBasisCouplingTest}.
   *       <b>Correction to the justification this list used to carry:</b> {@code percent} is NOT
   *       "inert at index scale". {@code ExitEvaluator.levelDistance} computes {@code premium_pct}
   *       and {@code percent} with ONE shared arm ({@code entryPrice × value ÷ 100}), so it is
   *       enforced index-side by exactly the same mechanism as {@code index_points}; whether it
   *       fires is a property of the VALUE, not the basis ({@code {percent, 0.3}} on NIFTY is ~72
   *       index points and fires normally, {@code {percent, 25}} is ~6,000 and never does). What
   *       disqualifies it here is that on an options config the name does not say which plane it
   *       means — which is why {@code SemanticValidator.checkOptionsPlaneLevelBases} refuses it
   *       there (#1284), making it unreachable by this method. It is removed rather than left as
   *       unreachable-but-inconsistent because the two modules are coupled by nothing but that
   *       shared {@code "options_of_underlying"} literal: were the refusal widened or relaxed,
   *       keeping {@code percent} here would silently restore the §0B hole (a config whose only
   *       stop is {@code {percent, 25}} loading as BOUNDED with no enforceable stop on either
   *       plane). Removed, the same drift merely refuses to load a {@code {percent, 0.3}} scalper
   *       that would in fact have been bounded — the safe failure direction.
   *   <li>{@code r_multiple} — derives its distance from ANOTHER stop's initial risk, so it is not
   *       self-sufficient as the only stop.
   *   <li>unknown/absent bases — null distance, so the rule never fires.
   * </ul>
   *
   * <p>The coupling to {@code SemanticValidator} is pinned by {@code ScalperStopBasisCouplingTest},
   * which drives every level basis through the real publish-path validation rather than
   * constructing {@code ExitRuleSpec}s directly; edit either side alone and it goes red.
   */
  private static final Set<String> ENGINE_SIDE_STOP_BASES = Set.of("atr_multiple", "index_points");

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
