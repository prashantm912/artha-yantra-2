package in.arthayantra.marketdata.upstox.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Full-mirror wire DTO for Upstox {@code POST /v2/charges/margin} (ADR-0002 anti-corruption
 * pattern) — Upstox computes SPAN + exposure margin server-side for a pre-trade basket, so the
 * platform never loads an {@code .spn} file. {@code @JsonIgnoreProperties(ignoreUnknown=true)} on
 * every record so a field Upstox ADDS can never crash the margin path. Domain-free: the {@code
 * upstox} package maps this to its own {@code MarginQuote} record; consumers never see the wire DTO.
 *
 * <p>Verified live 2026-07-04 on the login-free analytics token: a 1-lot short returned
 * {@code span_margin}/{@code exposure_margin}/{@code total_margin} per leg plus basket
 * {@code required_margin}/{@code final_margin}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxMargin(String status, Data data) {

  /** {@code data}: the per-leg margin breakdown plus the two basket totals. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      List<Margin> margins,
      @JsonProperty("required_margin") BigDecimal requiredMargin,
      @JsonProperty("final_margin") BigDecimal finalMargin) {}

  /** One leg's margin components (all in ₹). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Margin(
      @JsonProperty("span_margin") BigDecimal spanMargin,
      @JsonProperty("exposure_margin") BigDecimal exposureMargin,
      @JsonProperty("equity_margin") BigDecimal equityMargin,
      @JsonProperty("net_buy_premium") BigDecimal netBuyPremium,
      @JsonProperty("additional_margin") BigDecimal additionalMargin,
      @JsonProperty("total_margin") BigDecimal totalMargin,
      @JsonProperty("tender_margin") BigDecimal tenderMargin) {}
}
