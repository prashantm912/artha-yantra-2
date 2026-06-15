package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Market breadth for a date: advance/decline + delivery%-leaders from the EQ bhavcopy. */
@RestController
@RequestMapping("/api/v1/market/breadth")
public class BreadthController {

  private final BreadthService service;

  public BreadthController(BreadthService service) {
    this.service = service;
  }

  @GetMapping
  public BreadthService.Breadth breadth(@RequestParam String date) {
    return service.breadth(parseDate(date));
  }

  private static LocalDate parseDate(String raw) {
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "date must be ISO yyyy-MM-dd");
    }
  }
}
