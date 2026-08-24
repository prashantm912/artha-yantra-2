package in.arthayantra.marketdata.canary;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The canary's read surface (roadmap F4): evaluates fresh on every GET — the dashboard strip polls
 * it and the 09:42 live-health agent reads it first, deep-diving only on non-GREEN.
 */
@RestController
@RequestMapping("/api/v1/market/health")
public class DataHealthController {

  private final DataHealthCanary canary;
  private final BhavcopyCloseCanary bhavcopyClose;

  /** Wires the data-plane + bhavcopy-close (audit V8) canaries. */
  public DataHealthController(DataHealthCanary canary, BhavcopyCloseCanary bhavcopyClose) {
    this.canary = canary;
    this.bhavcopyClose = bhavcopyClose;
  }

  /** Current data-plane health: tick/bar divergence + capture freshness. */
  @GetMapping("/data")
  public CanaryReport data() {
    return canary.evaluate();
  }

  /**
   * Bhavcopy-close vs Kite-1d-close divergence (audit §8 V8). {@code date} defaults to the latest
   * bhavcopy trade date; 422 DATA_GAP semantics are still avoided — a thin or empty comparison set
   * answers 200 with a YELLOW report rather than an error.
   *
   * <p>⚠️ An empty compared set used to read GREEN/0. It now reads YELLOW against
   * {@code minCompared}: "nothing to compare" is not "the feeds agree", and reporting it as a clean
   * run is what let a collapse from ~165 comparable symbols to 14 pass unnoticed for two sessions.
   */
  @GetMapping("/bhavcopy-close")
  public BhavcopyCloseCanary.BhavcopyCloseReport bhavcopyClose(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    LocalDate target = date != null ? date : bhavcopyClose.latestTradeDate();
    return bhavcopyClose.evaluate(target);
  }
}
