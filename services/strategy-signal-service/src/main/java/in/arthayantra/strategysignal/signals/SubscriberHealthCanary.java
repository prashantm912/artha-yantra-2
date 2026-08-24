package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Subscriber-side liveness watchdog for the live signal engine's {@code candles.1m.*} Redis
 * subscription (audit {@code signal-eval-redis-subscriber-watchdog}; RCA
 * {@code docs/signal-analysis/2026-07-07-session-findings.md} §8).
 *
 * <p>On 2026-07-07 the {@code signal-eval} loop went silent twice mid-session while the feed stayed
 * healthy — NOT an eval-thread hang and NOT a feed death: {@link SignalEngine}'s
 * {@code RedisMessageListenerContainer} silently dropped its subscription (once recovered, once did
 * not), so the eval executor starved with no error logged and no alert. The market-data
 * {@code DataHealthCanary} could not catch it because it watches bar CLOSES on the PRODUCER side, not
 * consumer receipt.
 *
 * <p>Every minute in-session, if enabled+published strategies exist yet the engine has received no
 * candle for {@code bar-gap-ms} WHILE the feed is provably alive (market-data's
 * {@code ticks:last-at} heartbeat is fresh within {@code feed-fresh-ms}), this force-re-subscribes
 * (overlap-safe) and pages ntfy. The
 * feed-fresh cross-check is the discriminator: a genuine feed outage is the market-data canary's job,
 * so this stays silent then (no false page, no pointless re-subscribe churn). Reading
 * {@code ticks:last-at} on the shared Redis keeps it HTTP-free. Safety net, default ON — a
 * default-OFF net that nobody arms is exactly how the original stall stayed silent; disable via
 * {@code artha.signals.subscriber-watchdog.enabled=false}.
 *
 * <p><b>Eval-side blind spot (audit A13, RC-1, confirmed 2026-07-10):</b> the receive heartbeat
 * ({@code lastBarReceivedAtMs}) is stamped on the Redis DISPATCH thread as the first line of
 * {@code onCandleMessage}, but evaluation runs on a SEPARATE single-thread {@code signal-eval}
 * executor. So a stall INSIDE evaluation keeps the receive heartbeat fresh — the 14:52-IST stall
 * that day paged nobody because the canary read "receiving normally" the whole time. This sweep also
 * compares {@code lastBarReceivedAtMs − lastBarEvaluatedAtMs}: an EVAL stall is bars ARRIVING but not
 * processed for a full {@code bar-gap-ms}. It is compared receipt-to-eval (NOT to wall-clock) so a
 * quiet market that freezes both heartbeats never false-alarms. A blocked eval thread cannot be fixed
 * by re-subscribing (the rebuild would queue behind the block), so on an eval stall this does NOT
 * re-subscribe — it logs a distinct ERROR, captures the {@code signal-eval} stack trace (the forensic
 * evidence the wiped 15:57 logs cost us), pages with a distinct title, and latches once per episode.
 *
 * <p><b>The producer-blind branch RECORDS but still does not remediate (V062, 2026-08-24):</b> when
 * the receive gap coincides with a STALE feed heartbeat this stays out of the way — restarting a dead
 * producer is market-data's job — but until V062 it also wrote nothing at all, so a genuine outage
 * left ZERO durable trace on the engine side. Measured on 2026-08-19: the reconnect gap-backfill
 * repaired the candles (NIFTY 50 finished the session 373/375 bars, 51 of them {@code BACKFILL}) while
 * {@code subscriber_health_events} stayed empty for the day, so a later reader cannot separate "no
 * signal because no setup" from "no signal because the engine was blind". That branch now opens a
 * {@code blind_windows} row and closes it on recovery. It is a RECORD, never a replay trigger: a bar
 * the engine did not see live is not a decision it gets to make later (owner ruling 2026-08-24 —
 * backfill the data, never re-decide the bars). It still does NOT page, because market-data's
 * {@code FeedWatchdog} already pages for the same outage and a second push is pure noise.
 *
 * <p><b>This branch writes ONE durable record, deliberately.</b> Earlier revisions also wrote a
 * {@code feed-blind}/{@code recovery} pair into {@code subscriber_health_events}, and three review
 * rounds went into ordering those two writes safely under partial failure — telemetry before the
 * register lost the artifact, telemetry after the CALL was not the same as after SUCCESS, and
 * discharging it inside {@code flush()} put an unbounded insert BETWEEN an open and its required
 * close. The second write was the problem: {@code blind_windows} already carries the start, the end,
 * the reason and the detail, so the telemetry row was a redundant record whose only real effect was
 * to create an ordering hazard on a watchdog thread. It is gone. The log lines remain, because they
 * cannot block. Other branches of this canary still write telemetry; only the producer-blind path,
 * which owns a better record, does not.
 *
 * <p>Three boundaries of that record are worth knowing before reading one. {@code started_at} is
 * CLAMPED to today's session open — a feed already dead at 09:15 anchors on yesterday's last bar
 * otherwise, and would report the whole night as blindness. {@code ended_at} is the newest receipt
 * seen by the sweep that OBSERVED recovery, not the first bar back, so it OVERSTATES the true end by
 * up to one sweep — normally the {@value #SWEEP_INTERVAL_MS} ms cadence, but longer whenever the
 * sweep itself is delayed, because {@code fixedDelay} is a spacing and not an upper bound. Pinning it
 * exactly would need a transition hook inside the engine's receive path, which is not worth putting
 * on that path. And a
 * BACKWARD clock step is treated as a fault, never as recovery — a negative receive gap satisfies
 * "receiving normally", so acting on it would close a live outage with no bar having arrived.
 *
 * <p><b>Why one global heartbeat is sufficient (not per-channel):</b> {@link SignalEngine} subscribes
 * every candle channel through a SINGLE {@code RedisMessageListenerContainer} on one connection
 * ({@code resubscribe()} builds one container + N listeners on one {@code connectionFactory}), so
 * Redis pub/sub multiplexes all channels over that one subscription — a connection drop takes down
 * ALL channels together, never one in isolation. The 2026-07-07 incident confirms this empirically:
 * NIFTY and SENSEX scalper evaluation both stopped at the SAME instant (14:22:45), not NIFTY-only. So
 * the global {@code lastBarReceivedAtMs} (stamped by any channel) stales exactly when the container
 * drops, and stays fresh while any liquid future (always in a loaded scalper universe) keeps
 * delivering — which is also why a quiet minute can't false-page. Revisit per-channel tracking only
 * if the engine ever moves to per-channel connections.
 */
@Component
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true) // shares SignalEngine's lifecycle — meaningless (and unwireable) without it
public class SubscriberHealthCanary {

