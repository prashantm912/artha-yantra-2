package in.arthayantra.marketdata.options;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The on-demand stock-chain OI warm (audit §9.3, Wave 5): POST starts an async single-flight fetch
 * of one (stock, expiry, day)'s 1-minute Upstox bars into {@code options_chain_snapshots}; GET
 * polls its progress. Once warmed, every existing OI reader serves the stock unchanged. 503 when
 * the Upstox analytics stack is not wired (mock profile / token off); 422 when the stock lists no
 * option legs for the expiry.
 */
@RestController
@RequestMapping("/api/v1/market/options/stock-chain")
public class StockChainWarmController {

  private final StockChainWarmService service;
  private final Clock clock;

  public StockChainWarmController(StockChainWarmService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  /** Starts (or re-tops-up) the warm; single-flight per (name, expiry, day). */
  @PostMapping("/warm")
  public StockChainWarmService.WarmStatus warm(
      @RequestParam String name,
      @RequestParam(required = false) String expiry,
      @RequestParam(required = false) String date) {
    Resolved r = resolve(name, expiry, date);
    return service.start(name, r.expiry(), r.day());
  }

  /** The warm's progress (RUNNING fetched/total) or the persisted state (IDLE/DONE + row count). */
  @GetMapping("/warm")
  public StockChainWarmService.WarmStatus status(
      @RequestParam String name,
      @RequestParam(required = false) String expiry,
      @RequestParam(required = false) String date) {
    Resolved r = resolve(name, expiry, date);
    return service.status(name, r.expiry(), r.day());
  }

  private record Resolved(LocalDate expiry, LocalDate day) {}

  private Resolved resolve(String name, String expiry, String date) {
    if (!service.available()) {
      throw new ApiException(
          503, ErrorCodes.UPSTREAM_UNAVAILABLE, "Upstox analytics is not configured on this stack");
    }
    LocalDate exp = expiry == null || expiry.isBlank() ? service.frontExpiry(name) : LocalDate.parse(expiry);
    if (exp == null) {
      throw new ApiException(
          422, ErrorCodes.DATA_GAP, "no listed option expiry for '" + name + "'");
    }
    LocalDate day =
        date == null || date.isBlank()
            ? clock.instant().atZone(Ist.ZONE).toLocalDate()
            : LocalDate.parse(date);
    return new Resolved(exp, day);
  }
}
