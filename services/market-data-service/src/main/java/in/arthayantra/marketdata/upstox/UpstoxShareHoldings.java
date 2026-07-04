package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * Upstox {@code GET /v2/fundamentals/{isin}/share-holdings} wire DTO (ADR-0002 typed mirror,
 * {@code ignoreUnknown} so an added Upstox field never breaks the parse). Each category
 * ({@code promoters}/{@code fii}/{@code other_dii}/{@code mutual_funds}/{@code retail_and_other})
 * carries a quarterly history of holding percentages; free-float % = 100 − promoter %.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxShareHoldings(String status, List<Category> data) {

  /** One shareholder category with its quarterly history. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Category(String category, List<Point> history) {}

  /** One quarter's holding percentage. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Point(String period, BigDecimal value) {}
}