  private static final Logger log = LoggerFactory.getLogger(SubscriberHealthCanary.class);
  private static final LocalTime SESSION_START = LocalTime.of(9, 15); // the blind-window floor
  private static final LocalTime ARMED_FROM = LocalTime.of(9, 20); // after warmup + the first 1m bars
  private static final LocalTime SESSION_END = LocalTime.of(15, 30);
  private static final String FEED_HEARTBEAT_KEY = "ticks:last-at"; // epoch millis, written per tick

  /**
   * The sweep cadence, as a constant so {@link SignalFaultInjector} can derive a detector-capable
   * drill duration from the SAME number the scheduler uses. A hardcoded copy over there would drift
   * out of step with this the moment the cadence changes, and the drill would silently stop being
   * able to reach detection.
   */
  static final long SWEEP_INTERVAL_MS = 60_000;

  /** In-process alert event — the notifier module listens (signals must not import notifier). */
  public record SubscriberStallAlert(String title, String message) {}

  private final SignalEngine engine;
  private final StrategyRepository registry;
  private final StringRedisTemplate redis;
  private final ApplicationEventPublisher events;
  private final SubscriberHealthTelemetry telemetry;
  private final BlindWindowRegister blindWindows;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final boolean enabled;
  private final long barGapMs;
  private final long feedFreshMs;

