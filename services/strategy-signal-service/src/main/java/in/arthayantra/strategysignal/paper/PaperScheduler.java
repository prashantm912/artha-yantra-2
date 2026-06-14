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

  /** Wires the ledger service. */
  public PaperScheduler(PaperService paper) {
    this.paper = paper;
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
