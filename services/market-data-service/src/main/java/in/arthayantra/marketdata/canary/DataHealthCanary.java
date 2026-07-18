package in.arthayantra.marketdata.canary;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DataHealthCanary (roadmap F4, method doc §7.8): the in-code tripwire between "the container is
 * healthy" and "the data the signal engine eats is actually flowing". Three check families, swept
 * every 60 s during the session:
 *
 * <ul>
 *   <li><b>TICK_BAR_DIVERGENCE</b> — per instrument: ticks arriving but no 1m bar closed for
 *       {@code bar-stale-seconds}. This is the exact signature of the 2026-07-03 CandleBuilder
 *       poison (engine starved 3 h while 22k+ ticks flowed); the global {@code FeedWatchdog} is
 *       blind to one dead token.
 *   <li><b>OPTIONS_CAPTURE / FUTURES_OI_CAPTURE</b> (live profile only) — newest snapshot row older
 *       than {@code capture-stale-seconds}: the OI capture crons stopped landing rows.
 * </ul>
 *
 * <p>Alerts go to the ops ntfy topic with a per-(check,key) cooldown; ≥{@code AGGREGATE_THRESHOLD}
 * stalled tokens collapse into one summary push (a feed-wide fault must not fire 400 pings). A
 * cleared RED sends one recovery note. {@code GET /api/v1/market/health/data} evaluates fresh — the
 * 09:42 live-health agent reads it first and deep-dives only on non-GREEN.
 */
@Component
public class DataHealthCanary {

  private static final Logger log = LoggerFactory.getLogger(DataHealthCanary.class);

