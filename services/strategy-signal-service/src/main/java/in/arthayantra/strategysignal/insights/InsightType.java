package in.arthayantra.strategysignal.insights;

/**
 * The insight type catalog (INT design §2.2). I1 shipped the three display-only generators
 * (SIGNAL_PRIORITY/DATA_TRUST/RISK_HEAT); I2 adds the context/rejection/hygiene/expiry breadth; I3
 * adds the strategy-evidence + sell-decision generators. The enum grows additively as later waves add
 * types (each type = one generator class).
 */
public enum InsightType {
  /** On {@code SignalEmitted} (+ re-eval): the priority-ranked signal insight (§3.2). */
  SIGNAL_PRIORITY,
  /** On an ingest hole / capture stall: a data-family trust transition (§7.3). */
  DATA_TRUST,
  /** On the risk sweep: a paper-book heat / concentration warning (§3.2 risk row). */
  RISK_HEAT,
  /** I2 — 15-min sweep: a digest threshold crossing since open (PCR / max-pain / straddle / …, §6.2). */
  CONTEXT_SHIFT,
  /** I2 — 15-min sweep: a coarse market-regime read (VIX band / day-range regime, §2.2). */
  MARKET_STRUCTURE,
  /** I2 — 15-min sweep over rejections: the top rejections closest to firing this session (§4.2). */
  REJECTION_NEARMISS,
  /** I2 — EOD sweep: a rail whose block share spiked vs its trailing-session mean (§4.2). */
  REJECTION_RAIL_TREND,
  /** I2 — EOD sweep: journal / positions hygiene nudges (unrated auto entries, §2.2). */
  HYGIENE,
  /** I2 — T-1 sweep: open derivative positions expiring tomorrow + holiday proximity (§2.2). */
  EXPIRY_EVENT,
  /** I2 — risk sweep: SL/TP evaluation degraded because the feed stalled on a held instrument (§7.4). */
  RISK_STALE_TICK,
  /** I2 — weekly EOD job: the usefulness/consistency quality report as an insight's evidence (§10.2). */
  QUALITY_REPORT,
  /** I3 — 21:10 nightly (post-graduation-eval): graduation-criterion threshold crossings (§5.2). */
  STRATEGY_EVIDENCE,
  /** I3 — post-swing-batch: an unacknowledged swing SELL verdict, escalating while un-acked (§5.3). */
  SELL_DECISION
}
