package in.arthayantra.strategysignal.signals;

/**
 * In-process event: the engine emitted an EXIT resolving an ENTRY anchor. {@code reason} is the
 * exit cause in the paper ledger's {@code close_reason} taxonomy (STRUCTURAL_STOP, CONFLUENCE_FLIP,
 * STOP_LOSS, TAKE_PROFIT, TRAILING_STOP, TIME_STOP, SIGNAL_EXIT). The paper module listens and
 * closes any open position linked to the anchor — without this, a TAKEN entry's position outlived
 * every engine exit (audit P0-2).
 *
 * <p>{@code price} is the settlement price for the paper close, or {@code null} to settle at the live
 * LTP (the tick-engine default). The Phase-9 Minervini swing batch passes the fresh DAILY-BAR close
 * because its equities do NOT tick — an LTP-less close would otherwise book every swing trade at
 * breakeven against the entry price.
 */
public record SignalExited(
    long anchorSignalId, long exitSignalId, String reason, java.math.BigDecimal price) {

  /** Close at the live LTP (the tick-engine path — no explicit settlement price). */
  public SignalExited(long anchorSignalId, long exitSignalId, String reason) {
    this(anchorSignalId, exitSignalId, reason, null);
  }
}
