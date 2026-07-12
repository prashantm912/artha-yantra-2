package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.csv.CsvExport;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The C-2.14 signal surface: history, active calls, reasoning detail, taken/dismiss. */
@RestController
@RequestMapping("/api/v1/signals")
public class SignalsController {

  /** Optional taken metadata. */
  public record TakenRequest(String fillPrice, Integer qty, String note) {}

  private final SignalRepository repository;
  private final ApplicationEventPublisher events;

  /** Wires the repository + the domain-event publisher (paper listens for TAKEN). */
  public SignalsController(SignalRepository repository, ApplicationEventPublisher events) {
    this.repository = repository;
    this.events = events;
  }

  /** Paged/filtered history. */
  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String book,
      @RequestParam(required = false) UUID strategyVersionId,
      @RequestParam(required = false) String exchange,
      @RequestParam(required = false) String tradingsymbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<SignalRepository.SignalRow> items =
        repository.list(
            status, book, strategyVersionId, exchange, tradingsymbol, from, to, boundedLimit,
            boundedOffset);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", items.stream().map(SignalsController::dto).toList());
    response.put("limit", boundedLimit);
    response.put("offset", boundedOffset);
    return response;
  }

  /**
   * The filtered signal history as a CSV download (audit §10 Phase-3 CSV export standard). Same
   * filters as {@link #list}; the JSON side-channel columns (score breakdown / scalper detail) are
   * omitted — the scalar signal fields only. Loud truncation via the shared {@link CsvExport} headers.
   */
  @GetMapping("/export")
  public org.springframework.http.ResponseEntity<byte[]> export(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String book,
      @RequestParam(required = false) UUID strategyVersionId,
      @RequestParam(required = false) String exchange,
      @RequestParam(required = false) String tradingsymbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    List<SignalRepository.SignalRow> rows =
        repository.list(
            status, book, strategyVersionId, exchange, tradingsymbol, from, to,
            CsvExport.DEFAULT_MAX_ROWS + 1, 0);
    CsvExport.Writer w =
        CsvExport.writer(
            CsvExport.DEFAULT_MAX_ROWS,
            "id", "generated_at", "status", "exchange", "tradingsymbol", "interval", "signal_type",
            "side", "entry_price", "stop_loss", "target", "composite_score", "suggested_qty",
            "tradeable_exchange", "tradeable_tradingsymbol", "strategy_version_id", "expires_at");
    for (SignalRepository.SignalRow r : rows) {
      w.row(
          r.id(), r.generatedAt(), r.status(), r.exchange(), r.tradingsymbol(), r.interval(),
          r.signalType(), r.side(), r.entryPrice(), r.stopLoss(), r.target(), r.compositeScore(),
          r.suggestedQty(), r.tradeableExchange(), r.tradeableTradingsymbol(), r.strategyVersionId(),
          r.expiresAt());
    }
    return w.download("signals.csv");
  }

  /** Currently live calls. */
  @GetMapping("/active")
  public Map<String, Object> active() {
    return Map.of("items", repository.active().stream().map(SignalsController::dto).toList());
  }

  /** Signal detail + the full reasoning payload. */
  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable long id) {
    SignalRepository.SignalRow row =
        repository.find(id)
            .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal"));
    return dto(row);
  }

  /** Owner executed manually at the broker; optionally opens a paper position (when a qty is given). */
  @PostMapping("/{id}/taken")
  public Map<String, Object> taken(
      @PathVariable long id, @RequestBody(required = false) TakenRequest request) {
    requireExists(id);
    Integer qty = request == null ? null : request.qty();
    BigDecimal fillPrice =
        request == null || request.fillPrice() == null ? null : new BigDecimal(request.fillPrice());
    // A signal carries a scalper_detail side-channel iff a scalper strategy emitted it (E10) — the
    // flag rides the event so the paper listener charges the open to a 5-account sub-ledger.
    boolean scalper = repository.find(id).map(r -> r.scalperDetail() != null).orElse(false);
    // Guarded CAS ACTIVE→TAKEN: publish (and thus open a paper position) only if THIS call won the
    // transition — an already-TAKEN signal (auto-paper or a double-submit) is an idempotent no-op.
    if (repository.transitionIf(id, "ACTIVE", "TAKEN")) {
      events.publishEvent(new SignalTaken(id, qty, fillPrice, scalper));
    }
    return detail(id);
  }

  /** Reject a call. */
  @PostMapping("/{id}/dismiss")
  public Map<String, Object> dismiss(@PathVariable long id) {
    requireExists(id);
    repository.transition(id, "DISMISSED");
    return detail(id);
  }

  private void requireExists(long id) {
    if (repository.find(id).isEmpty()) {
      throw new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal");
    }
  }

  private static Map<String, Object> dto(SignalRepository.SignalRow row) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", row.id());
    dto.put("strategyVersionId", row.strategyVersionId());
    dto.put("exchange", row.exchange());
    dto.put("tradingsymbol", row.tradingsymbol());
    dto.put("interval", row.interval());
    dto.put("signalType", row.signalType());
    dto.put("side", row.side());
    dto.put("entryPrice", row.entryPrice());
    dto.put("stopLoss", row.stopLoss());
    dto.put("target", row.target());
    dto.put("compositeScore", row.compositeScore());
    dto.put("scoreBreakdown", row.scoreBreakdown());
    dto.put("status", row.status());
    dto.put("generatedAt", row.generatedAt());
    dto.put("expiresAt", row.expiresAt());
    dto.put("suggestedQty", row.suggestedQty());
    dto.put("tradeableExchange", row.tradeableExchange());
    dto.put("tradeableTradingsymbol", row.tradeableTradingsymbol());
    dto.put("scalperDetail", row.scalperDetail());
    return dto;
  }
}
