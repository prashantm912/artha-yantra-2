package in.arthayantra.marketdata.screener.manas;

/**
 * One detected Manas base for a symbol: a {@code setupType} of {@code "vcp"} or {@code "breakout"},
 * its validity, the Technical Footprint, the buy pivot, the base duration, and (when invalid) the
 * reject reason. Both setup types share this shape so {@link ManasSetupsRepository} can persist them
 * uniformly (one row per {@code (screen_date, symbol, setup_type)}).
 */
public record ManasSetup(
    String setupType,
    boolean valid,
    String footprint,
    double pivot,
    int baseWeeks,
    String rejectReason) {

  /** From the reused VCP detector's footprint. */
  static ManasSetup vcp(
      in.arthayantra.marketdata.screener.minervini.geometry.VcpFootprint f) {
    return f.vcp()
        ? new ManasSetup("vcp", true, f.footprint(), f.pivot(), f.baseWeeks(), null)
        : new ManasSetup("vcp", false, null, 0, 0, f.rejectReason());
  }

  /** From the consolidation-breakout detector's footprint. */
  static ManasSetup breakout(BreakoutFootprint f) {
    return f.valid()
        ? new ManasSetup("breakout", true, f.footprint(), f.pivot(), f.baseWeeks(), null)
        : new ManasSetup("breakout", false, null, 0, 0, f.rejectReason());
  }
}
