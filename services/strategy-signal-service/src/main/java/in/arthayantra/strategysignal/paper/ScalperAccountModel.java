package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.WinLoss;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Siva's 5-sub-account scalper discipline (master plan §12.7 / cheat sheet §0A) as an enforced ENTRY
 * rail. Two daily caps are derived from the day's CLOSED paper trades (read-time, no per-trade
 * feedback loop), consulted ONLY on a scalper ENTRY (alongside the global {@link RiskService} gate):
 *
 * <ul>
 *   <li><b>First-loss freeze (5 accounts):</b> capital is split across 5 logical sub-accounts; a
 *       losing trade freezes the sub-account that took it for the IST day. Once all 5 are frozen
 *       (5 losing trades), no fresh scalper entry is allowed. Modelled at day granularity — the loss
 *       COUNT against 5 accounts — rather than tracking which account took which trade.
 *   <li><b>5-wins/day cap:</b> after 5 winning trades the day's gains are banked and fresh scalper
 *       entries stop (overtrading is the killer — §2.14).
 * </ul>
 *
 * <p>EXITS are never gated by this — only fresh entries. State is the {@code paper_positions} ledger,
 * so it survives a restart and is consistent with the live account; nothing is held in memory.
 */
@Component
public class ScalperAccountModel {

  /** Logical sub-accounts: a losing trade freezes one; all frozen ⇒ no fresh entry. */
  static final int ACCOUNTS = 5;

  /** Daily winning-trade cap: bank the gains and stop after this many wins. */
  static final int MAX_WINS_PER_DAY = 5;

  private static final Logger log = LoggerFactory.getLogger(ScalperAccountModel.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final PaperPositionRepository positions;
  private final Clock clock;

  /** Wires the closed-trade ledger. */
  public ScalperAccountModel(PaperPositionRepository positions, Clock clock) {
    this.positions = positions;
    this.clock = clock;
  }

  /** False once 5 losses have frozen all sub-accounts OR 5 wins have banked the day. */
  public boolean scalperEntryAllowed() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    WinLoss wl = positions.winLossOn(today);
    if (wl.wins() >= MAX_WINS_PER_DAY) {
      log.info("scalper entries paused — {} wins banked today (cap {})", wl.wins(), MAX_WINS_PER_DAY);
      return false;
    }
    if (wl.losses() >= ACCOUNTS) {
      log.info("scalper entries paused — {} losses froze all {} sub-accounts", wl.losses(), ACCOUNTS);
      return false;
    }
    return true;
  }
}