  // Single-writer (the @Scheduled thread never overlaps under fixedDelay); volatile for test/read.
  private volatile boolean stalled; // receive-side latch (subscription drop)
  private volatile boolean evalStalled; // eval-side latch (bars arriving, not processed)
  // The producer-blind episode, or null when there is nothing blind and nothing pending. Held as
  // RETRYABLE state rather than a bare latch: a fail-soft register write that loses the row would
  // otherwise lose the whole artifact for the outage, which is the one thing this feature exists to
  // produce. `id` stays null until the INSERT lands, so a later sweep re-attempts it; `endedAt` set
  // with `id` still null means the close is waiting on that open. Cleared only on durable success.
  private volatile Episode episode;
  // The end of the most recently closed window. A second episode can never start before it, so a
  // `strategies-idle` close followed by a re-enable DURING the same outage cannot backdate the new
  // window over the interval that was already accounted for.
  private volatile Instant lastWindowClosedAt = Instant.EPOCH;
  // HIGH-WATER MARK of the wall clock across sweeps -- it only ever rises. A fixedDelay sweep can
  // only move forward, so a smaller reading means the host clock stepped backwards; see sweep().
  private volatile long clockHighWaterMs;
  private volatile boolean clockWarped; // latch, so one step does not log on every later sweep
  private long lastResubscribeAtMs;

  /** One producer-blind episode. Immutable; every transition replaces it. */
  private record Episode(
      String key, Instant startedAt, String detail, Long id, Instant endedAt, String reason) {
    Episode withId(Long newId) {
      return new Episode(key, startedAt, detail, newId, endedAt, reason);
    }

    Episode closing(Instant at, String why) {
      return new Episode(key, startedAt, detail, id, at, why);
    }
  }

  /** Wires the engine, the shared Redis, the event bus, telemetry, and the (tunable) thresholds. */
  public SubscriberHealthCanary(
      SignalEngine engine,
      StrategyRepository registry,
      StringRedisTemplate redis,
      ApplicationEventPublisher events,
      SubscriberHealthTelemetry telemetry,
      BlindWindowRegister blindWindows,
      Clock clock,
      @Value("${artha.signals.subscriber-watchdog.enabled:true}") boolean enabled,
      @Value("${artha.signals.subscriber-watchdog.bar-gap-ms:180000}") long barGapMs,
      @Value("${artha.signals.subscriber-watchdog.feed-fresh-ms:90000}") long feedFreshMs) {
    this.engine = engine;
    this.registry = registry;
    this.redis = redis;
    this.events = events;
    this.telemetry = telemetry;
    this.blindWindows = blindWindows;
    this.clock = clock;
    this.enabled = enabled;
    this.barGapMs = barGapMs;
    this.feedFreshMs = feedFreshMs;
  }

  /** The receive-gap threshold, so a drill can tell whether it will run long enough to be detected. */
  long barGapMs() {
    return barGapMs;
  }

