package in.arthayantra.strategysignal.paper;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * H44: counts positions that opened on a contract with no usable last tick.
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

  public NoTickFillListener(LastTickReader lastTick, MeterRegistry meterRegistry) {
    this.lastTick = lastTick;
    this.meterRegistry = meterRegistry;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onOpened(PaperPositionOpened e) {
    try {
      if (lastTick.lastTick(e.exchange(), e.tradingsymbol()).isPresent()) {
        return;
      }
      meterRegistry.counter(NO_TICK_FILL_TOTAL, "exchange", e.exchange()).increment();
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
