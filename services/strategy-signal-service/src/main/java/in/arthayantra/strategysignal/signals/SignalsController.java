package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.csv.CsvExport;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
  private final TakeAdmission admission;

  /** Wires the repository, the domain-event publisher (paper listens for TAKEN) + the take gate. */
  public SignalsController(
      SignalRepository repository, ApplicationEventPublisher events, TakeAdmission admission) {
    this.repository = repository;
    this.events = events;
    this.admission = admission;
  }

  /** Paged/filtered history. */
  @GetMapping
  public SignalViews.SignalPage list(
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
    return new SignalViews.SignalPage(
        items.stream().map(SignalsController::dto).toList(), boundedLimit, boundedOffset);
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
  public SignalViews.SignalFeed active() {
    return new SignalViews.SignalFeed(
        repository.active().stream().map(SignalsController::dto).toList());
  }

  /** Signal detail + the full reasoning payload. */
  @GetMapping("/{id}")
  public SignalViews.SignalDto detail(@PathVariable long id) {
    SignalRepository.SignalRow row =
        repository.find(id)
            .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal"));
    return dto(row);
  }

  /** Owner executed an ENTRY manually at the broker; optionally opens paper when a qty is given. */
  @PostMapping("/{id}/taken")
  public SignalViews.SignalDto taken(
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
    // The order intent is admitted BEFORE the CAS. Both writers refuse an unknown or misaligned lot,
    // and both are reached through a synchronous @EventListener — i.e. one statement too late to
    // veto the transition they were already told about. Committing first and discovering the refusal
    // after leaves a TAKEN anchor with no order, no position and no rejection row, which is
    // PERMANENT (TakenSignalResolver fires only on PaperPositionClosed) and which activeEntry then
    // reads as an open entry, suppressing re-entry on that instrument for that version forever.
    // Gated on ACTIVE so the idempotent double-take below stays a 200: re-taking an already-TAKEN
    // signal must not start 422-ing because its instrument master has since gone quiet.
    //
    // ⚠️ That status is the INITIAL read, and a refusal must be re-checked against a SECOND one —
    // cross-vendor review round 1, Major. The interleaving is real, not theoretical: A reads ACTIVE,
    // B (a concurrent manual take or the auto-paper listener) wins ACTIVE→TAKEN, then A's admission
    // refuses on the master data B already got past. Refusing there would answer 422 for a signal
    // that IS now TAKEN — the caller's intended end state, reached by someone else — breaking the
    // very idempotency contract the ACTIVE gate exists to preserve. The round-1 argument that "a row
    // that has since moved off ACTIVE loses the CAS anyway" covered only the admitted path; a
    // refusal RETURNS BEFORE the CAS is ever attempted, so it needed its own re-read.
    // A refusal that survives the re-read falls through to the CAS, which no-ops on the TAKEN row
    // and answers 200; an exception returns the same 200 rather than a 500 for the same reason.
    if ("ACTIVE".equals(signal.status())) {
      TakeAdmission.Verdict verdict;
      try {
        verdict = admission.admit(id, qty);
      } catch (RuntimeException e) {
        if (takenConcurrently(id)) {
          return detail(id);
        }
        throw e;
      }
      if (!verdict.admitted() && !takenConcurrently(id)) {
        // The verdict carries its OWN status: 422 for a refusal the caller could have avoided,
        // 503 when a dependency could not answer. Hardcoding 422 here made a retryable outage
        // look permanent, and disagreed with the writer, which already threw 503 for the same
        // fact (H44 round 3).
        throw new ApiException(
            verdict.httpStatus(), verdict.code(), verdict.reason(), verdict.details());
      }
    }
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
  public SignalViews.SignalDto dismiss(@PathVariable long id) {
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

  /**
   * Whether a concurrent take won {@code ACTIVE→TAKEN} while this call was admitting — the second
   * read the refusal path needs so a lost race answers 200, not 422/500.
   *
   * <p>A failure of the re-read itself returns {@code false}: it cannot CONFIRM the caller's intent
   * was reached, so the original outcome stands rather than being converted to a success on a read
   * we could not trust. That also keeps the original exception the one that propagates.
   */
  private boolean takenConcurrently(long id) {
    try {
      return repository
          .find(id)
          .map(SignalRepository.SignalRow::status)
          .filter("TAKEN"::equals)
          .isPresent();
    } catch (RuntimeException reReadFailed) {
      return false;
    }
  }

  private SignalRepository.SignalRow requireExists(long id) {
    return repository.find(id)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal"));
  }

  /**
   * The row as this surface renders it. NOT the row itself: {@code minerviniDetail} and {@code
   * manasAroraDetail} have never been emitted here, and the row's {@code exitReason} sits after
   * them, so returning {@code SignalRow} would add two keys and move a third.
   */
  private static SignalViews.SignalDto dto(SignalRepository.SignalRow row) {
    return new SignalViews.SignalDto(
        row.id(),
        row.strategyVersionId(),
        row.exchange(),
        row.tradingsymbol(),
        row.interval(),
        row.signalType(),
        row.side(),
        row.entryPrice(),
        row.stopLoss(),
        row.target(),
        row.compositeScore(),
        row.scoreBreakdown(),
        row.status(),
        row.generatedAt(),
        row.expiresAt(),
        row.suggestedQty(),
        row.tradeableExchange(),
        row.tradeableTradingsymbol(),
        row.scalperDetail(),
        row.exitReason());
  }
}
