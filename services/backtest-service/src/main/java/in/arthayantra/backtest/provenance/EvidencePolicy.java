package in.arthayantra.backtest.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.backtest.replay.options.PremiumProvenance;

/**
 * The family evidence policy a run's numbers obey (evolution design §1.2, roadmap #22b). Stamped on
 * every run so a stored run is self-describing about whether its sim numbers are a ranking plane at all:
 *
 * <ul>
 *   <li>{@link #SIM_FIRST} — swing / momentum (Manas, Minervini): deep history exists and live gates ≈
 *       sim gates, so walk-forward backtests + holdout ARE the ranking evidence.
 *   <li>{@link #LIVE_FIRST} — intraday Siva options scalpers (and BTST post-P0-5): the ~30-rail
 *       confluence gate is live-only and OI/Dow/IV are muted on derived history, so a backtest is
 *       functional SMOKE only — never a ranking plane; the family is judged on forward paper / shadow.
 *   <li>{@link #SIM_BLOCKED} — reserved for a family whose sim evidence is not scorable at all; NOT
 *       auto-derived here (an explicit evolution campaign sets it), but a valid stored value.
 * </ul>
 *
 * <p><b>Derivation.</b> A plain backtest run carries no campaign, so the policy is derived structurally
 * from the config (family-aware by construction, §1.2): a {@code btst} session or an
 * {@code options_of_underlying} universe is LIVE_FIRST; everything else (the swing/candle plane) is
 * SIM_FIRST. The authoritative source for an EVOLVING family is its {@code evo_campaigns.evidence_policy}
 * (set by the optimizer); this run-stamp is a best-effort structural label for the general run.
 */
public enum EvidencePolicy {
  SIM_FIRST,
  LIVE_FIRST,
  SIM_BLOCKED;

  /**
   * The structural evidence policy for a resolved strategy config. {@code btst} and options-scalper
   * strategies are LIVE_FIRST (backtest = smoke); everything else is SIM_FIRST. Never returns
   * SIM_BLOCKED (that is an explicit campaign decision, not a structural default).
   *
   * @param config the resolved (possibly trial-overridden) strategy config; a {@code null}/empty config
   *     falls to SIM_FIRST (the candle-plane default)
   */
  public static EvidencePolicy forConfig(JsonNode config) {
    if (config == null || config.isMissingNode()) {
      return SIM_FIRST;
    }
    String style = config.path("session").path("style").asText("intraday");
    if ("btst".equalsIgnoreCase(style)) {
      return LIVE_FIRST;
    }
    String mode = config.path("universe").path("mode").asText(null);
    if (PremiumProvenance.OPTIONS_MODE.equals(mode)) {
      return LIVE_FIRST;
    }
    return SIM_FIRST;
  }
}
