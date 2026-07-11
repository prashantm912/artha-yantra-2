package in.arthayantra.strategysignal.insights;

/**
 * Insight severity (INT design §2.1). Ordered least→most urgent so a delivery floor is a simple
 * ordinal compare (§2.5.3 channel floors arm in I3/I4; the ordering is defined here now).
 */
public enum Severity {
  INFO,
  NOTICE,
  WARN,
  CRITICAL
}
