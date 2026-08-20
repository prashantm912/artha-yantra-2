package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategysignal.signals.InsightDeliveryAlert;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The insight WS delivery seam (INT design §9.3) — publishes a tiny {@code insights} Redis frame that
 * the gateway STOMP bridge relays to {@code /topic/insights} (the WS bridge allowlist +1, mirroring
 * {@link in.arthayantra.strategysignal.paper.PaperEventPublisher}'s {@code paper.events}). The durable
 * {@code insights} row is the record of truth; the frame is best-effort live delivery, healed by the
 * REST feed read on reconnect.
 *
 * <p><b>Staged-rollout gates (§10.3).</b> WS delivery remains behind {@code
 * artha.insights.delivery.ws}. Phone delivery is emitted as a typed in-process event so this module
 * never imports {@code notifier}; the notifier listener owns the existing outbound client and audit.
 * Fail-soft: a broadcast or event-publish hiccup logs and is swallowed — delivery must NEVER perturb
 * generation.
 */
@Component
public class InsightPublisher {

  /** The pub/sub channel the gateway relays to {@code /topic/insights} (WS allowlist §9.3). */
  public static final String CHANNEL = "insights";

  /** The budget day is an IST trading day, not a UTC one — see {@link #claimPhoneBudget}. */
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static final Logger log = LoggerFactory.getLogger(InsightPublisher.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher events;
  private final InsightRepository repository;
  private final boolean wsEnabled;
  private final boolean ntfyEnabled;
  private final boolean telegramEnabled;
  private final Severity severityFloor;
  private final int contextShiftDailyPhoneCap;
  private final Clock clock;
  private final Counter contextShiftPhoneSuppressed;

  /** Guarded by {@code this} — see {@link #claimPhoneBudget}. */
  private LocalDate budgetDay;

  private int contextShiftPhonedToday;

  /** Wires Redis + the staged-rollout delivery flags (all phone flags default false). */
  public InsightPublisher(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      ApplicationEventPublisher events,
      InsightRepository repository,
      InsightProperties props,
      Clock clock,
      MeterRegistry meters) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.events = events;
    this.repository = repository;
    this.wsEnabled = props.delivery().ws();
    this.ntfyEnabled = props.delivery().ntfyEnabled();
    this.telegramEnabled = props.delivery().telegramEnabled();
    this.severityFloor = props.delivery().severityFloor();
    this.contextShiftDailyPhoneCap = props.delivery().contextShiftDailyPhoneCap();
    this.clock = clock;
    // ⚠️ The DROP path is counted, not just the moment the budget is spent. A cap that suppresses
    // silently hides its own misconfiguration -- and this count is precisely the number
    // InsightProperties.Delivery's javadoc tells the owner to size the cap from. Same reason
    // TokenResolverAdapter counts its -BE fallback.
    this.contextShiftPhoneSuppressed =
        Counter.builder("ay_insight_context_shift_phone_suppressed_total")
            .description("CONTEXT_SHIFT insights withheld from phone channels by the daily budget")
            .register(meters);
  }

  /**
   * Publishes a NOTICE-or-higher insight as a compact frame ({@code {id,type,severity,scope,title}},
   * §9.3). A no-op when delivery is disabled (shadow mode) or the insight is below the WS floor /
   * suppressed. WS is independently flag-gated; INFO is never sent to phone channels, and the common
   * phone floor is clamped to NOTICE.
   * Returns whether a WS frame was actually published (for the engine's counter/test).
   */
  public boolean publish(Insight insight) {
    if (insight == null || insight.suppressed()) {
      return false;
    }
    Severity severity = Severity.valueOf(insight.severity());
    boolean phoneEligible = severity.compareTo(severityFloor) >= 0 && (ntfyEnabled || telegramEnabled);
    boolean wsEligible = wsEnabled && severity.compareTo(Severity.NOTICE) >= 0;
    if (!phoneEligible && !wsEligible) {
      return false;
    }
    if (repository.isMuted(insight.type(), insight.scope())) {
      return false;
    }
    // ⚠️ AFTER the mute check on purpose: a muted insight must not spend budget it never used, or a
    // single muted scope could starve the phone for every other scope. Also strictly AFTER the
    // severity/flag gate above, so a shadow-mode stack (all phone flags false) never consumes any.
    if (phoneEligible && !claimPhoneBudget(insight)) {
      phoneEligible = false;
    }
    boolean wsPublished = false;
    if (wsEligible) { // WS floor: >= NOTICE
      wsPublished = publishWs(insight);
    }
    if (phoneEligible) {
      publishPhoneEvent(insight, "NTFY", ntfyEnabled);
      publishPhoneEvent(insight, "TELEGRAM", telegramEnabled);
    }
    return wsPublished;
  }

  /**
   * Claims one CONTEXT_SHIFT phone slot for the current IST day, or refuses.
   *
   * <p>Bounds PHONE delivery only — the insight row is still written and the WS frame still goes out,
   * so a refusal removes an interruption, never a record. Everything other than CONTEXT_SHIFT, and a
   * cap of {@code 0}, are unbudgeted.
   *
   * <p>⚠️ The one log line fires at the moment the budget is SPENT, not on each subsequent refusal.
   * A cap that announces every suppression replaces the notification flood it exists to prevent with
   * a log flood, which is the same defect wearing a quieter hat.
   *
   * <p>{@code synchronized} because the sweep is scheduled but {@code publish} is not contractually
   * single-threaded, and a torn read of the day rollover would either double the budget or zero it
   * mid-session. Measured callers: the {@code @Scheduled} sweeps in {@code InsightSweeper} and the
   * {@code @Async("notifierExecutor")} path from {@code InsightEngine.onSignalEmitted}.
   *
   * <p>⚠️ The counter is PROCESS-LOCAL, so a mid-session redeploy silently re-grants a full budget:
   * the real guarantee is "at most N per process per IST day", not per day. The failure direction is
   * over-delivery, which is exactly today's status quo, so this is a documented limit rather than a
   * defect — but it also means the "budget spent" log line below can fire twice in one day and is
   * NOT a reliable once-per-day marker.
   */
  private synchronized boolean claimPhoneBudget(Insight insight) {
    if (contextShiftDailyPhoneCap == 0
        || !InsightType.CONTEXT_SHIFT.name().equals(insight.type())) {
      return true;
    }
    // ⚠️ IST, not the clock's own zone. The Clock bean is systemUTC (ClockConfig:16), so a bare
    // LocalDate.now(clock) would roll the budget over at 05:30 IST -- mid-morning, before the sweep
    // window even opens on the day it is meant to bound.
    LocalDate today = LocalDate.now(clock.withZone(IST));
    if (!today.equals(budgetDay)) {
      budgetDay = today;
      contextShiftPhonedToday = 0;
    }
    if (contextShiftPhonedToday >= contextShiftDailyPhoneCap) {
      contextShiftPhoneSuppressed.increment();
      return false;
    }
    contextShiftPhonedToday++;
    if (contextShiftPhonedToday == contextShiftDailyPhoneCap) {
      log.info(
          "insight CONTEXT_SHIFT phone budget spent for {} ({} delivered) — further CONTEXT_SHIFT"
              + " insights today are still recorded and WS-published, but will not reach a phone",
          today, contextShiftDailyPhoneCap);
    }
    return true;
  }

  private boolean publishWs(Insight insight) {
    try {
      ObjectNode f = objectMapper.createObjectNode();
      f.put("id", insight.id().toString());
      f.put("type", insight.type());
      f.put("severity", insight.severity());
      f.put("scope", insight.scope());
      f.put("title", insight.title());
      f.put("at", insight.generatedAt().toString());
      redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(f));
      return true;
    } catch (Exception e) {
      log.warn("insight frame not published ({}): {}", insight.dedupeKey(), e.getMessage());
      return false;
    }
  }

  private void publishPhoneEvent(Insight insight, String channel, boolean enabled) {
    if (!enabled) {
      return;
    }
    try {
      events.publishEvent(
          new InsightDeliveryAlert(
              insight.id(), insight.title(), insight.explanation(), insight.scope(), channel));
    } catch (RuntimeException e) {
      log.warn("insight {} delivery event not published for {}: {}", insight.id(), channel, e.getMessage());
    }
  }

  /** Whether WS delivery is armed (shadow mode = false). Exposed for the engine's guard + tests. */
  public boolean wsEnabled() {
    return wsEnabled;
  }
}
