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
}
