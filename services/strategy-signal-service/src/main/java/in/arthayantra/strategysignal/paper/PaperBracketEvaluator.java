package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
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
 */
@Component
public class PaperBracketEvaluator {

  private static final Logger log = LoggerFactory.getLogger(PaperBracketEvaluator.class);

  private final PaperPositionRepository positions;
  private final LastTickReader lastTick;
  private final PaperService paper;

  /** Wires the ledger collaborators. */
  public PaperBracketEvaluator(
      PaperPositionRepository positions, LastTickReader lastTick, PaperService paper) {
    this.positions = positions;
    this.lastTick = lastTick;
    this.paper = paper;
  }

  /** Closes every OPEN paper position whose live LTP has breached its stop-loss or take-profit. */
  public int evaluate() {
    int closed = 0;
    for (PositionRow pos : positions.listOpen()) {
      if (pos.stopLoss() == null && pos.takeProfit() == null) {
        continue;
      }
      BigDecimal ltp = lastTick.lastPrice(pos.exchange(), pos.tradingsymbol()).orElse(null);
      if (ltp == null) {
        continue; // no live tick yet — leave the position open
      }
      String reason = breach(pos, ltp);
      if (reason == null) {
        continue;
      }
      try {
        paper.settle(pos, ltp, reason);
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
   * {@code STOP_LOSS} / {@code TAKE_PROFIT} when the position's bracket is breached at this LTP, else
   * {@code null}. Long (BUY): SL is a floor (LTP ≤ SL) and TP a ceiling (LTP ≥ TP); short (SELL)
   * inverts both. SL is checked before TP. Pure (no I/O) so the breach logic is unit-tested directly.
   */
  static String breach(PositionRow pos, BigDecimal ltp) {
    boolean isLong = "BUY".equals(pos.side());
    if (pos.stopLoss() != null) {
      boolean hit = isLong ? ltp.compareTo(pos.stopLoss()) <= 0 : ltp.compareTo(pos.stopLoss()) >= 0;
      if (hit) {
        return "STOP_LOSS";
      }
    }
    if (pos.takeProfit() != null) {
      boolean hit =
          isLong ? ltp.compareTo(pos.takeProfit()) >= 0 : ltp.compareTo(pos.takeProfit()) <= 0;
      if (hit) {
        return "TAKE_PROFIT";
      }
    }
    return null;
  }
}