  /** The per-minute in-session receive-gap + eval-gap check. */
  @Scheduled(
      fixedDelay = SWEEP_INTERVAL_MS,
      initialDelay = 120_000,
      scheduler = "subscriberWatchdogTaskScheduler") // NOT the monitor pool: this sweep writes JDBC
  public void sweep() {
    try {
      if (!enabled) {
        return;
      }
      long sweepAtMs = clock.millis();
      if (sweepAtMs < clockHighWaterMs) {
        // ⚠️ The host clock stepped BACKWARDS, a measured failure on this box (the 87-minute July
        // 2026 drift). Every time computation below is then wrong, so the sweep does nothing at all
        // this cycle rather than acting on any of them.
        //
        // ⚠️ The mark is a HIGH-WATER value and is deliberately NEVER lowered. Resyncing it down to
        // the warped reading protected exactly ONE sweep: the next tick, 09:17 > 09:16, processing
        // resumed and the same false session-ended close went through. The cost is real and worth
        // stating -- after an 87-minute step this canary is DORMANT for 87 minutes, so receive-stall
        // detection is down for that window too. That is the safer half of the trade: a host whose
        // clock jumps needs a human either way, and acting on times known to be wrong manufactures
        // false closes and false stalls on a live money engine. The ERROR line is the signal.
        //
        // This detector replaces two narrower ones that both missed. The negative-receive-gap check
        // sits inside updateBlindWindow, which the session gate returns before reaching; and the
        // "a window cannot end before it began" guard is necessary but NOT sufficient, because a
        // step can land AFTER a clamped start and still before ARMED_FROM -- a start clamped to
        // 09:15 and a step from 10:43 to 09:16 satisfies the interval and still fakes a session
        // boundary. Comparing successive sweeps needs no such reasoning: a fixedDelay sweep only
        // ever moves forward, so a smaller reading is the fault itself rather than a symptom of it.
        if (!clockWarped) {
          clockWarped = true;
          log.error(
              "subscriber watchdog: clock stepped BACKWARDS {}ms — DORMANT until it passes {} again;"
                  + " every window and stall computation would be wrong until then",
              clockHighWaterMs - sweepAtMs,
              Instant.ofEpochMilli(clockHighWaterMs));
        }
        return;
      }
      if (clockWarped) {
        clockWarped = false;
        log.info("subscriber watchdog: clock caught up past {} — resuming", Instant.ofEpochMilli(clockHighWaterMs));
      }
      clockHighWaterMs = sweepAtMs;
      ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
      boolean inSession = inSession(now);
      if (!inSession || registry.countEnabledPublished() == 0) {
        // A window still open here did NOT recover — the outage outlasted the session, or the
        // registry went idle. Say which, so a reader never reads a still-dead feed as healed. This
        // is also the LAST retry: an episode that still cannot be written is dropped with an ERROR
        // rather than carried into the next session, where its start would be a lie.
        // ⚠️ Only drop when the close was actually ATTEMPTED. A backward clock step can make a
        // live session look closed, and requestClose then REFUSES the transition — dropping a
        // pending open there would destroy the episode for an outage that is still running, and if
        // bars recovered while the clock stayed warped no row would ever be created at all.
        if (requestClose(clock.instant(), inSession ? "strategies-idle" : "session-ended")) {
          dropUnflushedEpisode();
        }
        return; // out of session, or genuinely idle by registry intent
      }
      long nowMs = clock.millis();
      long received = engine.lastBarReceivedAtMs();
      long evaluated = engine.lastBarEvaluatedAtMs();
      long receiveGap = nowMs - received; // wall-clock since the last bar RECEIVED
      long evalLag = received - evaluated; // receipt-vs-eval (NOT wall-clock: quiet market freezes both)

      // (0) PRODUCER-BLIND BOOKKEEPING — runs BEFORE any remediation branch returns. An eval stall
      // freezes `evaluated` while a dead feed freezes `received`, so evalLag stays wide and the
      // eval-stall return below would otherwise hide an outage that started underneath it — and,
      // worse, prevent the bars-resumed close, mislabelling a real recovery as `session-ended`
      // hours later. Returns the feed-heartbeat age so the receive branch does not read Redis twice
      // (MAX_VALUE also when it was never read, which that branch cannot reach).
      long feedAge = updateBlindWindow(now, nowMs, received, receiveGap);

      // (1) EVAL STALL — bars ARRIVING but the signal-eval thread is not processing them. Checked
      // FIRST and independently of the receive path: during an eval block receipt is FRESH, so the
      // "receiving normally" early-return below would otherwise mask it entirely. A pure receive-drop
      // can't trip this (evaluated tracks received to ~0 once the last drain completes), and both-frozen
      // quiet markets give evalLag ~0 — so this only fires on the real bars-arriving-not-processed
      // signature. A blocked eval thread cannot be fixed by re-subscribing, so NO re-subscribe here.
      if (evalLag >= barGapMs) {
        if (!evalStalled) {
          evalStalled = true;
          String detail =
              "signal-eval STALLED — bars arriving but not evaluated for " + (evalLag / 1000)
                  + "s (receipt " + (receiveGap / 1000) + "s old)";
          log.error("subscriber watchdog: {}", detail);
          logEvalThreadStack(); // forensic evidence the wiped 15:57 logs cost us (audit A13)
          telemetry.record("eval-stall", detail);
          publish(
              "ArthaYantra subscriber watchdog: signal-eval STALLED",
              detail + " — NOT re-subscribing (a blocked eval thread cannot be fixed by re-subscribe).");
        }
        return; // pure eval stall: no re-subscribe attempt
      }
      if (evalStalled) {
        evalStalled = false;
        log.info("subscriber watchdog: signal-eval RECOVERED (eval lag {}s)", evalLag / 1000);
        telemetry.record("recovery", "signal-eval caught up (lag " + (evalLag / 1000) + "s)");
      }

      // (2) RECEIVE GAP (the 2026-07-07 path) — the container silently dropped its subscription.
      if (receiveGap < barGapMs) {
        if (stalled) {
          stalled = false;
          log.info(
              "subscriber watchdog: candle receipt RECOVERED (last bar {}s ago)", receiveGap / 1000);
          telemetry.record("recovery", "candle receipt recovered (" + (receiveGap / 1000) + "s)");
        }
        return; // receiving normally
      }
      if (feedAge != Long.MAX_VALUE && feedAge > feedFreshMs) {
        return; // remediating a known stale producer is market-data's ownership; the RECORD is ours
      }
      // Feed is fresh, or its heartbeat is unavailable; this consumer received no bar for receiveGap.
      String detail =
          "no candle received for " + (receiveGap / 1000) + "s while the feed is live (ticks "
              + (feedAge / 1000) + "s old) — Redis candles.1m subscription dropped; re-subscribing";
      if (!stalled) {
        if (feedAge == Long.MAX_VALUE) {
          detail =
              "no candle received for "
                  + (receiveGap / 1000)
                  + "s while the feed heartbeat is unavailable; Redis candles.1m subscription is suspicious; re-subscribing";
        }
        // First detection: latch, re-subscribe, and page ONCE.
        stalled = true;
        lastResubscribeAtMs = nowMs;
        log.error("subscriber watchdog: signal engine STARVED — {}", detail);
        telemetry.record("receive-stall", detail);
        engine.forceResubscribe(detail);
        telemetry.record("resubscribe", detail);
        publish(
            "ArthaYantra subscriber watchdog: signal engine STARVED",
            detail + " (live scalper eval was silently stalled).");
      } else if (nowMs - lastResubscribeAtMs >= barGapMs) {
        // Still starved after a full window: retry the re-subscribe (throttled), no repeat page.
        lastResubscribeAtMs = nowMs;
        log.error("subscriber watchdog: still STARVED — retrying re-subscription ({})", detail);
        engine.forceResubscribe(detail);
        telemetry.record("resubscribe", detail);
      }
    } catch (RuntimeException e) {
      log.warn("subscriber watchdog sweep failed: {}", e.toString());
    }
  }

