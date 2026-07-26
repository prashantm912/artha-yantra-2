package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
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
  private final RejectionReader rejectionReader;
  private final SignalCompareReader compareReader;
  private final StrategyEvidenceReader strategyEvidenceReader;
  private final ObjectMapper objectMapper;

  public InsightController(
      InsightRepository repository,
      TrustService trustService,
      NotificationEventsRepository notifications,
      RejectionReader rejectionReader,
      SignalCompareReader compareReader,
      StrategyEvidenceReader strategyEvidenceReader,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.trustService = trustService;
    this.notifications = notifications;
    this.rejectionReader = rejectionReader;
    this.compareReader = compareReader;
    this.strategyEvidenceReader = strategyEvidenceReader;
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

  /**
   * The fired-vs-rejected Stage-1 contrast for a (version, IST-day) — composite score + dot-supports
   * only (§4.2 Stage 1; the full per-rail contrast is Stage 2, gated on §13 row 19's fired-side
   * rail-operand side-channel).
   */
  public record FiredVsRejectedResponse(
      UUID strategyVersionId,
      LocalDate day,
      List<RejectionReader.FiredRow> fired,
      List<RejectionReader.RejectedRow> rejected,
      RejectionReader.Contrast contrast) {}

  /** Owner feedback body (§2.4). */
  public record FeedbackRequest(String verdict, String note) {}

  /** The result of a triage write (ack/dismiss/feedback). */
  public record TriageResponse(UUID id, String status) {}

  /**
   * A one-click PROPOSE body (§1.2 / §9.1). {@code action} ∈ the §2.4 enum; {@code qty} optionally
   * overrides a ticket prefill's suggested quantity. Every other target is derived from the insight
   * itself — the request stays minimal (and typed, so it enumerates into the contract).
   */
  public record ActRequest(String action, Long qty) {}

  /**
   * The result of a PROPOSE (§9.1). {@code status}: {@code DONE} (an in-module idempotent write —
   * ack/dismiss/mute), {@code PREFILL} (an assembled body the owner's browser posts to
   * {@code target*} — the click NEVER places the order here), or {@code PROPOSED} (an existing
   * idempotent endpoint the owner's browser calls). Every {@code /act} writes an {@code insight_actions}
   * row FIRST (§2.4), so "the app suggested it and I clicked" is reconstructable even when the target
   * endpoint has no table audit. Nulls drop.
   */
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record ActResponse(
      UUID insightId,
      String action,
      String status,
      String targetMethod,
      String targetEndpoint,
      SignalCompareReader.TicketPrefill ticket,
      JournalDraft journal,
      String instrument,
      Long sellDecisionId,
      String note) {}

  /** A journal-draft prefill (§8.6 draft-accept flow) — the owner's browser posts it to /journal. */
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record JournalDraft(Long signalId, Long paperPositionId, List<String> tags, String note) {}

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

  /**
   * The fired-vs-rejected Stage-1 contrast for a strategy version over an IST day (§4.2) — composite
   * score + dot-supports for the fired signals vs the rejected rows, so "what almost fired" is
   * legible next to what did. {@code day} defaults to today (IST).
   */
  @GetMapping("/fired-vs-rejected")
  public FiredVsRejectedResponse firedVsRejected(
      @RequestParam UUID strategyVersionId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
    LocalDate istDay = day != null ? day : LocalDate.now(Ist.ZONE);
    RejectionReader.FiredVsRejected fvr = rejectionReader.firedVsRejected(strategyVersionId, istDay);
    return new FiredVsRejectedResponse(
        strategyVersionId, istDay, fvr.fired(), fvr.rejected(), fvr.contrast());
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

  /**
   * Candidate-trade compare (§4.1): {@code ?signalIds=a,b,c} (2–6, same IST session) → a matrix of the
   * priority the engine ALREADY computed per signal, plus cost / R:R / trust. 422 on a comparability
   * violation (different sessions, wrong count).
   */
  @GetMapping("/compare")
  public SignalCompareReader.CompareResult compare(@RequestParam List<Long> signalIds) {
    return compareReader.compare(signalIds);
  }

  /**
   * The qualification dossier (§5.1): the graduation row + criteria + threshold-crossing timeline +
   * GRADUATED marker + rejection profile + open sell-decisions, server-assembled for one strategy.
   */
  @GetMapping("/strategy-dossier/{strategyId}")
  public StrategyEvidenceReader.Dossier strategyDossier(@PathVariable UUID strategyId) {
    return strategyEvidenceReader.dossier(strategyId);
  }

  /**
   * The one-click PROPOSE executor (§1.2 / §9.1). It NEVER places an order itself: order-bearing
   * actions return a PREFILL the owner's browser posts to the existing endpoint (keeping the
   * manual-checks gate + risk governor + that endpoint's own audit on the real mutation path — "the
   * runtime click is the owner's"); watchlist-add / sell-ack return a PROPOSED instruction the browser
   * calls; ack / dismiss / mute are in-module idempotent writes. Every path writes an
   * {@code insight_actions} row FIRST (§2.4). Trust gate (§7.4 / hard-line): a BLOCKED insight refuses
   * ALL actions (422); a DEGRADED insight refuses the order-placing prefills.
   */
  @PostMapping("/{id}/act")
  public ActResponse act(@PathVariable UUID id, @RequestBody(required = false) ActRequest body) {
    Insight insight =
        repository.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "insight not found"));
    String action = body == null ? null : body.action();
    if (action == null || action.isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "action is required");
    }
    if ("BLOCKED".equals(insight.dataTrust())) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_STALE,
          "insight data_trust is BLOCKED — advice cannot outrun its data (§7.4)",
          Map.of("dataTrust", "BLOCKED", "reasons", insight.trustReasons() == null ? List.of() : insight.trustReasons()));
    }
    boolean orderAction = "OPEN_TICKET".equals(action) || "TAKE_SIGNAL".equals(action);
    if (orderAction && "DEGRADED".equals(insight.dataTrust())) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_STALE,
          "insight data_trust is DEGRADED — the order prefill is trust-gated off (§1.2)",
          Map.of("dataTrust", "DEGRADED", "reasons", insight.trustReasons() == null ? List.of() : insight.trustReasons()));
    }
    return switch (action) {
      case "ACK" -> {
        triage(id, Insight.Status.ACKED, "ACK");
        yield done(id, action, "acknowledged");
      }
      case "DISMISS" -> {
        triage(id, Insight.Status.DISMISSED, "DISMISS");
        yield done(id, action, "dismissed");
      }
      case "MUTE_TYPE", "UNMUTE_TYPE" -> {
        Map<String, Object> ref = new java.util.LinkedHashMap<>();
        ref.put("type", insight.type());
        if (insight.scope() != null && insight.scope().startsWith("strategy:")) {
          ref.put("scope", insight.scope());
        }
        repository.insertAction(id, action, refJson(ref), "owner");
        yield done(
            id,
            action,
            "MUTE_TYPE".equals(action)
                ? "type muted for future delivery (strategy-scoped when applicable)"
                : "type mute removed for future delivery (strategy-scoped when applicable)");
      }
      case "OPEN_TICKET" -> ticket(insight, id, action, body, "/api/v1/paper/orders");
      case "TAKE_SIGNAL" -> ticket(insight, id, action, body, null);
      case "JOURNAL_DRAFT_ACCEPT" -> journalDraft(insight, id);
      case "ADD_WATCHLIST" -> watchlist(insight, id);
      case "ACK_SELL_DECISION" -> sellAck(insight, id);
      default -> throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "unsupported action: " + action);
    };
  }

  /** OPEN_TICKET / TAKE_SIGNAL → a PREFILL the owner's browser posts; NEVER places the order here. */
  private ActResponse ticket(Insight insight, UUID id, String action, ActRequest body, String endpoint) {
    long signalId =
        signalScope(insight)
            .orElseThrow(() -> new ApiException(422, ErrorCodes.VALIDATION_FAILED, action + " needs a signal-scoped insight"));
    SignalCompareReader.TicketPrefill prefill =
        compareReader
            .ticketPrefill(signalId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "signal " + signalId + " not found"));
    if (body != null && body.qty() != null) {
      prefill =
          new SignalCompareReader.TicketPrefill(
              prefill.signalId(), prefill.exchange(), prefill.tradingsymbol(), prefill.side(),
              body.qty(), prefill.stopLoss(), prefill.target());
    }
    markActed(id, action, Map.of("signalId", signalId));
    String target = endpoint != null ? endpoint : "/api/v1/signals/" + signalId + "/taken";
    return new ActResponse(
        id, action, "PREFILL", "POST", target, prefill, null, null, null,
        "Review then confirm from your session — the manual-checks gate + risk governor apply on the real call.");
  }

  /** JOURNAL_DRAFT_ACCEPT → a journal draft prefill (§8.6) the owner's browser posts to /journal. */
  private ActResponse journalDraft(Insight insight, UUID id) {
    long signalId =
        signalScope(insight)
            .orElseThrow(() -> new ApiException(422, ErrorCodes.VALIDATION_FAILED, "JOURNAL_DRAFT_ACCEPT needs a signal-scoped insight"));
    markActed(id, "JOURNAL_DRAFT_ACCEPT", Map.of("signalId", signalId));
    JournalDraft draft = new JournalDraft(signalId, null, null, "Draft from insight " + id + ": " + insight.title());
    return new ActResponse(
        id, "JOURNAL_DRAFT_ACCEPT", "PREFILL", "POST", "/api/v1/journal", null, draft, null, null,
        "Review then save the draft from your session.");
  }

  /** ADD_WATCHLIST → a PROPOSED instruction; the owner's browser adds it (market-data endpoint). */
  private ActResponse watchlist(Insight insight, UUID id) {
    String instrument =
        watchlistInstrument(insight)
            .orElseThrow(() -> new ApiException(422, ErrorCodes.VALIDATION_FAILED, "ADD_WATCHLIST needs a signal- or underlying-scoped insight"));
    markActed(id, "ADD_WATCHLIST", Map.of("instrument", instrument));
    return new ActResponse(
        id, "ADD_WATCHLIST", "PROPOSED", "POST", "/api/v1/watchlists/items", null, null, instrument, null,
        "Add to a watchlist from your session (market-data endpoint).");
  }

  /** ACK_SELL_DECISION → a PROPOSED instruction to the EXISTING sell-decision acknowledge endpoint. */
  private ActResponse sellAck(Insight insight, UUID id) {
    long sellId =
        sellDecisionId(insight)
            .orElseThrow(() -> new ApiException(422, ErrorCodes.VALIDATION_FAILED, "not a sell-decision insight"));
    markActed(id, "ACK", Map.of("sellDecisionId", sellId));
    return new ActResponse(
        id, "ACK_SELL_DECISION", "PROPOSED", "POST", "/api/v1/signals/sell-decisions/" + sellId + "/ack",
        null, null, null, sellId, "Acknowledge the sell decision from your session.");
  }

  private void markActed(UUID id, String action, Map<String, Object> ref) {
    repository.updateStatus(id, Insight.Status.ACTED.name());
    repository.insertAction(id, action, refJson(ref), "owner");
  }

  private ActResponse done(UUID id, String action, String note) {
    return new ActResponse(id, action, "DONE", null, null, null, null, null, null, note);
  }

  /** The signal id of a {@code signal:<id>} scope, else empty. */
  private static java.util.Optional<Long> signalScope(Insight insight) {
    String scope = insight.scope();
    if (scope == null || !scope.startsWith("signal:")) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(Long.parseLong(scope.substring("signal:".length())));
    } catch (NumberFormatException e) {
      return java.util.Optional.empty();
    }
  }

  /** The instrument ({@code exch:tradingsymbol}) for a watchlist add: signal- or underlying-scoped. */
  private java.util.Optional<String> watchlistInstrument(Insight insight) {
    String scope = insight.scope();
    if (scope != null && scope.startsWith("underlying:")) {
      String rest = scope.substring("underlying:".length()); // <exch>:<name>
      return rest.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(rest);
    }
    return signalScope(insight)
        .flatMap(compareReader::ticketPrefill)
        .map(p -> p.exchange() + ":" + p.tradingsymbol());
  }

  /** The sell-decision id from a SELL_DECISION insight's evidence {@code ref.sellDecisionId}. */
  private static java.util.Optional<Long> sellDecisionId(Insight insight) {
    JsonNode evidence = insight.evidence();
    if (evidence == null || !evidence.isArray()) {
      return java.util.Optional.empty();
    }
    for (JsonNode e : evidence) {
      JsonNode ref = e.path("ref").path("sellDecisionId");
      if (ref.isNumber()) {
        return java.util.Optional.of(ref.asLong());
      }
    }
    return java.util.Optional.empty();
  }

  private String refJson(Map<String, Object> ref) {
    try {
      return objectMapper.writeValueAsString(ref);
    } catch (Exception e) {
      return null;
    }
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
