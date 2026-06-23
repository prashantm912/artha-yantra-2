package in.arthayantra.marketdata.futures.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** oipulse Open=High / Open=Low: an index's constituents that opened at their day high / low. */
@RestController
@RequestMapping("/api/v1/market/equity")
public class OpenHighController {

  private final OpenHighService service;

  public OpenHighController(OpenHighService service) {
    this.service = service;
  }

  /** The O=H (bearish) + O=L (bullish) constituent setups for an index, live from the future quotes. */
  @GetMapping("/open-high-low")
  public OpenHighService.OpenHigh openHighLow(@RequestParam String name) {
    return service.openHighLow(name);
  }
}
