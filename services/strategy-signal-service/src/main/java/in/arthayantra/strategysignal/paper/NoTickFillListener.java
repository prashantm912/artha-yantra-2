package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * H44: counts OPTION positions that opened on a contract with no usable last tick.
 *
 * <p><b>Why this matters.</b> Automatic exits refuse to settle without a REAL tick — the #694
 * doctrine: settles use the last real tick at any age and refuse only when none was ever seen, never
 * fabricating a price. So a position opened on a never-ticked contract cannot be closed by any
 * automatic path. Measured 2026-08-28: two SENSEX legs sat open through their TIME_STOPs, their
 * signal-exit and the 15:44 square-off, accruing 1,973 starved-bracket WARNs and holding two
 * sub-accounts allocation-dead, and were only released by a manual explicit-price close.
 *
 * <p><b>AFTER_COMMIT, and off the trade transaction — both deliberate, both from review.</b>
 *
 * <ul>
 *   <li><b>Not inline in the fill.</b> An earlier cut probed inside {@code PaperService} before the
 *       no-price rejection, the lot/risk/cap checks and the commit, so it counted attempts that were
 *       REJECTED or ROLLED BACK — including an atomic pair whose second leg failed. A count of
 *       "fills" that includes non-fills is worse than no count.
 *   <li><b>Not on the money path.</b> That same probe read Redis directly, and
 *       {@code LastTickReader}'s HGET sits OUTSIDE its own catch — so a Redis blip would have
 *       aborted a fill. A diagnostic must never be able to break the thing it observes. Here the
 *       read happens after the trade is durable, and is fail-soft besides.
 * </ul>
 *
 * <p><b>Wording is precise on purpose:</b> "no usable last tick", because a malformed tick also
 * returns empty; and "AUTOMATIC exits", because an explicit-price manual close still works — that is
 * exactly how the 2026-08-28 positions were released, so a message claiming nothing can close them
 * would be falsified by the documented recovery.
 *
 * <p>Alert on INCREMENTS, not the cumulative value: the counter is a per-fill event, not a gauge of
 * how many positions are currently stuck.
 */
@Component
public class NoTickFillListener {

  private static final Logger log = LoggerFactory.getLogger(NoTickFillListener.class);

  /** Tagged by exchange; the book is on the event but the exchange is what bounds the tick feed. */
  static final String NO_TICK_FILL_TOTAL = "ay_paper_fill_no_tick_total";

  private final LastTickReader lastTick;
  private final MeterRegistry meterRegistry;

  /**
   * H44 durability (V065). The counter alone CANNOT carry the weekly rate the arming decision rests
   * on -- it is process-lifetime and resets on every restart, and this stack restarts often. Found
   * 2026-08-29 when the weekly report's first run read 11 minutes of a Saturday.
   */
  private final PaperFillObservationRepository observations;

  public NoTickFillListener(
      LastTickReader lastTick,
      MeterRegistry meterRegistry,
      PaperFillObservationRepository observations) {
    this.lastTick = lastTick;
    this.meterRegistry = meterRegistry;
    this.observations = observations;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onOpened(PaperPositionOpened e) {
    // OPTION ONLY -- and this was MISSING in round 1, which repeated the exact defect the GATE
    // was carefully written to avoid (cross-vendor review, round 2). EQUITIES DO NOT TICK, so an
    // unfiltered counter is dominated by cash-equity swing opens that are in no danger at all:
    // their automatic exits settle at an EXPLICIT session price (EngineExitListener), never off a
    // tick. Two consequences, both bad: the counter saturates on a permanent structural
    // condition exactly as the MTM-blind gauge once did, and it stops being the thing the yml
    // says it is -- the measured rate at which the OPTION-scoped gate WOULD fire, which is the
    // number the arming decision rests on.
    if (e.instrumentClass() != InstrumentClass.OPTION) {
      return;
    }
    try {
      if (lastTick.lastTick(e.exchange(), e.tradingsymbol()).isPresent()) {
        return;
      }
      meterRegistry.counter(NO_TICK_FILL_TOTAL, "exchange", e.exchange()).increment();

      // DURABLE first-class record, not just the counter. Written INSIDE the same try, so a failure
      // here is caught by the existing fail-soft handler and can never touch the (already durable)
      // trade. The counter stays for alerting on increments; this row is what a weekly rate reads.
      observations.record(
          e.positionId(), e.book(), e.exchange(), e.tradingsymbol(), e.qty());
      log.warn(
          "paper position {} opened on {}:{} with NO usable last tick — AUTOMATIC exits cannot"
              + " settle it until one arrives (an explicit-price manual close still can). H44.",
          e.positionId(),
          e.exchange(),
          e.tradingsymbol());
    } catch (RuntimeException diagnosticFailed) {
      // Fail-soft, and loudly enough to be findable: the trade is already durable, so nothing here
      // may affect it. Only the message is logged -- a tick read carries no credential, but the
      // habit of not echoing upstream payloads is worth keeping.
      log.warn(
          "H44 no-tick check failed for position {} ({}) — the fill is unaffected",
          e.positionId(),
          diagnosticFailed.getMessage());
    }
  }
}
