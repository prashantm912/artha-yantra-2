package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SubscriberHealthCanary.SubscriberStallAlert;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit-proves the subscriber watchdog's decision table. RECEIVE-STALL path: force-re-subscribe +
 * page ONLY when the engine has 1m subs, received no bar for {@code bar-gap-ms}, AND the feed is
 * provably fresh; silent on a feed outage, on healthy receipt, and when nothing is subscribed.
 * EVAL-STALL path (audit A13): page + capture the stack but do NOT re-subscribe when bars are
 * ARRIVING (receipt fresh) yet not evaluated for a full gap, and stay silent when a quiet market
 * freezes both heartbeats. Fixed clock at 10:00 IST on 2026-07-07 (a Tuesday NSE session).
 */
class SubscriberHealthCanaryTest {

  private static final long BAR_GAP = 180_000;
  private static final long FEED_FRESH = 90_000;
  // 2026-07-07T04:30:00Z == 10:00 IST, an open NSE session.
  private static final Instant IN_SESSION = Instant.parse("2026-07-07T04:30:00Z");
  private static final Clock CLOCK = Clock.fixed(IN_SESSION, ZoneOffset.UTC);
  private static final long NOW_MS = IN_SESSION.toEpochMilli();

  private final SignalEngine engine = mock(SignalEngine.class);
  private final StrategyRepository registry = mock(StrategyRepository.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final SubscriberHealthTelemetry telemetry = mock(SubscriberHealthTelemetry.class);
  private final BlindWindowRegister blindWindows = mock(BlindWindowRegister.class);

  /** The register accepted the write - the default `false` would leave every episode pending. */
  private void registerAccepts() {
    when(blindWindows.open(anyString(), any(Instant.class), anyString())).thenReturn(7L);
    when(blindWindows.close(any(), any(Instant.class), anyString())).thenReturn(true);
  }

  private SubscriberHealthCanary canary(boolean enabled) {
    when(registry.countEnabledPublished()).thenReturn(1L);
    return new SubscriberHealthCanary(
        engine, registry, redis, events, telemetry, blindWindows, CLOCK, enabled, BAR_GAP,
        FEED_FRESH);
  }

  /** Eval keeps pace with receipt (evalLag ~0) — so the eval-stall branch never trips these tests. */
  private void evalKeepingUp(long receivedAtMs) {
    when(engine.lastBarReceivedAtMs()).thenReturn(receivedAtMs);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(receivedAtMs);
  }

  private void feedAgeMs(long ageMs) {
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - ageMs));
  }

  @Test
  void freshFeedButNoBarReceived_resubscribesAndPagesOnce() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000); // 200s > 180s gap, eval kept pace before the drop
    feedAgeMs(10_000); // feed ticked 10s ago — provably alive

    SubscriberHealthCanary c = canary(true);
    c.sweep();
    c.sweep(); // second pass, same fixed clock: latched — must NOT re-page or re-subscribe

    verify(engine, times(1)).forceResubscribe(anyString());
    verify(events, times(1)).publishEvent(any(SubscriberStallAlert.class));
    // durable telemetry: one receive-stall row + one resubscribe row (latched → not repeated)
    verify(telemetry, times(1)).record(eq("receive-stall"), anyString());
    verify(telemetry, times(1)).record(eq("resubscribe"), anyString());
  }

  /**
   * Producer-blind: REMEDIATION stays market-data's (no re-subscribe, and no second ntfy push on top
   * of the FeedWatchdog's) but the window is now RECORDED (V062) — before that this branch wrote
   * nothing at all, which is why the 2026-08-19 outage left zero durable trace on the engine side.
   */
  @Test
  void feedActuallyStale_doesNotRemediateButRegistersTheBlindWindow() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000);
    feedAgeMs(200_000); // feed itself is stale — market-data's canary owns this, not us

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
    // anchored at the RECEIPT of the last bar, not at detection time 200s later
    verify(blindWindows, times(1))
        .open(anyString(), eq(Instant.ofEpochMilli(NOW_MS - 200_000)), anyString());
    verify(telemetry, times(1)).record(eq("feed-blind"), anyString());
  }

  @Test
  void feedHeartbeatMissing_isSuspiciousAndActs() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(null); // unknown ⇒ suspicious, never silent

    canary(true).sweep();

    verify(engine, times(1)).forceResubscribe(anyString());
    verify(events, times(1)).publishEvent(any(SubscriberStallAlert.class));
  }

  @Test
  void receivingNormally_noAction() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 30_000); // 30s < 180s gap

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void noOneMinuteSubscriptions_butPublishedStrategy_isSuspicious() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(false); // nothing to receive
    evalKeepingUp(NOW_MS - 200_000);
    feedAgeMs(10_000);

    canary(true).sweep();

    verify(engine, times(1)).forceResubscribe(anyString());
    verify(events, times(1)).publishEvent(any(SubscriberStallAlert.class));
  }

  @Test
  void zeroEnabledPublishedStrategies_noOneMinuteSubscriptions_staysQuiet() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(false);
    SubscriberHealthCanary c = canary(true);
    when(registry.countEnabledPublished()).thenReturn(0L);

    c.sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void disabled_noAction() {
    canary(false).sweep();

    verify(engine, never()).hasOneMinuteSubscriptions();
    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
  }

  /** Still starved a full window later: re-subscribe is retried (throttled) but the page fires ONCE. */
  @Test
  void stillStarved_retriesResubscribeButDoesNotRepage() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    // heartbeats fixed in the past — receiveGap keeps growing as the clock advances (stays starved);
    // evaluated == received so evalLag is 0 (this is a RECEIVE drop, not an eval stall).
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenAnswer(inv -> Long.toString(advancing.millis() - 10_000));

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // first detection: re-subscribe #1 + page #1
    advancing.advanceMs(BAR_GAP); // a full window later, still no bar
    c.sweep(); // retry: re-subscribe #2, NO repeat page

    verify(engine, times(2)).forceResubscribe(anyString());
    verify(events, times(1)).publishEvent(any(SubscriberStallAlert.class));
    verify(telemetry, times(2)).record(eq("resubscribe"), anyString()); // one row per attempt
  }

  /**
   * EVAL STALL (audit A13): bars ARRIVING (receipt fresh) but the signal-eval thread stopped
   * processing for a full gap. Must page + capture the stack, but must NOT re-subscribe (a blocked
   * eval thread cannot be fixed that way), and must page only ONCE per episode.
   */
  @Test
  void evalStalled_pagesAndDumpsButNeverResubscribes() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 5_000); // receipt FRESH — bars arriving
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000); // eval frozen ⇒ evalLag 195s > gap

    SubscriberHealthCanary c = canary(true);
    c.sweep();
    c.sweep(); // latched — no re-page

    verify(engine, never()).forceResubscribe(anyString()); // a stalled eval thread is NOT re-subscribed
    verify(events, times(1)).publishEvent(any(SubscriberStallAlert.class));
    verify(telemetry, times(1)).record(eq("eval-stall"), anyString());
  }

  /** Quiet market: both heartbeats frozen together (evalLag ~0) — the eval alarm must stay silent. */
  @Test
  void quietMarketBothFrozen_doesNotClaimAnEvalStall() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000); // frozen
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000); // frozen ≈ received ⇒ evalLag 0
    feedAgeMs(200_000); // feed also quiet (no ticks) — nothing to act on

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
    verify(telemetry, never()).record(eq("eval-stall"), anyString());
  }

  /** One row per episode: the latch holds across sweeps so a multi-hour outage is ONE window. */
  @Test
  void producerBlindAcrossSweeps_opensExactlyOneWindow() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000);
    feedAgeMs(200_000);
    registerAccepts();

    SubscriberHealthCanary c = canary(true);
    c.sweep();
    c.sweep();
    c.sweep();

    verify(blindWindows, times(1)).open(anyString(), any(Instant.class), anyString());
  }

  /**
   * A failed register INSERT is RETRIED on later sweeps, with the ORIGINAL start - losing the row
   * would lose the whole artifact for that outage, which is the one thing this feature produces. The
   * alert side stays latched throughout: exactly one ERROR/telemetry row, however many retries.
   */
  @Test
  void registerInsertFailed_retriesWithTheOriginalStart() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000);
    feedAgeMs(200_000);
    when(blindWindows.open(anyString(), any(Instant.class), anyString()))
        .thenReturn(null) // first write lost
        .thenReturn(7L); // second lands

    SubscriberHealthCanary c = canary(true);
    c.sweep();
    c.sweep();
    c.sweep(); // already durable - must not insert a third time

    verify(blindWindows, times(2)).open(anyString(), eq(Instant.ofEpochMilli(NOW_MS - 200_000)), anyString());
    verify(telemetry, times(1)).record(eq("feed-blind"), anyString()); // one episode, one alert
  }

  /**
   * An eval stall must not be able to hide a producer outage that begins underneath it. Both
   * heartbeats freeze during an outage, so evalLag stays wide and the eval-stall branch returns
   * first - before this fix that return also skipped the blind-window transition entirely.
   */
  @Test
  void evalStalledWhileTheProducerGoesBlind_stillOpensTheWindow() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000); // frozen: no bars arriving
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 400_000); // eval frozen further back
    feedAgeMs(200_000); // producer blind too
    registerAccepts();

    canary(true).sweep();

    verify(blindWindows, times(1)).open(anyString(), any(Instant.class), anyString());
    verify(telemetry, times(1)).record(eq("eval-stall"), anyString()); // the eval branch still fires
  }

  /** ...and the mirror: bars returning while eval is still stalled must still CLOSE the window. */
  @Test
  void barsResumeWhileEvalStillStalled_stillClosesTheWindow() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    AtomicLong received = new AtomicLong(NOW_MS - 200_000);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenAnswer(inv -> received.get());
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 600_000); // eval stuck the whole time
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    registerAccepts();

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // blind, and eval stalled
    advancing.advanceMs(60_000);
    long firstBarBack = advancing.millis() - 5_000;
    received.set(firstBarBack);
    c.sweep(); // bars back; eval STILL stalled

    verify(blindWindows, times(1))
        .close(eq(7L), eq(Instant.ofEpochMilli(firstBarBack)), eq("bars-resumed"));
  }

  /**
   * A backward clock step makes receiveGap negative, which satisfies "receiving normally" - acting
   * on it would close a live outage as recovered without a single bar. Measured host failure class
   * (the July 2026 87-minute drift), so the window must be HELD, not closed.
   */
  @Test
  void clockStepsBackwards_holdsTheWindowInsteadOfClaimingRecovery() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    registerAccepts();

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // window opens
    advancing.advanceMs(-600_000); // clock jumps back 10 min so receiveGap goes negative
    c.sweep();

    verify(blindWindows, never()).close(any(), any(Instant.class), anyString());
  }

  /**
   * A feed already dead at the open anchors on YESTERDAY's last bar. Without the clamp the window
   * would report a whole night of legitimate silence as blindness.
   */
  @Test
  void feedDeadBeforeTheOpen_clampsTheStartToTodaysSessionOpen() {
    // 09:25 IST on the same NSE session; the last receipt is the PREVIOUS evening's close.
    Instant morning = Instant.parse("2026-07-07T03:55:00Z");
    MutableClock at925 = new MutableClock(morning);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    long yesterdayClose = Instant.parse("2026-07-06T10:00:00Z").toEpochMilli(); // 15:30 IST 07-06
    when(engine.lastBarReceivedAtMs()).thenReturn(yesterdayClose);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(yesterdayClose);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(morning.toEpochMilli() - 400_000));
    registerAccepts();

    new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, at925, true, BAR_GAP,
            FEED_FRESH)
        .sweep();

    // 09:15 IST on 2026-07-07 == 03:45Z, NOT the 07-06 close
    verify(blindWindows, times(1)).open(anyString(), eq(Instant.parse("2026-07-07T03:45:00Z")), anyString());
  }

  /**
   * Recovery closes the window at a bar RECEIPT, not at sweep time.
   *
   * <p>⚠️ This fixture has exactly one bar arrive before the sweep, so here the newest receipt
   * IS the first bar back. In production they differ: the close stamps the NEWEST receipt the
   * observing sweep can see, which overstates the true end by up to one sweep. The test name
   * used to claim "first bar back" generally, which the review of #1453 correctly called out.
   */
  @Test
  void barsResume_closesTheWindowAtABarReceiptNotSweepTime() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    AtomicLong received = new AtomicLong(NOW_MS - 200_000);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenAnswer(inv -> received.get());
    when(engine.lastBarEvaluatedAtMs()).thenAnswer(inv -> received.get());
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000)); // stale producer
    registerAccepts();

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // producer blind — window opens
    advancing.advanceMs(60_000);
    long firstBarBack = advancing.millis() - 5_000;
    received.set(firstBarBack);
    c.sweep(); // bars flowing again

    verify(blindWindows, times(1))
        .close(eq(7L), eq(Instant.ofEpochMilli(firstBarBack)), eq("bars-resumed"));
  }

  /**
   * An outage that outlasts the session is NOT a recovery. Closing it as {@code bars-resumed} would
   * report a still-dead feed as healed, so the session-end close names itself.
   */
  @Test
  void outageOutlastsTheSession_closesAsSessionEnded() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    registerAccepts();

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // 10:00 IST — window opens
    advancing.advanceMs(19_860_000L); // 15:31 IST — past SESSION_END, still blind
    c.sweep();

    verify(blindWindows, times(1)).close(eq(7L), any(Instant.class), eq("session-ended"));
  }


  /**
   * THE FIRST REVISION'S DEFECT, pinned. The boundary path used to drop EVERY unflushed episode,
   * including one whose row exists and whose close merely failed — so nothing would ever close it
   * again and the row stayed open forever, which is the permanent-loss failure the retryable episode
   * exists to prevent. Only an episode with no row may be dropped.
   */
  @Test
  void closeThatFailsAtTheSessionBoundary_isRetriedNotDropped() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    when(blindWindows.open(anyString(), any(Instant.class), anyString())).thenReturn(7L);
    when(blindWindows.close(any(), any(Instant.class), anyString())).thenReturn(false); // DB down

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // window opens, id 7
    advancing.advanceMs(19_860_000L); // 15:31 IST — past SESSION_END, close attempted and FAILS
    c.sweep();
    advancing.advanceMs(60_000);
    c.sweep(); // still out of session: the durable row must still be retried, not forgotten

    verify(blindWindows, times(2)).close(eq(7L), any(Instant.class), eq("session-ended"));
  }

  /**
   * Disabling every strategy mid-outage closes the window as `strategies-idle`. Re-enabling while
   * the producer is STILL blind must open a window that starts at that close, not back at the
   * original receipt — otherwise the two windows overlap and the second one re-counts an interval
   * the first already accounted for.
   */
  @Test
  void reEnablingDuringTheSameOutage_doesNotBackdateTheSecondWindow() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    AtomicLong enabled = new AtomicLong(1L);
    when(registry.countEnabledPublished()).thenAnswer(inv -> enabled.get());
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000); // frozen: still blind
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    registerAccepts();

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // first window opens at the last receipt
    advancing.advanceMs(120_000);
    enabled.set(0L);
    c.sweep(); // strategies-idle: closes at "now"
    Instant closedAt = advancing.instant();
    advancing.advanceMs(120_000);
    enabled.set(1L);
    c.sweep(); // re-enabled, producer still blind: a SECOND window opens

    ArgumentCaptor<Instant> starts = ArgumentCaptor.forClass(Instant.class);
    verify(blindWindows, times(2)).open(anyString(), starts.capture(), anyString());
    assertThat(starts.getAllValues().get(1))
        .as("the second window must not reach back over the interval the first one covered")
        .isAfterOrEqualTo(closedAt);
  }

  /**
   * ⚠️ ORDERING, and it is correctness rather than style. `telemetry.record` writes a DIFFERENT
   * table (`subscriber_health_events`) through an UNBOUNDED insert. With it first, a stall there
   * would stop `blind_windows` from ever being ATTEMPTED — losing the artifact this feature exists
   * to produce, to a failure in a table nothing here depends on.
   */
  @Test
  void openRegistersTheWindowBeforeWritingTelemetry() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000);
    feedAgeMs(200_000);
    registerAccepts();

    canary(true).sweep();

    InOrder order = inOrder(blindWindows, telemetry);
    order.verify(blindWindows).open(anyString(), any(Instant.class), anyString());
    order.verify(telemetry).record(eq("feed-blind"), anyString());
  }

  /**
   * The mirror, and the sharper half: telemetry running before the close was PERSISTED meant a stall
   * could block after recovery was observed but before `ended_at` was written — and a restart then
   * loses the in-memory episode and leaves the row open forever.
   */
  @Test
  void recoveryPersistsTheCloseBeforeWritingTelemetry() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    AtomicLong received = new AtomicLong(NOW_MS - 200_000);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenAnswer(inv -> received.get());
    when(engine.lastBarEvaluatedAtMs()).thenAnswer(inv -> received.get());
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    registerAccepts();

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep();
    advancing.advanceMs(60_000);
    received.set(advancing.millis() - 5_000);
    c.sweep();

    InOrder order = inOrder(blindWindows, telemetry);
    order.verify(blindWindows).close(eq(7L), any(Instant.class), eq("bars-resumed"));
    order.verify(telemetry).record(eq("recovery"), anyString());
  }

  /**
   * The failure path the ordering tests could NOT reach, because `registerAccepts()` forces success.
   * A fast register failure must not spend the sweep thread on the unbounded telemetry insert: if it
   * stalled there, no later sweep would ever retry the write that actually matters.
   */
  @Test
  void openThatFailsWritesNoTelemetryUntilTheRowLands() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000);
    feedAgeMs(200_000);
    when(blindWindows.open(anyString(), any(Instant.class), anyString()))
        .thenReturn(null) // first attempt lost
        .thenReturn(7L); // second lands

    SubscriberHealthCanary c = canary(true);
    c.sweep();
    verify(telemetry, never()).record(eq("feed-blind"), anyString());

    c.sweep(); // the retry lands the row — only NOW is the forensic write safe
    verify(telemetry, times(1)).record(eq("feed-blind"), anyString());

    c.sweep(); // already reported: exactly once per episode
    verify(telemetry, times(1)).record(eq("feed-blind"), anyString());
  }

  /** The mirror on the close path: a failed flush must not report a recovery it did not persist. */
  @Test
  void closeThatFailsWritesNoRecoveryTelemetryUntilItPersists() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    AtomicLong received = new AtomicLong(NOW_MS - 200_000);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenAnswer(inv -> received.get());
    when(engine.lastBarEvaluatedAtMs()).thenAnswer(inv -> received.get());
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    when(blindWindows.open(anyString(), any(Instant.class), anyString())).thenReturn(7L);
    when(blindWindows.close(any(), any(Instant.class), anyString()))
        .thenReturn(false) // the close is lost
        .thenReturn(true); // and lands on the retry

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // blind
    advancing.advanceMs(60_000);
    received.set(advancing.millis() - 5_000);
    c.sweep(); // recovery observed, close FAILS
    verify(telemetry, never()).record(eq("recovery"), anyString());

    advancing.advanceMs(60_000);
    received.set(advancing.millis() - 5_000);
    c.sweep(); // retry persists it
    verify(telemetry, times(1)).record(eq("recovery"), anyString());
  }

  /**
   * The backward-clock guard's BYPASS, which the existing clock test could not reach because it
   * steps back only ten minutes and so stays inside the session. The measured 87-minute host drift
   * moves 10:00 IST to 08:33 -- outside ARMED_FROM -- so the sweep takes the out-of-session branch
   * and never reaches the negative-receive-gap check at all. It must still refuse to close.
   */
  @Test
  void clockStepsBackPastTheSessionStart_stillRefusesToCloseTheWindow() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    registerAccepts();

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // 10:00 IST -- window opens
    advancing.advanceMs(-87 * 60_000L); // the measured July 2026 drift: 10:00 IST becomes 08:33
    c.sweep();

    verify(blindWindows, never()).close(any(), any(Instant.class), anyString());
  }

  /**
   * A single flush can OPEN and CLOSE the row together -- when the first open failed and bars came
   * back before the next blind sweep. Inferring "was it durable?" from the episode afterwards then
   * dropped the OPENING event, leaving a recovery-only timeline that reads as if the engine had
   * never been blind.
   */
  @Test
  void openAndCloseInOneFlush_stillReportsBothEvents() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    AtomicLong received = new AtomicLong(NOW_MS - 200_000);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenAnswer(inv -> received.get());
    when(engine.lastBarEvaluatedAtMs()).thenAnswer(inv -> received.get());
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - 200_000));
    when(blindWindows.open(anyString(), any(Instant.class), anyString()))
        .thenReturn(null) // the opening INSERT is lost
        .thenReturn(7L); // and lands on the retry inside the CLOSING flush
    when(blindWindows.close(any(), any(Instant.class), anyString())).thenReturn(true);

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, registry, redis, events, telemetry, blindWindows, advancing, true, BAR_GAP,
            FEED_FRESH);
    c.sweep(); // blind; open fails
    advancing.advanceMs(60_000);
    received.set(advancing.millis() - 5_000);
    c.sweep(); // bars back: one flush opens AND closes the row

    InOrder order = inOrder(telemetry);
    order.verify(telemetry).record(eq("feed-blind"), anyString());
    order.verify(telemetry).record(eq("recovery"), anyString());
  }

  /** A test clock whose instant can be advanced, to drive the multi-sweep throttle path. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advanceMs(long ms) {
      this.instant = this.instant.plusMillis(ms);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
