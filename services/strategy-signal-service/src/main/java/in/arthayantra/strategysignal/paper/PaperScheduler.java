package in.arthayantra.strategysignal.paper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The 15:45 IST mark-to-close (§F.6): intraday paper positions are settled at session end so they
 * do not carry overnight. Cron-gated to weekdays — the same posture as the engine's intraday sweep
 * (this service holds no MarketCalendar bean; an NSE-holiday weekday simply has no open intraday
 * paper positions to settle).
 */
@Component
public class PaperScheduler {

  private static final Logger log = LoggerFactory.getLogger(PaperScheduler.class);

  private final PaperService paper;
  private final PaperExpiryService expiry;
  private final PaperBracketEvaluator brackets;

  /** Wires the ledger + expiry + bracket services. */
  public PaperScheduler(
      PaperService paper, PaperExpiryService expiry, PaperBracketEvaluator brackets) {
    this.paper = paper;
    this.expiry = expiry;
    this.brackets = brackets;
  }

  /** Stop-loss / take-profit auto-exit — every 15s during market hours (§4b scalper). */
  @Scheduled(cron = "*/15 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
  public void bracketEvaluation() {
    int closed = brackets.evaluate();
    if (closed > 0) {
      log.info("paper SL/TP auto-exit closed {} position(s)", closed);
    }
  }

  /** 15:30 IST T-1 roll-or-close push for positions expiring tomorrow. */
  @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
  public void t1RolloverPrompt() {
    int sent = expiry.notifyExpiring();
    if (sent > 0) {
      log.info("paper T-1 roll-or-close pushed for {} position(s)", sent);
    }
  }

  /** 15:35 IST expiry settlement (post-close on expiry dates). */
  @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
  public void expirySettlement() {
    int settled = expiry.settleExpiries();
    if (settled > 0) {
      log.info("paper expiry settlement closed {} derivative position(s)", settled);
    }
  }

  /** 15:45 IST intraday mark-to-close. */
  @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
  public void intradayMarkToClose() {
    int closed = paper.markToCloseIntraday();
    if (closed > 0) {
      log.info("paper 15:45 mark-to-close settled {} intraday position(s)", closed);
    }
  }
}
