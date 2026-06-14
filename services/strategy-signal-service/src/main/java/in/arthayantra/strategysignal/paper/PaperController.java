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

  /** Open-order body: from a signal (side derived) or a manual entry. */
  public record OrderBody(
      Long signalId, String exchange, String tradingsymbol, String side, Long qty, BigDecimal price) {}

  /** Close body: an explicit price overrides the last tick. */
  public record CloseBody(BigDecimal price) {}

  /** Reset is guarded by an explicit confirm flag. */
  public record ResetBody(boolean confirm) {}

  /** Owner edit of the starting capital. */
  public record AccountBody(BigDecimal startingCapital) {}

  private final PaperService paper;
  private final PaperAccountService account;

  /** Wires the ledger + account services. */
  public PaperController(PaperService paper, PaperAccountService account) {
    this.paper = paper;
    this.account = account;
  }

  /** The account header: equity, cash, capital usage by class, day P&amp;L (A12). */
  @GetMapping("/account")
  public PaperAccountService.AccountDto account() {
    return account.account();
  }

  /** Edit the starting capital. */
  @PutMapping("/account")
  public PaperAccountService.AccountDto updateAccount(@RequestBody AccountBody body) {
    if (body.startingCapital() == null || body.startingCapital().signum() < 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "startingCapital must be non-negative");
    }
    account.updateStartingCapital(body.startingCapital());
    return account.account();
  }

  /** Open positions with mark-to-market P&amp;L from the last-tick map. */
  @GetMapping("/positions")
  public Map<String, Object> positions() {
    return Map.of("items", paper.openPositions());
  }

  /** The closed-trade ledger. */
  @GetMapping("/trades")
  public Map<String, Object> trades(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return Map.of("items", paper.trades(from, to, limit, offset), "limit", limit, "offset", offset);
  }

  /** Aggregate daily equity + win rate / expectancy. */
  @GetMapping("/pnl")
  public Map<String, Object> pnl() {
    return paper.pnl();
  }

  /** Simulate an entry (from a signal or manual). */
  @PostMapping("/orders")
  public ResponseEntity<PaperService.PositionDto> order(@RequestBody OrderBody body) {
    if (body.qty() == null || body.qty() <= 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "qty must be a positive integer");
    }
    PaperService.OrderRequest request =
        new PaperService.OrderRequest(
            body.signalId(), body.exchange(), body.tradingsymbol(), body.side(), body.qty(), body.price());
    return ResponseEntity.status(HttpStatus.CREATED).body(paper.openOrder(request));
  }

  /** Close a position at market (or a stated price). */
  @PostMapping("/positions/{id}/close")
  public PaperService.TradeDto close(@PathVariable long id, @RequestBody(required = false) CloseBody body) {
    return paper.closePosition(id, body == null ? null : body.price());
  }

  /** Wipe the paper ledger — guarded by {@code confirm=true}. */
  @PostMapping("/reset")
  public ResponseEntity<Void> reset(@RequestBody(required = false) ResetBody body) {
    paper.reset(body != null && body.confirm());
    return ResponseEntity.noContent().build();
  }
}
