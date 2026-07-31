package in.arthayantra.strategysignal.paper;

import in.arthayantra.common.web.csv.CsvExport;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.paper.PaperEventRepository.PaperEventRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The §F.6 paper-trading surface, routed by edge-gateway under {@code /api/v1/paper/**}. */
@RestController
@RequestMapping("/api/v1/paper")
public class PaperController {

  /**
   * Open-order body: from a signal (side derived) or a manual entry; optional SL/TP + book.
   * {@code clientOrderId} is the OPTIONAL audit-V2 idempotency key — a duplicate submission carrying
   * the same value returns the ORIGINAL position instead of a second fill.
   */
  public record OrderBody(
      Long signalId,
      String exchange,
      String tradingsymbol,
      String side,
      Long qty,
      BigDecimal price,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String book,
      String clientOrderId) {}

  /** Close body: an explicit price overrides the last tick. */
  public record CloseBody(BigDecimal price) {}

  /** Bracket-edit body: either level may be set (a {@code null} field leaves it unchanged); both null → 400. */
  public record BracketBody(BigDecimal stopLoss, BigDecimal takeProfit) {}

  /** Reset is guarded by an explicit confirm flag; scoped to a book (null → all books). */
  public record ResetBody(boolean confirm, String book) {}

  /** Owner edit of the starting capital for a book. */
  public record AccountBody(BigDecimal startingCapital, String book) {}

  /**
   * One paper-position lifecycle event (audit §7.2.2). {@code realizedPnl} rides as a string
   * (money-as-string convention); it and {@code reason} are null on an OPENED event.
   */
  public record PaperEventDto(
      long id,
      long positionId,
      String kind,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      @Schema(types = {"string", "null"}) String reason,
      @Schema(types = {"string", "null"}) String realizedPnl,
      OffsetDateTime createdAt) {}

  /** The {@code items} envelope for {@code GET /events} — a typed record, never a Map (contract gate). */
  public record PaperEventsResponse(List<PaperEventDto> items) {}

  private final PaperService paper;
  private final PaperAccountService account;
  private final InstrumentMetaClient instruments;
  private final PaperAdminAuditLedger adminAudit;
  private final PaperEventRepository events;

  /**
   * Wires the ledger + account services + the instrument-meta lookup + the admin-audit trail (V14) +
   * the lifecycle-event ledger (§7.2.2).
   */
  public PaperController(
      PaperService paper,
      PaperAccountService account,
      InstrumentMetaClient instruments,
      PaperAdminAuditLedger adminAudit,
      PaperEventRepository events) {
    this.paper = paper;
    this.account = account;
    this.instruments = instruments;
    this.adminAudit = adminAudit;
    this.events = events;
  }

  /** The account header for a book ({@code book} absent → the aggregate across all books). */
  @GetMapping("/account")
  public PaperAccountService.AccountDto account(@RequestParam(required = false) String book) {
    return account.account(book);
  }

  /** Edit a book's starting capital — the change is audited (V14: previous → new). */
  @PutMapping("/account")
  public PaperAccountService.AccountDto updateAccount(@RequestBody AccountBody body) {
    if (body.startingCapital() == null || body.startingCapital().signum() < 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "startingCapital must be non-negative");
    }
    if (body.book() == null || body.book().isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "book is required to edit capital");
    }
    BigDecimal previous = account.startingCapital(body.book());
    account.updateStartingCapital(body.book(), body.startingCapital());
    adminAudit.recordCapitalChange(body.book(), previous, body.startingCapital());
    return account.account(body.book());
  }

  /** Open positions with mark-to-market P&amp;L ({@code book} absent → all books). */
  @GetMapping("/positions")
  public PaperViews.PositionList positions(@RequestParam(required = false) String book) {
    return new PaperViews.PositionList(paper.openPositions(book));
  }

  /** The closed-trade ledger (optional {@code book} + {@code symbol}); feeds the chart marks. */
  @GetMapping("/trades")
  public PaperViews.TradePage trades(
      @RequestParam(required = false) String book,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false) String symbol,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    String tradingsymbol =
        symbol == null ? null : symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    return new PaperViews.TradePage(
        paper.trades(book, from, to, tradingsymbol, boundedLimit, boundedOffset),
        boundedLimit,
        boundedOffset);
  }

  /**
   * The closed-trade ledger as a CSV download (audit §10 Phase-3 CSV export standard). Same filters as
   * {@link #trades}; loud truncation via the shared {@link CsvExport} headers.
   */
  @GetMapping("/trades/export")
  public ResponseEntity<byte[]> tradesExport(
      @RequestParam(required = false) String book,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false) String symbol) {
    String tradingsymbol =
        symbol == null ? null : symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
    List<PaperService.TradeDto> rows =
        paper.trades(book, from, to, tradingsymbol, CsvExport.DEFAULT_MAX_ROWS + 1, 0);
    CsvExport.Writer w =
        CsvExport.writer(
            CsvExport.DEFAULT_MAX_ROWS,
            "id", "exchange", "tradingsymbol", "side", "qty", "avg_entry_price", "realized_pnl",
            "opened_at", "closed_at");
    for (PaperService.TradeDto t : rows) {
      w.row(
          t.id(), t.exchange(), t.tradingsymbol(), t.side(), t.qty(), t.avgEntryPrice(),
          t.realizedPnl(), t.openedAt(), t.closedAt());
    }
    return w.download("paper_trades.csv");
  }

  /** Daily equity + win rate / expectancy for a book ({@code book} absent → all books). */
  @GetMapping("/pnl")
  public PaperViews.Pnl pnl(@RequestParam(required = false) String book) {
    return paper.pnl(book);
  }

  /**
   * The append-only paper-position lifecycle events (OPENED / CLOSED / BRACKET_HIT / SETTLED), newest
   * first — audit §7.2.2. Filters: {@code positionId} (the trade-chain / position-detail read),
   * {@code book}, and {@code day} (an IST calendar date, bounded by explicit +05:30 instants). The
   * live counterpart is the {@code /topic/paper.events} channel the FE hook appends from.
   */
  @GetMapping("/events")
  public PaperEventsResponse events(
      @RequestParam(required = false) Long positionId,
      @RequestParam(required = false) String book,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<PaperEventDto> items =
        events.query(positionId, book, day, boundedLimit, boundedOffset).stream()
            .map(PaperController::eventDto)
            .toList();
    return new PaperEventsResponse(items);
  }

  private static PaperEventDto eventDto(PaperEventRow row) {
    return new PaperEventDto(
        row.id(), row.positionId(), row.kind(), row.book(), row.exchange(), row.tradingsymbol(),
        row.side(), row.qty(), row.reason(),
        row.realizedPnl() == null ? null : row.realizedPnl().toPlainString(), row.createdAt());
  }

  /**
   * Simulate an entry (from a signal or manual); the book is on the body or resolved from the signal.
   * Routes through {@code openManualOrder} so the per-book risk governor gates the fill (audit V1) — the
   * signal-taken path stays on {@code openOrder} (already gated at emission). Pure input validation
   * (qty &gt; 0, audit-V4 lot-size multiple) runs HERE, BEFORE the service, so a malformed order 400s
   * regardless of governor state — a since-tripped governor must not turn a bad input into a 422. A
   * duplicate {@code clientOrderId} (audit V2) replays the ORIGINAL position with a 200.
   */
  @PostMapping("/orders")
  public ResponseEntity<PaperService.PositionDto> order(@RequestBody OrderBody body) {
    if (body.qty() == null || body.qty() <= 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "qty must be a positive integer");
    }
    // Audit V4: a hand ticket for an F&O instrument whose qty is not a whole multiple of the contract
    // lot would be broker-rejected live (Upstox UDAPI1104). Validate only when the instrument is given
    // explicitly (a signal-derived symbol is resolved deeper in openOrder). Equity / unknown instruments
    // resolve to lot 1 (RestInstrumentMetaClient's equity proxy), so qty % 1 == 0 always passes.
    if (body.exchange() != null && body.tradingsymbol() != null) {
      long lotSize = instruments.meta(body.exchange(), body.tradingsymbol()).lotSize();
      if (lotSize > 1 && body.qty() % lotSize != 0) {
        throw new ApiException(
            400,
            ErrorCodes.VALIDATION_FAILED,
            "qty " + body.qty() + " is not a multiple of the lot size " + lotSize + " for "
                + body.exchange() + ":" + body.tradingsymbol(),
            Map.of("lotSize", lotSize, "qty", body.qty()));
      }
    }
    PaperService.OrderRequest request =
        new PaperService.OrderRequest(
            body.signalId(), body.exchange(), body.tradingsymbol(), body.side(), body.qty(),
            body.price(), body.stopLoss(), body.takeProfit(), null, body.book(), body.clientOrderId());
    try {
      return ResponseEntity.status(HttpStatus.CREATED).body(paper.openManualOrder(request));
    } catch (DuplicateOrderException duplicate) {
      // Idempotent replay: 200 with the ORIGINAL position DTO. The audit's V2 sketch said 409, but the
      // D8 design authority pins "every non-2xx body is exactly ErrorResponse" — a 409 carrying a
      // PositionDto would break every client that parses non-2xx as the error envelope (and a retrying
      // client wants success semantics for work that already succeeded). 201 stays reserved for a
      // genuinely new fill, so callers can still distinguish create from replay by status.
      return ResponseEntity.ok(duplicate.original());
    }
  }

  /**
   * Close a position at market (or a stated price). Written to the {@code paper_admin_audit} trail as a
   * MANUAL_CLOSE — an owner-initiated close is a discretionary override of the strategy's own exit rule,
   * and without the row it was indistinguishable from a routine engine/bracket close (noticed 2026-07-20
   * while hand-closing a position the outaged 07-17 swing batch never exited). The book is read BEFORE
   * the close via a lightweight local query because the audit is keyed by book and the trade DTO does
   * not carry it. Full position-detail enrichment is deliberately not on this urgent path; the audit
   * write remains after the close.
   */
  @PostMapping("/positions/{id}/close")
  public PaperService.TradeDto close(@PathVariable long id, @RequestBody(required = false) CloseBody body) {
    String book = paper.positionBook(id);
    BigDecimal requestedPrice = body == null ? null : body.price();
    PaperService.TradeDto trade = paper.closePosition(id, requestedPrice);
    adminAudit.recordManualClose(book, id, requestedPrice, trade.realizedPnl());
    return trade;
  }

  /**
   * The full detail + provenance of one position (Phase-2 §6.4/§6.5): opening signal + its family
   * side-channel, the ordered entry/exit legs with a recomputed fee breakdown, brackets, the F9
   * advised-lots / margin snapshot, sub-account and book — the detail-pane + trade-chain payload.
   */
  @GetMapping("/positions/{id}")
  public PaperService.PositionDetail positionDetail(@PathVariable long id) {
    return paper.positionDetail(id);
  }

  /**
   * Manually edit an OPEN position's stop-loss / take-profit (Phase-2, HOLD-tier — the edit changes
   * what the live 15s bracket evaluator will act on). At least one level is required and each supplied
   * level must be positive; a {@code null} field leaves that level unchanged. The service validates the
   * new level against the last REAL tick (a level that would fire immediately 422s) and NEVER settles.
   * The override is written to the {@code paper_admin_audit} trail (previous → new); the evaluator
   * reads the fresh levels on its next pass.
   */
  @PatchMapping("/positions/{id}/brackets")
  public PaperService.PositionDetail editBrackets(@PathVariable long id, @RequestBody BracketBody body) {
    if (body == null || (body.stopLoss() == null && body.takeProfit() == null)) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "at least one of stopLoss / takeProfit is required");
    }
    if (body.stopLoss() != null && body.stopLoss().signum() <= 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "stopLoss must be positive");
    }
    if (body.takeProfit() != null && body.takeProfit().signum() <= 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "takeProfit must be positive");
    }
    PaperService.BracketEdit edit = paper.editBrackets(id, body.stopLoss(), body.takeProfit());
    adminAudit.recordBracketEdit(
        edit.detail().book(), id, edit.previousStopLoss(), edit.previousTakeProfit(),
        edit.detail().stopLoss(), edit.detail().takeProfit());
    return edit.detail();
  }

  /**
   * Wipe a book's paper ledger ({@code book} absent → all books) — guarded by {@code confirm=true}. The
   * destructive action is audited with the deleted position/order counts (audit §7.1/§7.2.5).
   */
  @PostMapping("/reset")
  public ResponseEntity<Void> reset(@RequestBody(required = false) ResetBody body) {
    String book = body == null ? null : body.book();
    PaperService.ResetResult result = paper.reset(book, body != null && body.confirm());
    adminAudit.recordReset(book, result.positionsDeleted(), result.ordersDeleted());
    return ResponseEntity.noContent().build();
  }
}
