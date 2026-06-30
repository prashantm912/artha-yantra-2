package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Index-level NSE EOD reads: FII/DII cash (/cash), participant-wise OI (/participant-oi), a derived
 * FII index-futures long/short ratio (/long-short) and FII net activity per F&amp;O derivative
 * segment (/derivative-stats, ADR-0002 U6). Date-ranged (from required, to=from).
 */
@RestController
@RequestMapping("/api/v1/market/fii-dii")
public class FiiDiiController {

  private final NseEodReader reader;
  private final ParticipantBiasService biasService;

  public FiiDiiController(NseEodReader reader, ParticipantBiasService biasService) {
    this.reader = reader;
    this.biasService = biasService;
  }

  public record LongShortRow(LocalDate tradeDate, long fiiLong, long fiiShort, BigDecimal ratio) {}

  @GetMapping("/cash")
  public Map<String, Object> cash(
      @RequestParam String from, @RequestParam(required = false) String to) {
    LocalDate f = parseDate(from);
    LocalDate t = to == null || to.isBlank() ? f : parseDate(to);
    return Map.of("items", reader.fiiDii(f, t));
  }

  @GetMapping("/derivative-stats")
  public Map<String, Object> derivativeStats(
      @RequestParam String from, @RequestParam(required = false) String to) {
    LocalDate f = parseDate(from);
    LocalDate t = to == null || to.isBlank() ? f : parseDate(to);
    return Map.of("items", reader.fiiDerivativeStats(f, t));
  }

  @GetMapping("/participant-oi")
  public Map<String, Object> participantOi(
      @RequestParam String from, @RequestParam(required = false) String to) {
    LocalDate f = parseDate(from);
    LocalDate t = to == null || to.isBlank() ? f : parseDate(to);
    return Map.of("items", reader.participantOi(f, t));
  }

  @GetMapping("/long-short")
  public Map<String, Object> longShort(
      @RequestParam String from, @RequestParam(required = false) String to) {
    LocalDate f = parseDate(from);
    LocalDate t = to == null || to.isBlank() ? f : parseDate(to);
    List<LongShortRow> items =
        reader.participantOi(f, t).stream()
            .filter(r -> "FII".equalsIgnoreCase(r.clientType()))
            .map(
                r -> {
                  BigDecimal ratio =
                      r.futureIndexShort() == 0
                          ? null
                          : BigDecimal.valueOf(r.futureIndexLong())
                              .divide(
                                  BigDecimal.valueOf(r.futureIndexShort()), 4, RoundingMode.HALF_UP);
                  return new LongShortRow(
                      r.tradeDate(), r.futureIndexLong(), r.futureIndexShort(), ratio);
                })
            .toList();
    return Map.of("items", items);
  }

  /**
   * E3 fii-dii-bias (§3.3): the combined FII EOD participant bias for a date — the LB/SC/LU/SB futures
   * classification + the option-leg seller read collapsed into a single {@code biasSign} the scalper
   * {@code fii-dii-gate} consumes. {@code date} defaults to today (the caller passes the prior completed
   * session's eodDate for a backtest/intraday read).
   */
  @GetMapping("/bias")
  public ParticipantBiasService.Bias bias(@RequestParam String date) {
    return biasService.bias(parseDate(date));
  }

  private static LocalDate parseDate(String raw) {
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "date must be ISO yyyy-MM-dd");
    }
  }
}
