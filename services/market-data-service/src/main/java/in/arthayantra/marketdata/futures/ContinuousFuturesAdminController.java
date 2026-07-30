package in.arthayantra.marketdata.futures;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-triggered historical CONT reconstruction (2b-E1). One call rebuilds {@code {root}-FUT-CONT}
 * 1m from the expired + live front-month roster so a backtest signal can read a continuous NIFTY
 * front-future series over a fresh window. Idempotent — safe to re-run, never disturbs the live
 * roller. Map-envelope so it does not enumerate a springdoc schema. Defaults target NIFTY.
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class ContinuousFuturesAdminController {

  private final ContinuousFuturesBackfill backfill;

  /** Wires the historical CONT backfill service. */
  public ContinuousFuturesAdminController(ContinuousFuturesBackfill backfill) {
    this.backfill = backfill;
  }

  /** Rebuilds {@code {root}-FUT-CONT} — {@code {root, contSymbol, contracts}}. */
  @PostMapping("/futures/continuous-backfill")
  public ContinuousBackfillResponse continuousBackfill(
      @RequestParam(defaultValue = "NIFTY") String root,
      @RequestParam(name = "underlyingExchange", defaultValue = "NSE") String underlyingExchange,
      @RequestParam(name = "underlying", defaultValue = "NIFTY 50") String underlyingDisplay) {
    int contracts = backfill.backfill(root, underlyingExchange, underlyingDisplay);
    return new ContinuousBackfillResponse(root, root + "-FUT-CONT", contracts);
  }

  /** Backfill receipt. Multi-key {@code Map.of} before D3 — order NORMALISED, not preserved. */
  public record ContinuousBackfillResponse(String root, String contSymbol, int contracts) {}
}
