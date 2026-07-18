package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F9 advisory: after a paper position opens, prices its broker-real SPAN margin (Upstox, via
 * market-data's {@link PaperMarginClient}) and stamps {@code margin_snapshot} + {@code margin_pct}
 * (margin as % of book equity) on the row. Purely advisory — it never blocks a fill or changes qty.
 *
 * <p>Runs AFTER_COMMIT (like {@code TakenSignalResolver}/{@code AutoJournalListener}) so an
 * annotation failure can never roll back the fill, and additionally {@code @Async} on the existing
 * {@code notifierExecutor} pool so the HTTP margin round-trip runs off the ledger-write thread. The
 * async listener has no bound transaction — {@code updateMarginSnapshot} uses its own JdbcTemplate
 * connection, guarded {@code WHERE status='OPEN'} so a race with a close is a safe no-op. Fail-soft:
 * an unpriced quote (cash-equity leg Upstox does not margin / analytics token off / market-data
 * unreachable) leaves the columns NULL and logs at debug — never touches the position.
 */
@Component
public class PaperMarginAnnotator {

  private static final Logger log = LoggerFactory.getLogger(PaperMarginAnnotator.class);

  private final PaperPositionRepository positions;
  private final PaperMarginClient marginClient;
  private final PaperAccountService account;

  /** Wires the ledger + the margin client + the per-book equity source. */
  public PaperMarginAnnotator(
      PaperPositionRepository positions,
      PaperMarginClient marginClient,
      PaperAccountService account) {
    this.positions = positions;
    this.marginClient = marginClient;
    this.account = account;
  }

  /** Prices the just-opened position's SPAN margin and stamps the advisory columns (fail-soft). */
  @Async("notifierExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPaperPositionOpened(PaperPositionOpened event) {
    try {
      PaperMarginClient.Quote quote =
          marginClient.margin(
              List.of(
                  new PaperMarginClient.Leg(
                      event.exchange(),
                      event.tradingsymbol(),
                      (int) event.qty(),
                      event.side(),
                      "D")));
      if (!quote.priced() || quote.spanMargin() == null) {
        // Unpriced re-quote: CLEAR any prior snapshot rather than preserve it (EXT-03 finding 2). An
        // averaged add re-fires this event with the grown qty (PaperService: "an averaged add still
        // re-prices"); if the re-quote is now unpriced, keeping the earlier priced value would leave a
        // STALE, wrong-qty margin_snapshot/margin_pct on the row — which BookHeatReader sums into the
        // risk-heat insight, understating heat. Clearing to null makes an unpriced state visibly
        // unpriced everywhere (BookHeatReader filters NULL margin_pct out of its sum). A never-priced
        // position is already null, so the clear is a harmless no-op there.
        log.debug(
            "position {} margin unpriced ({}) — clearing any prior snapshot",
            event.positionId(),
            quote.unpricedReason());
        positions.updateMarginSnapshot(event.positionId(), null, null);
        return;
      }
      BigDecimal margin = quote.spanMargin();
      BigDecimal equity = account.equity(event.book());
      BigDecimal marginPct =
          equity.signum() > 0
              ? margin.multiply(BigDecimal.valueOf(100)).divide(equity, 2, RoundingMode.HALF_UP)
              : null;
      positions.updateMarginSnapshot(event.positionId(), margin, marginPct);
    } catch (RuntimeException e) {
      log.warn("F9 margin annotation failed for position {}: {}", event.positionId(), e.getMessage());
    }
  }
}
