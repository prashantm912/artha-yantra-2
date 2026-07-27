package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.csv.CsvExport;
import in.arthayantra.common.web.error.ApiException;
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
            "tradeable_exchange", "tradeable_tradingsymbol", "strategy_version_id", "expires_at",
            "exit_reason");
    for (SignalRepository.SignalRow r : rows) {
      w.row(
          r.id(), r.generatedAt(), r.status(), r.exchange(), r.tradingsymbol(), r.interval(),
          r.signalType(), r.side(), r.entryPrice(), r.stopLoss(), r.target(), r.compositeScore(),
          r.suggestedQty(), r.tradeableExchange(), r.tradeableTradingsymbol(), r.strategyVersionId(),
          r.expiresAt(), r.exitReason());
    }
    return w.download("signals.csv");
  }

  /** Currently takeable ENTRY calls; EXIT advisories remain on history and sell-decision surfaces. */
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

  /** Owner executed an ENTRY manually at the broker; optionally opens paper when a qty is given. */
  @PostMapping("/{id}/taken")
  public Map<String, Object> taken(
      @PathVariable long id, @RequestBody(required = false) TakenRequest request) {
    SignalRepository.SignalRow signal = requireExists(id);
    if (!"ENTRY".equals(signal.signalType())) {
      throw new ApiException(
          422,
          ErrorCodes.VALIDATION_FAILED,
          "signal #" + id + " is " + signal.signalType() + " and cannot be taken as an entry",
          Map.of("signalId", id, "signalType", signal.signalType()));
    }
    Integer qty = request == null ? null : request.qty();
    BigDecimal fillPrice =
        request == null || request.fillPrice() == null ? null : new BigDecimal(request.fillPrice());
    // A signal carries a scalper_detail side-channel iff a scalper strategy emitted it (E10) — the
    // flag rides the event so the paper listener charges the open to a 5-account sub-ledger.
    boolean scalper = signal.scalperDetail() != null;
    // Guarded CAS ACTIVE→TAKEN: publish (and thus open a paper position) only if THIS call won the
    // transition — an already-TAKEN signal (auto-paper or a double-submit) is an idempotent no-op.
    if (repository.transitionIf(id, "ACTIVE", "TAKEN")) {
      events.publishEvent(new SignalTaken(id, qty, fillPrice, scalper));
    }
    return detail(id);
  }

  /**
   * Reject a call — legal from ACTIVE only (task_6f1372da). This was an UNCONDITIONAL {@code
   * transition}, which made the Dismiss button a third door into the manual-ticket orphan: a hand
   * ticket fills → the anchor is TAKEN → the owner clicks Dismiss → the anchor is DISMISSED → {@code
   * SignalRepository.activeEntry:166-178} (ACTIVE/TAKEN only) stops resolving it → the live engine can
   * never emit that position's exit, ever. A TAKEN anchor means a position is OPEN, and the answer to
   * that is to CLOSE the position, not to discard its anchor — a TAKEN signal whose position has since
   * closed is already resolved TAKEN→EXPIRED by {@link
   * in.arthayantra.strategysignal.paper.TakenSignalResolver}, so dismissing TAKEN is never right.
   *
   * <p>A lost CAS is a 422, NOT the idempotent no-op {@link #taken} uses two methods above. The
   * precedents differ because the END STATE differs: a double-take lands on TAKEN, which IS the
   * caller's intent, so no-op is honest. A dismiss that loses the CAS leaves the row NOT dismissed —
   * reporting 200 would tell the owner their click worked while the signal stays in the feed, the same
   * "silently refused button reads as success" hazard #881 was built to kill. The status is re-read
   * AFTER the failed CAS so the body names the state that actually blocked it, not a pre-CAS read that
   * a concurrent 15:45 sweep may already have invalidated.
   */
  @PostMapping("/{id}/dismiss")
  public Map<String, Object> dismiss(@PathVariable long id) {
    requireExists(id);
    if (!repository.transitionIf(id, "ACTIVE", "DISMISSED")) {
      String status = repository.find(id).map(SignalRepository.SignalRow::status).orElse(null);
      throw new ApiException(
          422,
          ErrorCodes.VALIDATION_FAILED,
          "signal #" + id + " is " + status + " and can no longer be dismissed — only an ACTIVE"
              + " signal can be discarded; a TAKEN anchor holds an open paper position, so close the"
              + " position instead of discarding the anchor the engine exits through",
          Map.of("signalId", id, "signalStatus", String.valueOf(status)));
    }
    return detail(id);
  }

  private SignalRepository.SignalRow requireExists(long id) {
    return repository.find(id)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal"));
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
    // Why an EXIT fired. NULL on ENTRY rows and on EXITs predating V048 — those reasons only
    // ever reached a log line. The endpoint returns a Map, so springdoc does not enumerate it
    // and adding the key does not drift the contract.
    dto.put("exitReason", row.exitReason());
    return dto;
  }
}
