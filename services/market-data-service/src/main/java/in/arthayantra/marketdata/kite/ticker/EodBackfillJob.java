package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The 15:45 IST EOD candle backfill + gap audit (B-12), Phase-15A scope: every subscribed
 * instrument — which includes the pinned indices (INDIA VIX among them, FP-14) and the pinned
 * front/next/far FUT contracts (FP-9) — gets a gap-aware 1m pass over today's session plus a 30-day
 * 1d pass. Six-field cron, IST zone (the B-9 cron pitfall).
 */
@Component
public class EodBackfillJob {

  private static final Logger log = LoggerFactory.getLogger(EodBackfillJob.class);

  private final SubscriptionRegistry registry;
  private final FuturesPinner futuresPinner;
  private final GapBackfiller backfiller;
  private final MarketCalendar calendar;
  private final Clock clock;

  /** Wires the audit inputs. */
  public EodBackfillJob(
      SubscriptionRegistry registry,
      FuturesPinner futuresPinner,
      GapBackfiller backfiller,
      MarketCalendar calendar,
      Clock clock) {
    this.registry = registry;
    this.futuresPinner = futuresPinner;
    this.backfiller = backfiller;
    this.calendar = calendar;
    this.clock = clock;
  }

  /** 15:45 IST daily — six-field cron with the explicit IST zone (B-9 pitfall). */
  @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
  public void scheduledEodBackfill() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (!isTradingDaySafe(today)) {
      return;
    }
    backfillNow();
  }

  /** One synchronous pass — callable from tests and the Phase 16 schedule registry. */
  public int backfillNow() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    Instant sessionOpen = today.atTime(MarketCalendar.SESSION_OPEN).atOffset(Ist.OFFSET).toInstant();
    Instant now = clock.instant();
    Instant monthAgo = now.minusSeconds(30L * 24 * 3600);

    Set<InstrumentKey> targets = new LinkedHashSet<>(registry.subscribedKeys());
    targets.addAll(futuresPinner.pinnedContracts());
    for (InstrumentKey key : targets) {
      backfiller.prefetch(key, "1m", sessionOpen, now);
      backfiller.prefetch(key, "1d", monthAgo, now);
    }
    log.info("EOD backfill pass covered {} instruments", targets.size());
    return targets.size();
  }

  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
