package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Upstox {@code GET /v2/fundamentals/{isin}/income-statement} wire DTO (ADR-0002 typed mirror).
 * {@code units_in} is "crore"; {@code income_statement} carries the headline lines
 * ({@code revenue}/{@code operating_profit}/{@code net_profit}) each with a period history
 * (latest-first or -last is Upstox-defined — the consumer sorts by period).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxIncomeStatement(String status, Data data) {

  /** The statement block: units + the headline category lines. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      @JsonProperty("units_in") String unitsIn,
      @JsonProperty("time_period") String timePeriod,
      @JsonProperty("income_statement") List<Line> incomeStatement) {}

  /** One headline line (revenue / operating_profit / net_profit) with its period history. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Line(String category, List<Point> history) {}

  /** One period's value (in crore). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Point(BigDecimal value, String period, String change) {}
}
