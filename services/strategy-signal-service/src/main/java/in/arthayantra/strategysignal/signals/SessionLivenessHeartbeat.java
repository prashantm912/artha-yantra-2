package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Session-cadence external dead-man's-switch for the LIVE trading session — the reliability gap the
 * once-a-day {@link SwingBatchHeartbeat} cannot close.
 *
 * <p>{@code SwingBatchHeartbeat} pings its external monitor ONCE at 18:54 IST and proves only that the
 * evening swing BATCH ran. A stack that is dead across the whole live session (09:15–15:30 IST) but has
 * recovered by 18:54 pings that monitor happily — the entire intraday session was silently lost with no
 * external trace. This heartbeat makes "the session was alive" externally observable: every ~10 minutes
 * during session hours it pings a SEPARATE external monitor, but ONLY when candles are currently
 * arriving. When the engine is not receiving bars (feed/subscription dead, or the process/host is down)
 * the ping is WITHHELD, and the external monitor alarms on the missed schedule — off-box, so it survives
 * exactly the full-stack death an in-process alarm cannot report.
 *
 * <p><b>Absence is the alarm.</b> We ping only when healthy and stay silent when not — the withheld ping
 * is the signal, never a status payload. Health is gated ONLY on candle-receipt liveness
 * ({@link SignalEngine#lastBarReceivedAtMs()}, stamped on the first line of bar receipt before any
 * universe/window/position logic) — never on rejections, gate output, or signal counts, which are
 * direction-/window-/position-confounded and false-alarm on a healthy-but-quiet leg. The receive-gap
 * threshold reuses the subscriber-watchdog's {@code bar-gap-ms} so "bars flowing" means the same thing
 * here as in {@link SubscriberHealthCanary}.
 *
 * <p><b>Window.</b> The cron fires every 10 min over hours 09–15 IST on weekdays; the health check
 * narrows that to a live NSE session inside [{@value #SESSION_START_STR}, {@value #SESSION_END_STR}) IST
 * (slightly inside the 09:15–15:30 session so the first/last bars have settled and never straddle the
 * open/close). {@link MarketCalendar#isOpen} handles NSE holidays; an uncovered-year query is treated as
 * closed (the calendar cliff has its own canary), mirroring {@link SubscriberHealthCanary}.
 *
 * <p><b>Dormant until armed</b> (identical to {@link SwingBatchHeartbeat}): it loads only when
 * {@code artha.heartbeat.session-url} is set AND the live engine is enabled
 * ({@code artha.signals.engine-enabled}, default on) — it shares {@link SignalEngine}'s lifecycle since it
 * injects it, so an engine-off boot never tries to wire an absent engine; the scheduled method
 * belt-and-braces also re-checks a blank URL. Fail-soft: a ping failure is logged, never thrown. It only READS a volatile heartbeat long — it
 * touches no evaluation path, no {@code SignalEvent}/{@code Trade}, no scoring — so goldens/parity are
 * unaffected by construction.
 *
 * <p><b>Scheduler.</b> Unqualified {@code @Scheduled} → the service-wide default {@code taskScheduler},
 * exactly like its HTTP-pinging siblings {@link SwingBatchHeartbeat} and {@code NotifierHealthCheck}. It
 * deliberately does NOT bind {@code monitorTaskScheduler}: that single-thread detector pool is reserved
 * (audit BEJ-01) for pure fast in-memory sweeps that make NO external HTTP call, so a 5 s ping there
 * could delay the critical {@link SubscriberHealthCanary} sweep — the opposite of the intent.
 */
@Component
@ConditionalOnProperty(name = "artha.heartbeat.session-url")
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true) // shares SignalEngine's lifecycle — it injects the engine, unwireable without it
public class SessionLivenessHeartbeat {

  private static final Logger log = LoggerFactory.getLogger(SessionLivenessHeartbeat.class);

  private static final String SESSION_START_STR = "09:30";
  private static final String SESSION_END_STR = "15:20";
  private static final LocalTime SESSION_START = LocalTime.of(9, 30);
  private static final LocalTime SESSION_END = LocalTime.of(15, 20);

  private final String url;
  private final SignalEngine engine;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final long barGapMs;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /**
   * Wires the (armed-only) session ping URL, the engine whose receive heartbeat gates the ping, the
   * shared clock, and the subscriber-watchdog bar-gap so "bars flowing" matches the watchdog exactly.
   */
  public SessionLivenessHeartbeat(
      @Value("${artha.heartbeat.session-url}") String url,
      SignalEngine engine,
      Clock clock,
      @Value("${artha.signals.subscriber-watchdog.bar-gap-ms:180000}") long barGapMs) {
    this.url = url;
    this.engine = engine;
    this.clock = clock;
    this.barGapMs = barGapMs;
  }

  /** Every 10 min over 09–15 IST weekdays; pings only when the session is alive and bars are flowing. */
  @Scheduled(cron = "${artha.heartbeat.session-cron:0 */10 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
  public void beat() {
    if (url == null || url.isBlank()) {
      return; // belt-and-braces; the conditional already gates loading
    }
    pingIfHealthy(clock.millis(), engine.lastBarReceivedAtMs(), clock.instant().atZone(Ist.ZONE));
  }

  /**
   * The decide-then-ping seam (package-private so a unit test drives it with explicit inputs — no
   * clock/engine/network reads). Pings ONLY when healthy; otherwise returns silently so the external
   * monitor alarms on the withheld ping. Fail-soft: a ping error is logged, never propagated.
   */
  void pingIfHealthy(long nowMs, long lastReceivedMs, ZonedDateTime now) {
    if (!healthy(nowMs, lastReceivedMs, now)) {
      return; // withhold the ping — absence is the alarm
    }
    try {
      send(url);
      log.info("session liveness heartbeat: pinged the external dead-man's-switch (bars flowing)");
    } catch (Exception e) {
      log.warn(
          "session liveness heartbeat ping failed (external monitor may alert): {}", e.toString());
    }
  }

  /**
   * Healthy = a live NSE session inside the ping window AND candles currently arriving (receive-gap
   * below the subscriber-watchdog threshold). Package-private + fully parameterized for unit testing.
   */
  boolean healthy(long nowMs, long lastReceivedMs, ZonedDateTime now) {
    if (!inSession(now)) {
      return false;
    }
    return nowMs - lastReceivedMs < barGapMs; // bars are currently arriving
  }

  /** Trading day AND within [09:30, 15:20) IST; an uncovered-year calendar query counts as closed. */
  private boolean inSession(ZonedDateTime now) {
    try {
      LocalTime time = now.toLocalTime();
      return calendar.isOpen(now.toInstant())
          && !time.isBefore(SESSION_START)
          && time.isBefore(SESSION_END);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary (mirror SubscriberHealthCanary.inSession)
    }
  }

  /** The actual GET — package-private so a unit test can capture it without real network I/O. */
  void send(String pingUrl) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(pingUrl)).timeout(Duration.ofSeconds(5)).GET().build();
    http.send(request, HttpResponse.BodyHandlers.discarding());
  }
}
