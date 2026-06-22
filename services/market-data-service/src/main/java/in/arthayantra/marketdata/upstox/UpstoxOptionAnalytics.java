package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Full-mirror wire DTO for the Upstox Market-Information option-analytics endpoints ({@code GET
 * /v2/market/max-pain}, {@code /v2/market/pcr}) — same anti-corruption pattern as {@code kite/wire}.
 * Both endpoints share this shape: a {@code data} block carrying the day's final value ({@code
 * max_pain} or {@code pcr}) + {@code spot_closing_price} + a per-bucket {@code insights} array. The
 * irrelevant value field is null per endpoint ({@code pcr} on a max-pain response, etc.). {@code
 * data} is null when Upstox has no data for the requested expiry/date (a {@code 200}). Every
 * documented field is mirrored and {@code ignoreUnknown=true}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxOptionAnalytics(@JsonProperty("status") String status, @JsonProperty("data") Data data) {

  /** The analytics block for one {@code (instrument_key, expiry, date)}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      @JsonProperty("instrument_key") String instrumentKey,
      @JsonProperty("expiry_date") String expiryDate,
      @JsonProperty("max_pain") BigDecimal maxPain,
      @JsonProperty("pcr") BigDecimal pcr,
      @JsonProperty("spot_closing_price") BigDecimal spotClosingPrice,
      @JsonProperty("insights") List<Insight> insights) {}

  /** One intraday bucket: the value at {@code time} (HH:mm) plus the bucket's spot. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Insight(
      @JsonProperty("max_pain") BigDecimal maxPain,
      @JsonProperty("pcr") BigDecimal pcr,
      @JsonProperty("spot_price") BigDecimal spotPrice,
      @JsonProperty("time") String time) {}
}
