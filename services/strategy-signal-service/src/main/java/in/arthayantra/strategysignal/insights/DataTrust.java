package in.arthayantra.strategysignal.insights;

/**
 * The trust verdict on an insight's load-bearing inputs (INT design §7.2). {@code OK} → advice
 * scores normally; {@code DEGRADED} → advice caps at B-band (§3.2 trust cap) and renders a caveat;
 * {@code BLOCKED} → no advice scored (never rank on bad data, §7.4).
 */
public enum DataTrust {
  OK,
  DEGRADED,
  BLOCKED
}