  static final String DIVERGENCE = "TICK_BAR_DIVERGENCE";
  static final String OPTIONS_CAPTURE = "OPTIONS_CAPTURE";
  static final String FUTURES_OI_CAPTURE = "FUTURES_OI_CAPTURE";
  static final int AGGREGATE_THRESHOLD = 5;
  static final int REPORT_PROBLEM_CAP = 50;
  private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);

  private final DataHealthState state;
  private final NtfyClient ntfy;
  private final JdbcTemplate jdbc;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final boolean live;
  private final boolean alertsEnabled;
  private final boolean forceOpen;
  private final Duration tickFresh;
  private final Duration barStale;
  private final Duration captureStale;
  private final Duration alertCooldown;

  private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();
  private final Set<String> activeReds = ConcurrentHashMap.newKeySet();

  /** Wires state, alerting, the capture-freshness probes and the tunable thresholds. */
  public DataHealthCanary(
      DataHealthState state,
      NtfyClient ntfy,
      JdbcTemplate jdbc,
      MarketCalendar calendar,
      Clock clock,
      Environment environment,
      @Value("${artha.canary.alerts-enabled:true}") boolean alertsEnabled,
      @Value("${artha.canary.force-open:false}") boolean forceOpen,
      @Value("${artha.canary.tick-fresh-seconds:90}") long tickFreshSeconds,
      @Value("${artha.canary.bar-stale-seconds:240}") long barStaleSeconds,
      @Value("${artha.canary.capture-stale-seconds:600}") long captureStaleSeconds,
      @Value("${artha.canary.alert-cooldown-seconds:900}") long alertCooldownSeconds) {
    this.state = state;
    this.ntfy = ntfy;
    this.jdbc = jdbc;
    this.calendar = calendar;
    this.clock = clock;
    this.live = environment.matchesProfiles("live");
    this.alertsEnabled = alertsEnabled;
    this.forceOpen = forceOpen;
    this.tickFresh = Duration.ofSeconds(tickFreshSeconds);
    this.barStale = Duration.ofSeconds(barStaleSeconds);
    this.captureStale = Duration.ofSeconds(captureStaleSeconds);
    this.alertCooldown = Duration.ofSeconds(alertCooldownSeconds);
  }

  /** One full evaluation; cheap (in-memory maps + two bounded SQL probes), safe to call per GET. */
  public CanaryReport evaluate() {
    Instant now = clock.instant();
    boolean open = marketOpen(now);
    Map<String, Instant> ticks = state.lastTicks();
    if (!open) {
      return new CanaryReport("GREEN", false, now.toString(), ticks.size(), List.of());
    }
    List<CheckResult> problems = new ArrayList<>(divergences(now, ticks));
    if (live) {
      captureCheck(OPTIONS_CAPTURE, "options_chain_snapshots", now, problems);
      captureCheck(FUTURES_OI_CAPTURE, "futures_oi_snapshots", now, problems);
    }
    String status =
        problems.stream().anyMatch(p -> "RED".equals(p.status()))
            ? "RED"
            : problems.isEmpty() ? "GREEN" : "AMBER";
    List<CheckResult> capped =
        problems.size() <= REPORT_PROBLEM_CAP ? problems : problems.subList(0, REPORT_PROBLEM_CAP);
    return new CanaryReport(status, true, now.toString(), ticks.size(), List.copyOf(capped));
  }

  private List<CheckResult> divergences(Instant now, Map<String, Instant> ticks) {
    // Arming guards against known false-positive shapes: (1) session-open + barStale covers the
    // day rollover (yesterday's last bar is stale at 09:15 but today's first bar needs a minute);
    // (2) per-key first-tick age covers boot and mid-session UI subscriptions (a token's very
    // first bar also needs a minute).
    List<CheckResult> problems = new ArrayList<>();
    Instant sessionArmed =
        now.atZone(Ist.ZONE).toLocalDate().atTime(SESSION_OPEN).atZone(Ist.ZONE).toInstant()
            .plus(barStale);
    if (now.isBefore(sessionArmed)) {
      return problems;
    }
    for (Map.Entry<String, Instant> e : ticks.entrySet()) {
      String key = e.getKey();
      if (Duration.between(e.getValue(), now).compareTo(tickFresh) > 0) {
        continue; // no recent ticks — a silent token is the FeedWatchdog's problem, not a divergence
      }
      Instant first = state.firstTick(key);
      if (first == null || Duration.between(first, now).compareTo(barStale) < 0) {
        continue;
      }
      Instant bar = state.lastBar(key);
      if (bar == null || Duration.between(bar, now).compareTo(barStale) > 0) {
        problems.add(
            new CheckResult(
                DIVERGENCE,
                key,
                "RED",
                "ticks flowing but no 1m bar closed for "
                    + (bar == null ? "this run" : Duration.between(bar, now).toSeconds() + "s"),
                bar == null ? null : bar.toString()));
      }
    }
    return problems;
  }

  private void captureCheck(String check, String table, Instant now, List<CheckResult> problems) {
    Instant newest;
    try {
      // bounded max: the time index serves this from the newest (uncompressed) chunk only — an
      // unbounded max(ts) over the compressed hypertable is exactly the scan we must never run
      Timestamp ts =
          jdbc.queryForObject(
              "SELECT max(ts) FROM " + table + " WHERE ts > ?",
              Timestamp.class,
              Timestamp.from(now.minus(Duration.ofHours(1))));
      newest = ts == null ? null : ts.toInstant();
    } catch (Exception probeFailure) {
      problems.add(
          new CheckResult(check, table, "AMBER", "probe failed: " + probeFailure.getMessage(), null));
      return;
    }
    // arm only once the session has been open past the staleness budget (first capture lands
    // minutes after open); before that an empty morning is normal, not a fault
    Instant sessionArmed =
        now.atZone(Ist.ZONE).toLocalDate().atTime(SESSION_OPEN).atZone(Ist.ZONE).toInstant()
            .plus(captureStale);
    if (now.isBefore(sessionArmed)) {
      return;
    }
    if (newest == null || Duration.between(newest, now).compareTo(captureStale) > 0) {
      problems.add(
          new CheckResult(
              check,
              table,
              "RED",
              "newest snapshot row is "
                  + (newest == null ? "absent this hour" : Duration.between(newest, now).toSeconds() + "s old"),
              newest == null ? null : newest.toString()));
    }
  }

  /** The 60 s alerting sweep; the initial delay covers boot. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 120_000, scheduler = "monitorTaskScheduler")
  public void sweep() {
    CanaryReport report;
    try {
      report = evaluate();
    } catch (Exception evalFailure) {
      log.warn("canary sweep failed: {}", evalFailure.getMessage());
      return;
    }
    if (!report.marketOpen()) {
      return;
    }
    Instant now = clock.instant();
    Set<String> reds = new HashSet<>();
    List<CheckResult> redResults = new ArrayList<>();
    for (CheckResult p : report.problems()) {
      if ("RED".equals(p.status())) {
        reds.add(p.check() + "|" + p.key());
        redResults.add(p);
      }
    }
    long divergenceCount = redResults.stream().filter(p -> DIVERGENCE.equals(p.check())).count();
    if (divergenceCount >= AGGREGATE_THRESHOLD) {
      alert(DIVERGENCE + "|*", "ArthaYantra data canary", "urgent",
          divergenceCount + " instruments have ticks flowing but no 1m bars closing — feed-wide bar stall", now);
    } else {
      for (CheckResult p : redResults) {
        alert(p.check() + "|" + p.key(), "ArthaYantra data canary: " + p.check(), "urgent",
            p.key() + " — " + p.detail(), now);
      }
    }
    for (String cleared : activeReds) {
      if (!reds.contains(cleared) && !cleared.endsWith("|*")) {
        sendAlert("ArthaYantra data canary recovered", "default", cleared + " is healthy again");
      }
    }
    activeReds.clear();
    activeReds.addAll(reds);
    if (divergenceCount >= AGGREGATE_THRESHOLD) {
      activeReds.add(DIVERGENCE + "|*");
    }
  }

  private void alert(String cooldownKey, String title, String priority, String message, Instant now) {
    Instant last = lastAlertAt.get(cooldownKey);
    if (last != null && Duration.between(last, now).compareTo(alertCooldown) < 0) {
      return;
    }
    lastAlertAt.put(cooldownKey, now);
    log.error("data canary RED: {}", message);
    sendAlert(title, priority, message);
  }

  private void sendAlert(String title, String priority, String message) {
    if (!alertsEnabled) {
      return;
    }
    ntfy.send(title, priority, message);
  }

  private boolean marketOpen(Instant now) {
    if (forceOpen) {
      return true;
    }
    try {
      return calendar.isOpen(now);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary — never let this one throw
    }
  }
}
