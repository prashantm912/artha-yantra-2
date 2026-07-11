package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.insights.NotificationEventsRepository.NotificationEventRow;
import in.arthayantra.strategysignal.insights.TrustSnapshot.FamilyTrust;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The insight plane's read + triage surface (INT design §9.1), all TYPED records (the SS Map-return
 * ratchet forbids new {@code Map} endpoints — Maps are invisible to the contract gate). I1 ships the
 * read feed / Focus / summary / trust-summary + the notification_events read + the display-side
 * triage writes (ack / dismiss / feedback). The PROPOSE {@code /act} execution, {@code /compare} and
 * {@code /strategy-dossier} are later waves (I2/I3). Everything here is display-only / shadow mode.
 */
@RestController
@RequestMapping("/api/v1/insights")
public class InsightController {

  private final InsightRepository repository;
  private final TrustService trustService;
  private final NotificationEventsRepository notifications;
  private final ObjectMapper objectMapper;

  public InsightController(
      InsightRepository repository,
      TrustService trustService,
      NotificationEventsRepository notifications,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.trustService = trustService;
    this.notifications = notifications;
    this.objectMapper = objectMapper;
  }

  /** The {@code {items}} feed envelope. */
  public record InsightListResponse(List<Insight> items, int limit, int offset) {}

  /** A (key, count) badge tally. */
  public record Count(String key, long count) {}

  /** Focus-header + feed badge counts (§9.1 summary). */
  public record InsightSummaryResponse(List<Count> bySeverity, List<Count> byStatus, long suppressed) {}

  /** The one-call Focus surface: ranked signal queue + attention queue (§3.1). */
  public record FocusResponse(List<Insight> signalQueue, List<Insight> attentionQueue, long suppressed) {}

  /** Per-family trust for the dashboard/Data-Ops trust strip (§7.3). */
  public record TrustSummaryResponse(String asOf, List<FamilyTrust> families) {}

  /** The notifier delivery-audit read envelope (§13 row 15). */
  public record NotificationEventsResponse(List<NotificationEventRow> items) {}

  /** Owner feedback body (§2.4). */
  public record FeedbackRequest(String verdict, String note) {}

  /** The result of a triage write (ack/dismiss/feedback). */
  public record TriageResponse(UUID id, String status) {}

  /** Paged/filtered feed, newest first ({@code {items}} envelope). */
  @GetMapping
  public InsightListResponse list(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
      @RequestParam(defaultValue = "false") boolean includeSuppressed,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    OffsetDateTime from = null;
    OffsetDateTime to = null;
    if (day != null) {
      // Explicit IST-day bounds (in-container now() is UTC — never ::date, #IST-midnight trap).
      from = day.atStartOfDay(Ist.ZONE).toOffsetDateTime();
      to = day.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();
    }
    List<Insight> items =
        repository.list(type, severity, status, scope, from, to, includeSuppressed, boundedLimit, boundedOffset);
    return new InsightListResponse(items, boundedLimit, boundedOffset);
  }

  /** One insight (self-contained for audit, §9.4). */
  @GetMapping("/{id}")
  public Insight get(@PathVariable UUID id) {
    return repository.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "insight not found"));
  }

  /** Badge counts by severity + status for OPEN insights. */
  @GetMapping("/summary")
  public InsightSummaryResponse summary() {
    return new InsightSummaryResponse(
        toCounts(repository.countsBy("severity")),
        toCounts(repository.countsBy("status")),
        repository.suppressedOpenCount());
  }

  /** The ranked signal queue + attention queue in one call (Focus panel, §8.1). */
  @GetMapping("/focus")
  public FocusResponse focus(@RequestParam(defaultValue = "20") int limit) {
    int bounded = Math.min(Math.max(limit, 1), 100);
    return new FocusResponse(
        repository.focusSignals(bounded), repository.focusAttention(bounded), repository.suppressedOpenCount());
  }

  /** Per-family trust strip (§7.3). */
  @GetMapping("/trust-summary")
  public TrustSummaryResponse trustSummary() {
    TrustSnapshot snap = trustService.snapshot();
    return new TrustSummaryResponse(snap.asOf(), snap.families());
  }

  /** The notifier delivery-audit ledger, newest first (§13 row 15). */
  @GetMapping("/notification-events")
  public NotificationEventsResponse notificationEvents(@RequestParam(defaultValue = "100") int limit) {
    return new NotificationEventsResponse(notifications.recent(Math.min(Math.max(limit, 1), 500)));
  }

  /** Acknowledge an insight (status → ACKED + an insight_actions row). */
  @PostMapping("/{id}/ack")
  public TriageResponse ack(@PathVariable UUID id) {
    return triage(id, Insight.Status.ACKED, "ACK");
  }

  /** Dismiss an insight (status → DISMISSED + an insight_actions row). */
  @PostMapping("/{id}/dismiss")
  public TriageResponse dismiss(@PathVariable UUID id) {
    return triage(id, Insight.Status.DISMISSED, "DISMISS");
  }

  /** Record owner feedback on an insight (USEFUL / NOT_USEFUL, §2.4). */
  @PostMapping("/{id}/feedback")
  public TriageResponse feedback(@PathVariable UUID id, @RequestBody FeedbackRequest body) {
    Insight insight =
        repository.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "insight not found"));
    String verdict = body == null ? null : body.verdict();
    if (!"USEFUL".equals(verdict) && !"NOT_USEFUL".equals(verdict)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "verdict must be USEFUL or NOT_USEFUL");
    }
    repository.upsertFeedback(id, verdict, body.note());
    return new TriageResponse(insight.id(), insight.status());
  }

  private TriageResponse triage(UUID id, Insight.Status status, String action) {
    if (repository.get(id).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "insight not found");
    }
    repository.updateStatus(id, status.name());
    repository.insertAction(id, action, targetRef(id), "owner");
    return new TriageResponse(id, status.name());
  }

  private String targetRef(UUID id) {
    try {
      return objectMapper.writeValueAsString(Map.of("insightId", id.toString()));
    } catch (Exception e) {
      return null;
    }
  }

  private static List<Count> toCounts(Map<String, Long> counts) {
    return counts.entrySet().stream().map(e -> new Count(e.getKey(), e.getValue())).toList();
  }
}
