package in.arthayantra.strategysignal.paper;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

  /** Open-order body: from a signal (side derived) or a manual entry; optional SL/TP + book. */
  public record OrderBody(
      Long signalId,
      String exchange,
      String tradingsymbol,
      String side,
      Long qty,
      BigDecimal price,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String book) {}

  /** Close body: an explicit price overrides the last tick. */
  public record CloseBody(BigDecimal price) {}

  /** Reset is guarded by an explicit confirm flag; scoped to a book (null → all books). */
  public record ResetBody(boolean confirm, String book) {}

  /** Owner edit of the starting capital for a book. */
  public record AccountBody(BigDecimal startingCapital, String book) {}

  private final PaperService paper;
  private final PaperAccountService account;

  /** Wires the ledger + account services. */
  public PaperController(PaperService paper, PaperAccountService account) {
    this.paper = paper;
    this.account = account;
  }

  /** The account header for a book ({@code book} absent → the aggregate across all books). */
  @GetMapping("/account")
  public PaperAccountService.AccountDto account(@RequestParam(required = false) String book) {
    return account.account(book);
  }

  /** Edit a book's starting capital. */
  @PutMapping("/account")
  public PaperAccountService.AccountDto updateAccount(@RequestBody AccountBody body) {
    if (body.startingCapital() == null || body.startingCapital().signum() < 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "startingCapital must be non-negative");
    }
    if (body.book() == null || body.book().isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "book is required to edit capital");
    }
    account.updateStartingCapital(body.book(), body.startingCapital());
    return account.account(body.book());
  }

  /** Open positions with mark-to-market P&amp;L ({@code book} absent → all books). */
  @GetMapping("/positions")
  public Map<String, Object> positions(@RequestParam(required = false) String book) {
    return Map.of("items", paper.openPositions(book));
  }

  /** The closed-trade ledger (optional {@code book} + {@code symbol}); feeds the chart marks. */
  @GetMapping("/trades")
  public Map<String, Object> trades(
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
    return Map.of(
        "items", paper.trades(book, from, to, tradingsymbol, limit, offset), "limit", limit, "offset", offset);
  }

  /** Daily equity + win rate / expectancy for a book ({@code book} absent → all books). */
  @GetMapping("/pnl")
  public Map<String, Object> pnl(@RequestParam(required = false) String book) {
    return paper.pnl(book);
  }

  /**
   * Simulate an entry (from a signal or manual); the book is on the body or resolved from the signal.
   * Routes through {@code openManualOrder} so the per-book risk governor gates the fill (audit V1) — the
   * signal-taken path stays on {@code openOrder} (already gated at emission).
   */
  @PostMapping("/orders")
  public ResponseEntity<PaperService.PositionDto> order(@RequestBody OrderBody body) {
    if (body.qty() == null || body.qty() <= 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "qty must be a positive integer");
    }
    PaperService.OrderRequest request =
        new PaperService.OrderRequest(
            body.signalId(), body.exchange(), body.tradingsymbol(), body.side(), body.qty(),
            body.price(), body.stopLoss(), body.takeProfit(), null, body.book());
    return ResponseEntity.status(HttpStatus.CREATED).body(paper.openManualOrder(request));
  }

  /** Close a position at market (or a stated price). */
  @PostMapping("/positions/{id}/close")
  public PaperService.TradeDto close(@PathVariable long id, @RequestBody(required = false) CloseBody body) {
    return paper.closePosition(id, body == null ? null : body.price());
  }

  /** Wipe a book's paper ledger ({@code book} absent → all books) — guarded by {@code confirm=true}. */
  @PostMapping("/reset")
  public ResponseEntity<Void> reset(@RequestBody(required = false) ResetBody body) {
    paper.reset(body == null ? null : body.book(), body != null && body.confirm());
    return ResponseEntity.noContent().build();
  }
}
