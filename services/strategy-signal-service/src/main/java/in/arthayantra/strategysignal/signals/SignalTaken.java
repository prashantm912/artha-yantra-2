package in.arthayantra.strategysignal.signals;

import java.math.BigDecimal;

/**
 * Published when the owner marks a signal TAKEN. The paper module listens and OPTIONALLY opens a
 * paper position (when a qty is supplied) — an event keeps the signals module free of any compile
 * dependency on paper, so the module graph stays acyclic. {@code scalper} (set by the publisher from
 * the signal's {@code scalper_detail} side-channel) tells the paper listener to charge the open to a
 * 5-account sub-ledger (E10); a non-scalper / manual take leaves it unstamped.
 */
public record SignalTaken(long signalId, Integer qty, BigDecimal fillPrice, boolean scalper) {

  /** Pre-E10 3-arg form: not a scalper take (no sub-account stamping). */
  public SignalTaken(long signalId, Integer qty, BigDecimal fillPrice) {
    this(signalId, qty, fillPrice, false);
  }
}
