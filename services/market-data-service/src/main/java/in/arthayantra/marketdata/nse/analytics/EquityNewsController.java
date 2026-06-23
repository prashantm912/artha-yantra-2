package in.arthayantra.marketdata.nse.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Per-stock news / announcements (Upstox /v2/news, analytics-token side-channel). */
@RestController
@RequestMapping("/api/v1/market/equity")
public class EquityNewsController {

  private final EquityNewsService service;

  public EquityNewsController(EquityNewsService service) {
    this.service = service;
  }

  /** Recent news for a stock symbol; {@code available=false} when the Upstox source isn't live. */
  @GetMapping("/news")
  public EquityNewsService.News news(@RequestParam String symbol) {
    return service.news(symbol);
  }
}