  /**
   * Drives the producer-blind episode and returns the feed-heartbeat age, so the receive branch does
   * not read Redis a second time. Returns {@code MAX_VALUE} when the heartbeat was not read at all —
   * only possible while bars are flowing, which that branch returns before reaching.
   *
   * <p>Deliberately independent of every remediation branch: an eval stall freezes {@code evaluated}
   * while a dead feed freezes {@code received}, so the eval-stall return would otherwise hide an
   * outage that began underneath it AND block the bars-resumed close.
   */
  private long updateBlindWindow(
      ZonedDateTime now, long nowMs, long receivedAtMs, long receiveGapMs) {
    if (receiveGapMs < 0) {
      // The clock stepped BACKWARDS past the receipt stamp — a measured failure class on this host
      // (the July 2026 87-minute drift; SignalEngine.ageSeconds deliberately refuses to clamp it for
      // the same reason). A negative gap satisfies "receiving normally", so acting on it would close
      // a live outage as `bars-resumed` without a single new bar. Hold the episode exactly as it is.
      log.warn(
          "subscriber watchdog: clock stepped backwards ({}ms past the last receipt) — blind-window"
              + " state HELD, neither opened nor closed",
          receiveGapMs);
      return Long.MAX_VALUE;
    }
    if (receiveGapMs < barGapMs) {
      requestClose(Instant.ofEpochMilli(receivedAtMs), "bars-resumed");
      return Long.MAX_VALUE;
    }
    long feedAge = feedAgeMs(nowMs);
    if (feedAge != Long.MAX_VALUE && feedAge > feedFreshMs) {
      openOrRetry(now, receivedAtMs, receiveGapMs, feedAge);
    }
    return feedAge;
  }

