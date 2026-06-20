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
  LONG_UNWINDING,
  /**
   * No directional read — the OI snapshot was unavailable for this leg. NOT one of the source
   * four states; it exists only so the live assembler can represent "data missing" without a null
   * (which would NPE the gates) and without falsely confirming a side. {@link #bullish()} and
   * {@link #bearish()} are both {@code false}, so an unknown quadrant never confirms a scalp.
   */
  NEUTRAL;

  /** LB / SC favour a long (CE) bias. */
  public boolean bullish() {
    return this == LONG_BUILDUP || this == SHORT_COVERING;
  }

  /** SB / LU favour a short (PE) bias. */
  public boolean bearish() {
    return this == SHORT_BUILDUP || this == LONG_UNWINDING;
  }

  /**
   * Maps a market-data {@code OiInterpretation} name to the local quadrant; any unrecognised or
   * blank value (a missing snapshot, or a state this enum does not mirror) becomes {@link #NEUTRAL}.
   * The four real states share names across the service boundary, so this is a name match — keep it
   * in lock-step with market-data {@code OiInterpretation} (the source-swap principle).
   */
  public static OiQuadrant fromInterpretation(String interpretation) {
    if (interpretation == null) {
      return NEUTRAL;
    }
    return switch (interpretation) {
      case "LONG_BUILDUP" -> LONG_BUILDUP;
      case "SHORT_BUILDUP" -> SHORT_BUILDUP;
      case "SHORT_COVERING" -> SHORT_COVERING;
      case "LONG_UNWINDING" -> LONG_UNWINDING;
      default -> NEUTRAL;
    };
  }
}
