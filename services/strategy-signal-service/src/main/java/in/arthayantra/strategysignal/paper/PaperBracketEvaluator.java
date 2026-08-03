package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.signals.Books;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stop-loss / take-profit auto-exit for paper positions (Phase 4b scalper cockpit). Polled by the
 * {@link PaperScheduler} during market hours: each OPEN position carrying a bracket level is checked
 * against its live LTP (the same Redis last-tick the MTM reads), and closed through the normal settle
 * path — {@code close_reason} STOP_LOSS / TAKE_PROFIT — when a level is breached. A position with no
 * bracket levels is untouched (the engine behaves exactly as before). Paper-only: live broker bracket
 * orders are a separate concern and never placed here.
 *
 * <p>Audit V3: a dead/absent option tick used to defer a stop SILENTLY (the position simply stayed
 * open every pass). The close DECISION is unchanged — a stop still can't fire without a price — but
 * every un-evaluated pass is now surfaced via {@link PaperStaleTickAlerter} (counter + a once-a-day
 * ntfy once starvation persists past the threshold). Visibility only; nothing new is closed.
 */
@Component
public class PaperBracketEvaluator {

  private static final Logger log = LoggerFactory.getLogger(PaperBracketEvaluator.class);

  private final PaperPositionRepository positions;
  private final LastTickReader lastTick;
  private final PaperService paper;
  private final PaperStaleTickAlerter staleTicks;

  /** Wires the ledger collaborators + the stale-tick visibility instrument. */
  public PaperBracketEvaluator(
      PaperPositionRepository positions,
      LastTickReader lastTick,
      PaperService paper,
      PaperStaleTickAlerter staleTicks) {
    this.positions = positions;
    this.lastTick = lastTick;
    this.paper = paper;
    this.staleTicks = staleTicks;
  }

  /** Closes every OPEN paper position whose live LTP has breached its stop-loss or take-profit. */
  public int evaluate() {
    int closed = 0;
    for (PositionRow pos : positions.listOpen()) {
      if (pos.stopLoss() == null && pos.takeProfit() == null) {
        continue;
      }
      Optional<LastTickReader.TickView> tick = lastTick.lastTick(pos.exchange(), pos.tradingsymbol());
      BigDecimal ltp = tick.map(LastTickReader.TickView::price).orElse(null);
      String reason = ltp == null || eodManaged(pos) ? null : breach(pos, ltp);
      if (reason == null) {
        // No level to act on this pass (tick absent, or price inside the band) — the SAME "leave the
        // position open" outcome as before. Additive audit-V3 visibility: flag a bracket that is being
        // starved of a fresh tick so a dead-tick-deferred stop no longer hides in silence.
        staleTicks.observeBracket(pos, tick);
        continue;
      }
      try {
        // EMPTY ⇒ a concurrent closer (an engine exit, a manual close, the expiry sweep) won the CAS
        // and this pass closed nothing. Counting or logging it anyway asserted an exit that did not
        // happen, at a price that was not the settlement price — a confident, wrong audit record for
        // anyone reconstructing why a position left the book, and an inflated
        // "auto-exit closed N position(s)" in PaperScheduler (§9-05).
        if (paper.settle(pos, ltp, reason).isEmpty()) {
          log.debug(
              "paper bracket lost the close race for position {} — already closed elsewhere", pos.id());
          continue;
        }
        staleTicks.bracketRecovered(pos.id());
        closed++;
        log.info(
            "paper {} closed position {} {}:{} at {}",
            reason,
            pos.id(),
            pos.exchange(),
            pos.tradingsymbol(),
            ltp);
      } catch (Exception e) {
        log.warn("paper bracket close failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return closed;
  }

  /**
   * True for a holding of an EOD-managed swing book — the books this poller must never price.
   *
   * <p>PR #1251. The level in {@code paper_positions.stop_loss} is written ONCE, at entry, by the daily
   * swing batch, off that position's entry price on the corporate-action plane that was current then;
   * the only other writer is a human {@code PATCH .../brackets}. Nothing re-scales it when a split or
   * bonus retroactively re-planes the market, so comparing it to a live LTP is comparing two different
   * planes: a 1:2 split halves the tick while the stored stop stays whole, and this poller would stop
   * the holding out on the ex-date MORNING — at a price that never happened, and before the daily batch
   * (whose own copy of this hazard #1251 fixed in {@code SwingBatchEngine.entryReference}) ever runs.
   *
   * <p>Until now that was unreachable only by accident of what is subscribed: no cash equity is on the
   * live tick feed, so {@code lastTick} returns empty and {@code breach} is never called (measured, and
   * the reason all 9 automated swing closes to date came from the 20:05 batch, not a 15-second poll —
   * docs/signal-analysis/2026-08-02-manas-exit-stop-doctrine.md §3). The other half of the arming
   * condition was ALREADY true: every open swing holding carries a non-null {@code stop_loss}. One tick
   * was the entire remaining distance, and nothing marked the transition. This makes the doctrine — "the
   * swing books stay EOD-managed" (§1, merged) — true in code, so the exposure is structurally
   * impossible rather than accidentally inert.
   *
   * <p><b>This removes no exit.</b> It cannot strand a position: the swing batch is these books' exit
   * path and remains so (its trailing stop and its own {@code stop_loss} rule both settle off the daily
   * bar, never off a tick), and this poller has never closed one of these holdings. {@code
   * SwingEquityBracketTripwireIntegrationTest} pins both halves — skipped here, still exited there.
   *
   * <p>Keyed on the compile-time {@link Books#eodManaged()} and deliberately NOT on the tunable {@code
   * artha.paper.eod-managed-books} property, so no operator value can re-open this.
   */
  private static boolean eodManaged(PositionRow pos) {
    return Books.eodManaged().contains(pos.book());
  }

  /**
   * {@code STOP_LOSS} / {@code TAKE_PROFIT} when the position's bracket is breached at this LTP, else
   * {@code null}. Long (BUY): SL is a floor (LTP ≤ SL) and TP a ceiling (LTP ≥ TP); short (SELL)
   * inverts both. SL is checked before TP. Pure (no I/O) so the breach logic is unit-tested directly.
   */
  static String breach(PositionRow pos, BigDecimal ltp) {
    return breach(pos.side(), pos.stopLoss(), pos.takeProfit(), ltp);
  }

  /**
   * The same breach test over raw {@code side} + bracket levels (either level nullable). Extracted so
   * a bracket EDIT ({@code PATCH .../brackets}) can validate a proposed level against the current LTP
   * with the EXACT inequalities the live evaluator uses — a level that passes this check will not be
   * insta-closed on the next poll, and a level that would immediately fire is rejected up front. Pure.
   */
  static String breach(String side, BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal ltp) {
    boolean isLong = "BUY".equals(side);
    if (stopLoss != null) {
      boolean hit = isLong ? ltp.compareTo(stopLoss) <= 0 : ltp.compareTo(stopLoss) >= 0;
      if (hit) {
        return "STOP_LOSS";
      }
    }
    if (takeProfit != null) {
      boolean hit = isLong ? ltp.compareTo(takeProfit) >= 0 : ltp.compareTo(takeProfit) <= 0;
      if (hit) {
        return "TAKE_PROFIT";
      }
    }
    return null;
  }
}
