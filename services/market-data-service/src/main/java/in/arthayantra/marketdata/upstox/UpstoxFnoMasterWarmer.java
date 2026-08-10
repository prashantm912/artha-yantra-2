package in.arthayantra.marketdata.upstox;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
 * {@code REFRESH} — enforced by {@link #clampWarmInterval} and pinned by {@code
 * UpstoxFnoMasterWarmerTest} — so the cache is always inside its window when a caller arrives and
 * the client's lazy branch never runs on a caller's thread. {@code initialDelay} equals the period
 * so the scheduler's first run does not duplicate the startup warm.
 *
 * <p><b>Neither trigger downloads on the thread it was called on.</b> Both only {@link
 * #submit(String)} onto a dedicated daemon executor. That is not tidiness: inline, the boot warm
 * delayed the {@code ApplicationReadyEvent} publisher by up to a 75s worst case (connect + read),
 * and the periodic warm blocked Spring's default scheduler — which is POOL SIZE 1, so every other
 * {@code @Scheduled} task in market-data (the canaries, the ingest jobs) stalled behind a slow CDN.
 *
 * <p><b>Fail-soft, and the retry is deliberately SINGLE-FLIGHT.</b> The client is resolved through
 * an {@link ObjectProvider} (absent unless the analytics/quote/ticker flags bind it, so this is a
 * no-op rather than a wiring failure); {@link UpstoxFnoMasterClient#warm()} swallows
 * transport/gunzip/parse failure and keeps any prior cache, reporting the outcome as a boolean; and
 * each trigger additionally catches {@link RuntimeException}. That last catch is not belt-and-braces
 * for an impossible case — an escaped exception from a {@code fixedDelay} task SUPPRESSES ALL ITS
 * FUTURE EXECUTIONS, so a single bad CDN response would silently retire the warm for the life of the
 * process.
 *
 * <p>{@code retryInFlight} is the load-bearing part of that paragraph. A failed warm schedules one
 * retry, and a retry is only scheduled when none is already outstanding. Without the guard each
 * failed PERIODIC warm starts its own perpetual retry chain, the chains never coalesce, and a
 * multi-day CDN outage multiplies them without bound — one extra 5-minute chain per period. On a
 * single-thread executor, with each attempt costing up to 75s, the chains saturate the thread and
 * the queue grows forever. One outstanding retry is all this needs: the retry re-arms itself while
 * it keeps failing and stops on the first success.
 */
public class UpstoxFnoMasterWarmer {

  private static final Logger log = LoggerFactory.getLogger(UpstoxFnoMasterWarmer.class);
  private static final Duration DEFAULT_WARM_INTERVAL = Duration.ofHours(6);
  private static final Duration MIN_WARM_INTERVAL = Duration.ofMinutes(1);

  /**
   * The ceiling is {@code REFRESH} minus a margin, and the margin must exceed one worst-case
   * download — NOT be merely nonzero. {@code fixedDelay} counts from the previous run's COMPLETION,
   * so consecutive loads land {@code interval + download} apart while the client's window is
   * measured from the load's START. A 1-minute margin is therefore narrower than the client's own
   * 60s read timeout plus its 15s connect: one slow-but-successful download and the window lapses
   * onto a caller's thread — exactly what this class exists to prevent. 5 minutes clears the 75s
   * worst case four times over.
   */
  private static final Duration MAX_WARM_INTERVAL = UpstoxFnoMasterClient.REFRESH.minusMinutes(5);

  private final ObjectProvider<UpstoxFnoMasterClient> master;
  private final ScheduledExecutorService warmExecutor;
  private final Duration retryDelay;

  /** At most one retry may be outstanding; see the single-flight paragraph on the class. */
  private final AtomicBoolean retryInFlight = new AtomicBoolean();

  /**
   * Coalesces concurrent warms — startup, scheduled and retry alike. Distinct from {@link
   * #retryInFlight}, which bounds only the retry CHAIN: this one bounds the DOWNLOAD.
   */
  private final AtomicBoolean warmInFlight = new AtomicBoolean();

  /**
   * Wires the optional F&amp;O master client.
   *
   * <p>No interval parameter, deliberately: the period is read by the {@code @Scheduled} SpEL from
   * the {@code upstoxFnoMasterWarmInterval} bean, so an interval passed here would be inert. An
   * earlier revision did take one, stored it in a field nothing ever read, and clamped it a second
   * time — which made every test that passed a period look like it was configuring the schedule.
   */
  public UpstoxFnoMasterWarmer(ObjectProvider<UpstoxFnoMasterClient> master) {
    this(
        master,
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "upstox-fno-master-warmer");
              thread.setDaemon(true);
              return thread;
            }),
        UpstoxFnoMasterClient.RETRY_BACKOFF);
  }

  UpstoxFnoMasterWarmer(
      ObjectProvider<UpstoxFnoMasterClient> master,
      ScheduledExecutorService warmExecutor,
      Duration retryDelay) {
    this.master = master;
    this.warmExecutor = warmExecutor;
    this.retryDelay = retryDelay;
  }

  /** Clamps the operator-provided period to a safe, positive interval inside the client refresh. */
  static Duration clampWarmInterval(Duration interval) {
    if (interval == null) {
      return DEFAULT_WARM_INTERVAL;
    }
    if (interval.compareTo(MIN_WARM_INTERVAL) < 0) {
      return MIN_WARM_INTERVAL;
    }
    if (interval.compareTo(MAX_WARM_INTERVAL) > 0) {
      return MAX_WARM_INTERVAL;
    }
    return interval;
  }

  /** Submits the boot warm; the ApplicationReadyEvent caller never downloads. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.LOWEST_PRECEDENCE)
  public void warmOnStartup() {
    submit("startup");
  }

  /** Submits the periodic warm; the default Spring scheduler only queues this short dispatch. */
  @Scheduled(
      initialDelayString = "#{@upstoxFnoMasterWarmInterval.toMillis()}",
      fixedDelayString = "#{@upstoxFnoMasterWarmInterval.toMillis()}")
  public void warmPeriodically() {
    submit("scheduled");
  }

  /** Hands the download to the dedicated daemon; the ready listener and scheduler never download. */
  private void submit(String trigger) {
    try {
      warmExecutor.execute(() -> warm(trigger));
    } catch (RejectedExecutionException shuttingDown) {
      // Must not escape: out of the @Scheduled fixedDelay task an exception cancels every future
      // execution, and out of the @EventListener it fails startup. Only reachable once the context
      // is going down, when there is nothing left to warm.
      log.debug("Upstox F&O master warm ({}) not submitted — executor is shut down", trigger);
    }
  }

  private void warm(String trigger) {
    if ("retry".equals(trigger)) {
      retryInFlight.set(false); // this attempt IS the outstanding retry; re-arm on its own failure
    }
    // ⚠️ COALESCE EVERY WARM, not just retries. retryInFlight guards the retry chain alone, and
    // cross-vendor review (2026-08-10) showed that is not enough: Spring's fixedDelay measures the
    // DISPATCH, and submit() returns the instant it hands the task to the executor. So at the
    // one-minute floor the scheduler enqueues a fresh warm every minute regardless of how long the
    // previous download is taking — a 75-second fetch queues them faster than the single daemon
    // drains, and the backlog is a pile of duplicate multi-megabyte downloads against a shared rate
    // limiter.
    //
    // The claim lives HERE rather than in submit() so it covers all three entry points — startup,
    // scheduled, retry — including the retry, which is scheduled directly onto the executor. A tick
    // that arrives mid-warm still costs one queued task, but that task now returns in microseconds
    // instead of downloading, so the queue drains as fast as it fills.
    if (!warmInFlight.compareAndSet(false, true)) {
      log.debug("Upstox F&O master warm ({}) skipped — a warm is already in flight", trigger);
      return;
    }
    try {
      UpstoxFnoMasterClient client = master.getIfAvailable();
      if (client == null) {
        return; // analytics/quote/ticker flags off — nothing to warm (not an error)
      }
      try {
        if (client.warm()) {
          log.debug("Upstox F&O master warm ({}) complete", trigger);
        } else {
          log.warn("Upstox F&O master warm ({}) failed; retrying in {}", trigger, retryDelay);
          scheduleRetry();
        }
      } catch (RuntimeException e) {
        // NEVER propagate: an escaped daemon exception would retire the retry chain.
        log.warn(
            "Upstox F&O master warm ({}) failed; retrying in {}: {}",
            trigger, retryDelay, e.toString());
        scheduleRetry();
      }
    } finally {
      warmInFlight.set(false);
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void scheduleRetry() {
    if (!retryInFlight.compareAndSet(false, true)) {
      return; // a retry is already queued — see the single-flight paragraph on the class
    }
    try {
      warmExecutor.schedule(() -> warm("retry"), retryDelay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException ignored) {
      retryInFlight.set(false);
      // Context shutdown won the race with a failed warm; no retry is needed after shutdown.
    }
  }

  /** Releases the daemon thread on context shutdown. */
  @jakarta.annotation.PreDestroy
  public void shutdown() {
    warmExecutor.shutdownNow();
  }
}