  /**
   * Opens the episode, or re-attempts an INSERT that a previous sweep lost. No ntfy push:
   * market-data's {@code FeedWatchdog} already pages for this outage.
   */
  private void openOrRetry(
      ZonedDateTime now, long receivedAtMs, long receiveGapMs, long feedAgeMs) {
    Episode current = episode;
    if (current != null && current.endedAt() != null && !flush()) {
      return; // a close is still unflushed — keep it rather than conflate two outages in one row
    }
    current = episode;
    if (current != null) {
      if (current.id() == null) {
        Long recovered = blindWindows.open(current.key(), current.startedAt(), current.detail());
        if (recovered != null) {
          episode = current.withId(recovered);
        }
      }
      return; // already blind; one row per episode
    }
    Instant startedAt = clampStart(now, Instant.ofEpochMilli(receivedAtMs));
    String detail =
        "no candle received for "
            + (receiveGapMs / 1000)
            + "s and the feed heartbeat is "
            + (feedAgeMs / 1000)
            + "s old — the PRODUCER is blind, so remediation stays market-data's; recording the "
            + "window because the reconnect backfill repairs the CANDLES but never the fact that the "
            + "engine did not see them live";
    // One key per episode, generated ONCE here: every later retry of this INSERT reuses it, so an
    // ambiguous commit resolves to the SAME row instead of a second, permanently-open one.
    String key = UUID.randomUUID().toString();
    episode = new Episode(key, startedAt, detail, blindWindows.open(key, startedAt, detail), null, null);
    log.error("subscriber watchdog: engine BLIND — {}", detail);
  }

  /**
   * Marks the open episode closed and tries to make that durable. No-op when nothing is open.
   *
   * <p>⚠️ Same ordering rule as the open path, for a sharper reason: telemetry used to run BEFORE
   * the close was persisted, so a stall on {@code subscriber_health_events} could block after
   * recovery had been observed but before {@code ended_at} was written — and a restart then loses
   * the in-memory episode and leaves the row open forever, which is exactly the corruption the
   * episode key was added to prevent, arriving by a different route.
   */
  private boolean requestClose(Instant endedAt, String reason) {
    Episode current = episode;
    if (current == null) {
      return true; // nothing to close is not a refusal
    }
    if (endedAt.isBefore(current.startedAt())) {
      // A window cannot end before it began. This is the backward-clock guard's second half, and the
      // review found the first half BYPASSABLE: the negative-receive-gap check lives in
      // updateBlindWindow, which the SESSION GATE returns before ever reaching. The measured
      // 87-minute host drift moves 10:00 IST to 08:33 -- outside ARMED_FROM -- so the sweep took the
      // out-of-session branch and would have persisted `session-ended` with an INVERTED interval
      // while the outage was still running.
      //
      // Guarding HERE rather than at the top of sweep() is deliberate: it covers every caller,
      // including any future one, and states the invariant instead of re-deriving the clock
      // condition. There is deliberately no CHECK constraint for it -- close() is fail-soft and
      // retried, so a row that could never satisfy the constraint would retry forever; a code guard
      // refuses the impossible write instead of generating an unbounded retry.
      log.warn(
          "subscriber watchdog: refusing to close the blind window at {} -- before its start {}; "
              + "the clock stepped backwards. Window HELD.",
          endedAt,
          current.startedAt());
      return false; // REFUSED — the caller must not treat this as a boundary it can clean up after
    }
    if (current.endedAt() == null) {
      episode = current.closing(endedAt, reason);
      lastWindowClosedAt = endedAt; // the next window can never start before this
      log.info("subscriber watchdog: engine blind window closed — {}", reason);
    }
    flush();
    return true;
  }

  /**
   * Pushes whatever the episode still owes to the register. Returns true iff nothing is left
   * pending — so an episode that is open, durable and still blind returns FALSE, which is only ever
   * consulted from a path that has already set {@code endedAt}.
   */
  private boolean flush() {
    Episode current = episode;
    if (current == null) {
      return true;
    }
    if (current.id() == null) {
      Long id = blindWindows.open(current.key(), current.startedAt(), current.detail());
      if (id == null) {
        return false;
      }
      current = current.withId(id);
      episode = current;
    }
    if (current.endedAt() == null) {
      return false; // durable, but the outage is still running
    }
    if (!blindWindows.close(current.id(), current.endedAt(), current.reason())) {
      return false;
    }
    episode = null;
    return true;
  }

