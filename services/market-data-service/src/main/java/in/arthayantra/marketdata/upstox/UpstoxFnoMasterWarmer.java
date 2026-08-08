package in.arthayantra.marketdata.upstox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps the Upstox F&amp;O instrument master ({@link UpstoxFnoMasterClient}) loaded so a LIVE margin
 * call never pays its cold load.
 *
 * <p><b>Why.</b> The master is a 5MB+ gzip the client fetches lazily, on the first lookup after its
 * 12-hour {@code REFRESH} window, on its own connect-15s/read-60s timeouts. The F9 heat read arrives
 * through {@code PaperMarginClient}, which allows 2000ms end-to-end — deliberately, so a slow
 * market-data can never park the tick thread (the #694 doctrine), and NOT something to lengthen. So
 * whenever a cold load lands on a live margin call the two budgets race. Measured 2026-08-05: two
 * WARNs at exactly 2000ms, master load completing 535ms later, F9 heat gate inert on the session's
 * only funded entry. Measured 2026-08-06: same cold-load shape, inside budget, no failure. A race,
 * not a constant break — this removes the race by making sure the load has already happened.
 *
 * <p><b>Two triggers, one job.</b> {@link #warmOnStartup()} covers the fresh boot; {@link
 * #warmPeriodically()} covers the service staying up past the refresh window, which is the case that
 * actually bites (the stack runs for days). The period must stay STRICTLY SHORTER than the client's
 * {@code REFRESH} — pinned by {@code UpstoxFnoMasterWarmerTest} — so the cache is always inside its
 * window when a caller arrives and the client's lazy branch never runs on a caller's thread. {@code
 * initialDelay} equals the period so the scheduler's first run does not duplicate the startup warm.
 *
 * <p><b>Fail-soft, three ways.</b> The client is resolved through an {@link ObjectProvider} (absent
 * unless the analytics/quote/ticker flags bind it, so this is a no-op rather than a wiring failure);
 * {@link UpstoxFnoMasterClient#warm()} already swallows transport/gunzip/parse failure and keeps any
 * prior cache; and each trigger additionally catches {@link RuntimeException}. That last catch is not
 * belt-and-braces for an impossible case — an escaped exception from a {@code fixedDelay} task
 * SUPPRESSES ALL ITS FUTURE EXECUTIONS, so a single bad CDN response would silently retire the warm
 * for the life of the process. The CDN is auth-free but can be down; a failed warm must cost nothing
 * more than the pre-existing lazy behaviour.
 */
public class UpstoxFnoMasterWarmer {

  private static final Logger log = LoggerFactory.getLogger(UpstoxFnoMasterWarmer.class);

  private final ObjectProvider<UpstoxFnoMasterClient> master;

  /** Wires the (optional) F&amp;O master client. */
  public UpstoxFnoMasterWarmer(ObjectProvider<UpstoxFnoMasterClient> master) {
    this.master = master;
  }

  /** Warms on boot so the day's first margin call finds the master already loaded. */
  @EventListener(ApplicationReadyEvent.class)
  public void warmOnStartup() {
    warm("startup");
  }

  /**
   * Re-warms inside the client's refresh window, so the window never lapses onto a caller's thread.
   * The first run is one full period after boot — {@link #warmOnStartup()} has already covered t=0.
   */
  @Scheduled(
      initialDelayString = "${artha.upstox.fno-master.warm-interval:PT6H}",
      fixedDelayString = "${artha.upstox.fno-master.warm-interval:PT6H}")
  public void warmPeriodically() {
    warm("scheduled");
  }

  private void warm(String trigger) {
    UpstoxFnoMasterClient client = master.getIfAvailable();
    if (client == null) {
      return; // analytics/quote/ticker flags off — nothing to warm (not an error)
    }
    try {
      client.warm();
      log.debug("Upstox F&O master warm ({}) complete", trigger);
    } catch (RuntimeException e) {
      // NEVER propagate: out of @EventListener this would fail startup, and out of the @Scheduled
      // fixedDelay task it would cancel every future warm.
      log.warn("Upstox F&O master warm ({}) failed — leaving the lazy path in place: {}", trigger, e.toString());
    }
  }
}
