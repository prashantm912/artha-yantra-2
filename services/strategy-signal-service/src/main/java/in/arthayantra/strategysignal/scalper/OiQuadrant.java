package in.arthayantra.strategysignal.scalper;

/**
 * The oipulse 4-state OI interpretation (price direction × OI direction), mirrored locally in
 * strategy-signal-service. market-data owns the source enum {@code OiInterpretation}; classes cannot
 * be imported across services, so this is a deliberate domain-record duplication (the source-swap
 * principle — strategies depend only on local domain records, never a service's wire shape). Keep the
 * four states in lock-step with market-data {@code OiInterpretation}.
 */
public enum OiQuadrant {
  /** price↑, OI↑ — fresh longs. */
  LONG_BUILDUP,
  /** price↓, OI↑ — fresh shorts. */
  SHORT_BUILDUP,
  /** price↑, OI↓ — shorts exiting. */
  SHORT_COVERING,
  /** price↓, OI↓ — longs exiting. */
  LONG_UNWINDING;

  /** LB / SC favour a long (CE) bias. */
  public boolean bullish() {
    return this == LONG_BUILDUP || this == SHORT_COVERING;
  }

  /** SB / LU favour a short (PE) bias. */
  public boolean bearish() {
    return this == SHORT_BUILDUP || this == LONG_UNWINDING;
  }
}