  /**
   * Last resort at the session boundary, for the INSERT that never landed only.
   *
   * <p>⚠️ It deliberately does NOT drop an episode that HAS a row. Dropping those was a defect in the
   * first revision of this fix — a close that failed left a durable row open, and clearing the state
   * meant nothing would ever close it again, which is precisely the permanent-loss failure the
   * retryable episode exists to prevent. A row with an id is safe to carry: the retry only sets
   * {@code ended_at} on a row that already exists, so no stale START can leak into the next session.
   * An episode with NO id is the opposite — carrying it would open tomorrow's row with today's
   * start — so that one is dropped, loudly, because a silent drop is how an empty table gets
   * mistaken for a quiet week.
   */
  private void dropUnflushedEpisode() {
    Episode current = episode;
    if (current == null || current.id() != null) {
      return; // nothing pending, or pending a CLOSE on a real row — keep retrying that one
    }
    episode = null;
    log.error(
        "subscriber watchdog: blind window insert never landed before the session boundary — "
            + "dropping it (started {}, reason {})",
        current.startedAt(),
        current.reason());
  }

  /**
   * The floor for a window's start: the last bar receipt, but never earlier than today's session
   * open, and never earlier than the end of the window before it.
   *
   * <p>The session clamp is why a feed already dead at 09:15 does not anchor on YESTERDAY's 15:29
   * bar — or, on a pre-market boot, on the construction-time seed ({@code SignalEngine} stamps both
   * heartbeats at boot as grace for this canary) — and report a whole night of legitimate silence as
   * blindness. The previous-close clamp covers the narrower case the review found: disabling every
   * strategy mid-outage closes the window as {@code strategies-idle}, and re-enabling while the
   * producer is STILL blind would otherwise open a second window backdated across the interval the
   * first one already accounted for.
   */
  private Instant clampStart(ZonedDateTime now, Instant lastReceipt) {
    Instant floor = now.toLocalDate().atTime(SESSION_START).atZone(Ist.ZONE).toInstant();
    if (lastWindowClosedAt.isAfter(floor)) {
      floor = lastWindowClosedAt;
    }
    return lastReceipt.isBefore(floor) ? floor : lastReceipt;
  }

  private void publish(String title, String message) {
    try {
      events.publishEvent(new SubscriberStallAlert(title, message));
    } catch (RuntimeException e) {
      log.warn("subscriber watchdog alert failed: {}", e.getMessage());
    }
  }

  /**
   * Dumps the {@code signal-eval} thread's stack at ERROR — the forensic evidence a container
   * re-create would otherwise erase (audit A13). Best-effort: never throws into the sweep.
   */
  private void logEvalThreadStack() {
    try {
      for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
        if ("signal-eval".equals(entry.getKey().getName())) {
          StringBuilder sb =
              new StringBuilder("signal-eval thread stack (state=")
                  .append(entry.getKey().getState())
                  .append("):");
          for (StackTraceElement frame : entry.getValue()) {
            sb.append("\n\tat ").append(frame);
          }
          log.error(sb.toString());
          return;
        }
      }
      log.error("subscriber watchdog: signal-eval thread not found in the live thread set");
    } catch (RuntimeException dumpFailed) {
      log.warn("subscriber watchdog: signal-eval stack capture failed: {}", dumpFailed.toString());
    }
  }

  /** Age in ms of market-data's {@code ticks:last-at} heartbeat. Returns MAX when unknown/down. */
  private long feedAgeMs(long nowMs) {
    try {
      String raw = redis.opsForValue().get(FEED_HEARTBEAT_KEY);
      if (raw == null) {
        return Long.MAX_VALUE;
      }
      return Math.max(0, nowMs - Long.parseLong(raw));
    } catch (RuntimeException unreadable) {
      log.warn("subscriber watchdog: feed heartbeat unreadable; treating it as suspicious");
      return Long.MAX_VALUE; // unknown feed age is suspicious; the caller must not stay quiet
    }
  }

  private boolean inSession(ZonedDateTime now) {
    try {
      return calendar.isOpen(now.toInstant())
          && !now.toLocalTime().isBefore(ARMED_FROM)
          && now.toLocalTime().isBefore(SESSION_END);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary
    }
  }
}
