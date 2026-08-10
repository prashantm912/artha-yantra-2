package in.arthayantra.marketdata.upstox;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

/** Keeps the Upstox F&amp;O instrument master warm without blocking a framework-owned thread. */
public class UpstoxFnoMasterWarmer {

  private static final Logger log = LoggerFactory.getLogger(UpstoxFnoMasterWarmer.class);
  private static final Duration DEFAULT_WARM_INTERVAL = Duration.ofHours(6);
  private static final Duration MIN_WARM_INTERVAL = Duration.ofMinutes(1);
  private static final Duration MAX_WARM_INTERVAL =
      UpstoxFnoMasterClient.REFRESH.minusMinutes(1);

  private final ObjectProvider<UpstoxFnoMasterClient> master;
  private final ScheduledExecutorService warmExecutor;
  private final Duration warmInterval;
  private final Duration retryDelay;

  /** Wires the optional F&amp;O master client and its bounded refresh interval. */
  public UpstoxFnoMasterWarmer(
      ObjectProvider<UpstoxFnoMasterClient> master, Duration warmInterval) {
    this(
        master,
        warmInterval,
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
      Duration warmInterval,
      ScheduledExecutorService warmExecutor,
      Duration retryDelay) {
    this.master = master;
    this.warmInterval = clampWarmInterval(warmInterval);
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
    warmExecutor.execute(() -> warm(trigger));
  }

  private void warm(String trigger) {
    UpstoxFnoMasterClient client = master.getIfAvailable();
    if (client == null) {
      return; // analytics/quote/ticker flags off — nothing to warm (not an error)
    }
    try {
      if (client.warm()) {
        log.debug("Upstox F&O master warm ({}) complete", trigger);
      } else {
        log.warn(
            "Upstox F&O master warm ({}) failed; retrying in {}", trigger, retryDelay);
        scheduleRetry();
      }
    } catch (RuntimeException e) {
      // NEVER propagate: an escaped daemon exception would retire the retry chain.
      log.warn(
          "Upstox F&O master warm ({}) failed; retrying in {}: {}",
          trigger,
          retryDelay,
          e.toString());
      scheduleRetry();
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void scheduleRetry() {
    try {
      warmExecutor.schedule(() -> warm("retry"), retryDelay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException ignored) {
      // Context shutdown won the race with a failed warm; no retry is needed after shutdown.
    }
  }

  /** Releases the daemon thread on context shutdown. */
  @jakarta.annotation.PreDestroy
  public void shutdown() {
    warmExecutor.shutdownNow();
  }
}
