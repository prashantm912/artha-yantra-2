package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-DOT input liveness (roadmap F4 v2 / F3 acceptance): the market-data canary watches the data
 * PLANE (ticks/bars/captures); this one watches the GATE'S INPUTS — the rejection-context fields
 * each Connect-the-Dots dot scores from. A dot whose input goes dead doesn't fail loudly: it
 * silently re-caps the composite (the 0.765 ceiling class the 2026-07-02 forensics found months
 * late). Every sweep inspects TODAY's newest rejections; a REQUIRED dot with no live input across
 * the whole window alerts once per day (newly-alive also notes once — that's a fix landing).
 *
 * <p>Dot registry mirrors the findings-doc §3.7 checks. {@code required-dots} (config) lists the
 * dots expected alive TODAY — grows as fixes land (breadth after #486; iv_rank once the IV-history
 * floor is met; dow stays off the list while un-armed by design). Read surface:
 * {@code GET /api/v1/signal-rejections/dot-health} — the 09:42 agent reads it instead of
 * hand-running the SQL.
 */
@Component
public class DotHealthCanary {

  private static final Logger log = LoggerFactory.getLogger(DotHealthCanary.class);
  private static final LocalTime ARMED_FROM = LocalTime.of(9, 45); // rejections need to accrue
  private static final LocalTime SESSION_END = LocalTime.of(15, 30);
  static final int WINDOW = 40;
  // T17 (2026-07-25): rows blocked at an early rail (time-window / time-of-day / option-side)
  // carry NO context at all, and after ~14:45 the newest rows are ALL that shape — sampling them
  // read every dot dead 4 sessions running (breadth included, while it supported 426/1,100).
  // Fetch a deeper page and keep only context-bearing rows; an all-context-less window is
  // UNINFORMATIVE, never an all-dead verdict.
  static final int FETCH_DEPTH = 200;

  /** One dot's input-liveness probe over a rejection's diagnostic JSON. */
  private record Probe(String dot, Predicate<JsonNode> alive) {}

  private static final List<Probe> PROBES =
      List.of(
          new Probe("breadth", d -> macroInt(d, "advances") + macroInt(d, "declines") > 0),
          new Probe("iv_rank", d -> !d.at("/context/macro/ivRank").isMissingNode()
              && !d.at("/context/macro/ivRank").isNull()),
          new Probe("dow", d -> !d.at("/context/macro/dowUp").isMissingNode()
              && !d.at("/context/macro/dowUp").isNull()),
          new Probe("fii", d -> !d.at("/context/macro/fiiLongPct").isMissingNode()
              && !d.at("/context/macro/fiiLongPct").isNull()),
          new Probe("oi_spurt_price", d -> d.at("/context/oi/spurtPricePct").asDouble(0) != 0),
          new Probe("vix", d -> !d.at("/context/macro/vixLevel").isMissingNode()
              && !d.at("/context/macro/vixLevel").isNull()),
          // T13: NEUTRAL is the strategy-side "data missing" sentinel — OiInterpretation.classify is
          // a total function over four real states, so an all-NEUTRAL window means the OI read is
          // broken (the 2026-07-20 outage: 748/748 NEUTRAL, three dots dead, canary green all day).
          new Probe("futures_oi", d -> quadrantLive(d, "futuresQuadrant")),
          new Probe("underlying_oi", d -> quadrantLive(d, "underlyingQuadrant")));

  private static boolean quadrantLive(JsonNode d, String field) {
    JsonNode q = d.at("/context/oi/" + field);
    return !q.isMissingNode() && !q.isNull() && !"NEUTRAL".equals(q.asText());
  }

  private static int macroInt(JsonNode d, String field) {
    return d.at("/context/macro/" + field).asInt(0);
  }

  /** One dot's current verdict. */
  public record DotState(String dot, boolean alive, boolean required, String detail) {}

  /**
   * The endpoint payload. {@code rowsInspected} counts the CONTEXT-BEARING rows the probes actually
   * read (T17 — the old any-row count let an all-context-less tail read as an all-dead verdict);
   * {@code rowsScanned} is the raw page depth for transparency.
   */
  public record DotHealth(
      String asOf, boolean session, int rowsScanned, int rowsInspected, List<DotState> dots) {}

  /**
   * In-process alert event — the notifier module listens (same direction as {@link SignalEmitted};
   * signals must not import notifier, that is a module cycle).
   */
  public record DotInputAlert(String title, String message) {}

  // The two OI-quadrant probes are exempt from paging on a monthly index-expiry day: MarketOiClient
  // deliberately degrades the OI reads to NEUTRAL then (S24 — chain OI is corrupted by the expiring
  // series' unwind), so an all-NEUTRAL window is by-design, not an outage. Keyed per root exactly
  // like the suppression itself (NSE monthly for NIFTY, BSE Thursday monthly for SENSEX) — the
  // window mixes both roots, so either root's suppression day exempts the pair.
  private static final Set<String> OI_QUADRANT_DOTS = Set.of("futures_oi", "underlying_oi");

  private final SignalRejectionRepository rejections;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final MarketCalendar bseCalendar = MarketCalendar.bse();
  private final Set<String> required;
  private final Map<String, LocalDate> alertedDeadOn = new ConcurrentHashMap<>();
  private final Set<String> deadNow = ConcurrentHashMap.newKeySet();

  /** Wires the rejection source, the in-process event bus and the required-alive dot list. */
  public DotHealthCanary(
      SignalRejectionRepository rejections,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.canary.required-dots:breadth,futures_oi,underlying_oi}") String requiredDots) {
    this.rejections = rejections;
    this.events = events;
    this.clock = clock;
    this.required = Set.of(requiredDots.isBlank() ? new String[0] : requiredDots.split("\\s*,\\s*"));
  }

  /** One evaluation over today's newest CONTEXT-BEARING rejections (T17; controller calls per GET). */
  public DotHealth evaluate() {
    ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
    boolean session = inSession(now);
    List<SignalRejectionRepository.RejectionRow> scanned =
        rejections.list(
            null, null, null, null,
            now.toLocalDate().atTime(LocalTime.of(9, 15)).atZone(Ist.ZONE).toOffsetDateTime(),
            null, FETCH_DEPTH, 0);
    List<JsonNode> contextRows = new ArrayList<>(WINDOW);
    for (SignalRejectionRepository.RejectionRow row : scanned) {
      JsonNode d = row.diagnostic();
      if (d != null && !d.at("/context").isMissingNode() && d.at("/context").size() > 0) {
        contextRows.add(d);
        if (contextRows.size() == WINDOW) {
          break;
        }
      }
    }
    boolean expiryDay = expirySuppressed(now.toLocalDate());
    List<DotState> dots = new ArrayList<>(PROBES.size());
    for (Probe p : PROBES) {
      boolean alive = false;
      for (JsonNode d : contextRows) {
        if (p.alive().test(d)) {
          alive = true;
          break;
        }
      }
      // `required` means "expected alive TODAY" (class javadoc). On a monthly index-expiry day the
      // OI reads are S24-suppressed to NEUTRAL by design, so the quadrant dots are NOT expected
      // alive — dropping the flag here keeps every consumer (paging, /status count, UI badge)
      // agreeing with the no-outage decision instead of each needing its own exemption.
      boolean suppressedToday = expiryDay && OI_QUADRANT_DOTS.contains(p.dot());
      String detail;
      if (alive) {
        detail = "input live in the last " + contextRows.size() + " context-bearing rejections";
      } else if (contextRows.isEmpty()) {
        detail =
            scanned.isEmpty()
                ? "no rejections yet today"
                : "UNINFORMATIVE — " + scanned.size() + " rejections scanned, none carry context"
                    + " (early-rail blocks only)";
      } else if (suppressedToday) {
        detail =
            "NEUTRAL by design — monthly index-expiry day, OI reads S24-suppressed (not an outage)";
      } else {
        detail = "input dead across " + contextRows.size() + " context-bearing rejections";
      }
      dots.add(
          new DotState(p.dot(), alive, required.contains(p.dot()) && !suppressedToday, detail));
    }
    return new DotHealth(
        now.toOffsetDateTime().toString(), session, scanned.size(), contextRows.size(),
        List.copyOf(dots));
  }

  /** The 5-min alerting sweep; only REQUIRED dots page, once per day per transition. */
  @Scheduled(fixedDelay = 300_000, initialDelay = 180_000, scheduler = "monitorTaskScheduler")
  public void sweep() {
    try {
      ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
      if (!inSession(now) || now.toLocalTime().isBefore(ARMED_FROM)) {
        return;
      }
      DotHealth health = evaluate();
      if (health.rowsInspected() == 0) {
        return; // engine-silence is the data-plane canary's problem, not a dot verdict
      }
      LocalDate today = now.toLocalDate();
      Map<String, DotState> byDot = new LinkedHashMap<>();
      health.dots().forEach(s -> byDot.put(s.dot(), s));
      boolean expiryDay = expirySuppressed(today);
      for (String dot : required) {
        DotState state = byDot.get(dot);
        if (state == null) {
          continue;
        }
        if (expiryDay && OI_QUADRANT_DOTS.contains(dot)) {
          continue; // S24 suppression: NEUTRAL quadrants are by-design today, never an outage page
        }
        if (!state.alive()) {
          if (!today.equals(alertedDeadOn.get(dot))) {
            alertedDeadOn.put(dot, today);
            log.error("dot canary: required dot '{}' input DEAD — {}", dot, state.detail());
            send("ArthaYantra dot canary: " + dot + " DEAD",
                "required dot '" + dot + "' has no live input — " + state.detail()
                    + " (composite silently re-capped)");
          }
          deadNow.add(dot);
        } else if (deadNow.remove(dot)) {
          send("ArthaYantra dot canary recovered", "dot '" + dot + "' input is live again");
        }
      }
    } catch (RuntimeException e) {
      log.warn("dot canary sweep failed: {}", e.toString());
    }
  }

  private void send(String title, String message) {
    try {
      events.publishEvent(new DotInputAlert(title, message));
    } catch (RuntimeException e) {
      log.warn("dot canary alert failed: {}", e.getMessage());
    }
  }

  private boolean expirySuppressed(LocalDate today) {
    try {
      return calendar.isMonthlyIndexExpiryDay(today) || bseCalendar.isMonthlyIndexExpiryDay(today);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary
    }
  }

  private boolean inSession(ZonedDateTime now) {
    try {
      return calendar.isOpen(now.toInstant()) && now.toLocalTime().isBefore(SESSION_END);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary
    }
  }
}
