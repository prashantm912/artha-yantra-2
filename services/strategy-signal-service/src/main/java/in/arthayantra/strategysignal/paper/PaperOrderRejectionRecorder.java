package in.arthayantra.strategysignal.paper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a REFUSED paper-order attempt (audit P1-4). {@code PaperService.openOrder} rejects a fill by
 * THROWING — a stale last tick ({@code DATA_STALE_TICK}) or no reference price ({@code NO_PRICE}) — BEFORE
 * any {@code paper_orders} row exists, and that throw rolls back the fill transaction. So each write runs
 * in a {@link Propagation#REQUIRES_NEW} transaction that commits independently of the fill rollback; the
 * caller wraps the call fail-soft, so a ledger hiccup can never mask the rejection the order path surfaces.
 * A separate bean (not a self-invocation) so the REQUIRES_NEW proxy actually engages.
 */
@Component
public class PaperOrderRejectionRecorder {

  private final PaperOrderRejectionRepository repo;

  /** Wires the append-only rejections ledger. */
  public PaperOrderRejectionRecorder(PaperOrderRejectionRepository repo) {
    this.repo = repo;
  }

  /** DATA_STALE_TICK: the fill's last tick was older than the freshness bound (#694). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordStaleTick(
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      long tickAgeMs,
      long maxAgeMs) {
    repo.insert(
        signalId, book, exchange, tradingsymbol, side, qty, "DATA_STALE_TICK",
        "last tick " + tickAgeMs + "ms old > " + maxAgeMs + "ms", tickAgeMs);
  }

  /** NO_PRICE: no reference price was available to strike the fill. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordNoPrice(
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty) {
    repo.insert(
        signalId, book, exchange, tradingsymbol, side, qty, "NO_PRICE",
        "no price available to fill", null);
  }

  /** DEPLOYMENT_BLOCKED: filling this order would push the book past its max-deployment cap. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordDeploymentBlocked(
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String detail) {
    repo.insert(
        signalId, book, exchange, tradingsymbol, side, qty, "DEPLOYMENT_BLOCKED", detail, null);
  }

  /**
   * DATA_GAP_LOT_SIZE: the instrument master carries no lot size for this DERIVATIVE, so the entry
   * was refused rather than sized against a fabricated lot of 1. Distinct from {@code ZERO_SIZE},
   * which means the entry WAS priced against a known lot and could not afford one — conflating them
   * makes the forensic row answer "why did this entry not happen?" with the wrong cause.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordUnknownLot(
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      String detail) {
    repo.insert(
        signalId, book, exchange, tradingsymbol, side, 0L, "DATA_GAP_LOT_SIZE", detail, null);
  }

  /** ZERO_SIZE: the emitted entry was unaffordable at its option premium and opened no order. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordZeroSize(
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      String detail) {
    repo.insert(signalId, book, exchange, tradingsymbol, side, 0L, "ZERO_SIZE", detail, null);
  }

  /**
   * DATA_GAP_NEVER_TICKED (H44): the contract has never produced a tick, so an opened position
   * could not be settled by any AUTOMATIC exit — every exit refuses without a real tick rather than
   * fabricate a price (#694). Deliberately DISTINCT from {@code DATA_GAP_LOT_SIZE} (master data has
   * no lot) and from a stale-tick refusal (a tick exists, it is merely old): conflating them makes
   * the forensic row answer "why did this entry not happen?" with the wrong cause.
   *
   * <p>Only ever written while {@code artha.paper.refuse-no-tick-entries} is ARMED. With the flag
   * off no row appears here and the condition surfaces as {@code ay_paper_fill_no_tick_total}
   * instead — the counter says it HAPPENED, this row says it was REFUSED.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordNeverTicked(
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String detail) {
    repo.insert(
        signalId, book, exchange, tradingsymbol, side, qty, "DATA_GAP_NEVER_TICKED", detail,
        null);
  }

  /**
   * DATA_GAP_CLOSABILITY_UNKNOWN (H44, fail-closed): the tick store could not be reached, so
   * closability could not be VERIFIED. Distinct from {@code DATA_GAP_NEVER_TICKED} on purpose --
   * "we could not ask" and "we asked and the answer was no" are different operational facts, and
   * conflating them would send an operator hunting a dead instrument during a Redis outage.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordClosabilityUnknown(
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String detail) {
    repo.insert(
        signalId, book, exchange, tradingsymbol, side, qty, "DATA_GAP_CLOSABILITY_UNKNOWN",
        detail, null);
  }
}
