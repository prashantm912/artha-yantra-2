package in.arthayantra.marketdata.nse.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Equity EOD analytics from the NSE bhavcopy (oipulse Equity section). */
@RestController
@RequestMapping("/api/v1/market/equity")
public class EquityController {

  private final EquityDeliveryService delivery;

  public EquityController(EquityDeliveryService delivery) {
    this.delivery = delivery;
  }

  /** One stock's daily delivery series over the most recent {@code days} sessions (default 15). */
  @GetMapping("/delivery")
  public EquityDeliveryService.Delivery delivery(
      @RequestParam String symbol, @RequestParam(defaultValue = "15") int days) {
    return delivery.delivery(symbol, days);
  }
}
