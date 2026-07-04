package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Upstox {@code GET /v2/fundamentals/{isin}/key-ratios} wire DTO (ADR-0002 typed mirror). Ratios are
 * returned as {@code name}/{@code company_value}/{@code sector_value} triples (P/E, P/B, ROE, ...);
 * the consumer matches by name leniently since the exact label strings are Upstox-defined.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxKeyRatios(String status, List<Ratio> data) {

  /** One named ratio with the company value and the peer-sector value. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Ratio(
      String name,
      @JsonProperty("company_value") String companyValue,
      @JsonProperty("sector_value") String sectorValue) {}
}
