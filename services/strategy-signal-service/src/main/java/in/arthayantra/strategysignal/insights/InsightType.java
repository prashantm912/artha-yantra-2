package in.arthayantra.strategysignal.insights;

/**
 * The insight type catalog (INT design §2.2). I1 ships the three display-only generators; the enum
 * grows additively as later waves add types (each type = one generator class).
 */
public enum InsightType {
  /** On {@code SignalEmitted} (+ re-eval): the priority-ranked signal insight (§3.2). */
  SIGNAL_PRIORITY,
  /** On an ingest hole / capture stall: a data-family trust transition (§7.3). */
  DATA_TRUST,
  /** On the risk sweep: a paper-book heat / concentration warning (§3.2 risk row). */
  RISK_HEAT
}
