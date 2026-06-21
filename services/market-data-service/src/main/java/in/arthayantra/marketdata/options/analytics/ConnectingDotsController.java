package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * oipulse "Connecting Dots" feed (§20.7.8): the per-interval multi-factor sentiment matrix for an
 * index. {@code name} is an index (e.g. {@code NIFTY 50}); interval is the shared OiInterval (the page
 * offers 3/5/10/15/30/60); live mode → today IST, history → {@code date}. Empty rows (200) when the
 * session has no futures candles yet.
 */
@RestController
@RequestMapping("/api/v1/market/connecting-dots")
public class ConnectingDotsController {

  private final ConnectingDotsService service;

  public ConnectingDotsController(ConnectingDotsService service) {
    this.service = service;
  }

  @GetMapping
  public ConnectingDotsService.ConnectingDots matrix(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval) {
    OiQuery q = OiQuery.of(mode, name, date, interval, null);
    return service.matrix(q.name(), q.interval(), q.date());
  }
}
