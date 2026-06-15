package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * One instrument's full market quote (Kite v3 {@code /quote}). Every documented field is mirrored;
 * {@code oi}/{@code oi_day_high}/{@code oi_day_low} are documented {@code float64}, so they are
 * decimals here and narrowed to {@code long} where the domain needs whole open interest.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteQuote(
    @JsonProperty("instrument_token") Long instrumentToken,
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("last_trade_time") String lastTradeTime,
    @JsonProperty("last_price") BigDecimal lastPrice,
    @JsonProperty("last_quantity") Long lastQuantity,
    @JsonProperty("buy_quantity") Long buyQuantity,
    @JsonProperty("sell_quantity") Long sellQuantity,
    @JsonProperty("volume") Long volume,
    @JsonProperty("average_price") BigDecimal averagePrice,
    @JsonProperty("oi") BigDecimal oi,
    @JsonProperty("oi_day_high") BigDecimal oiDayHigh,
    @JsonProperty("oi_day_low") BigDecimal oiDayLow,
    @JsonProperty("net_change") BigDecimal netChange,
    @JsonProperty("lower_circuit_limit") BigDecimal lowerCircuitLimit,
    @JsonProperty("upper_circuit_limit") BigDecimal upperCircuitLimit,
    @JsonProperty("ohlc") Ohlc ohlc,
    @JsonProperty("depth") Depth depth) {

  /** The day's open/high/low/close block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Ohlc(
      @JsonProperty("open") BigDecimal open,
      @JsonProperty("high") BigDecimal high,
      @JsonProperty("low") BigDecimal low,
      @JsonProperty("close") BigDecimal close) {}

  /** Up to five levels of bid/ask market depth. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Depth(
      @JsonProperty("buy") List<Level> buy, @JsonProperty("sell") List<Level> sell) {

    /** One depth level. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Level(
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("quantity") Long quantity,
        @JsonProperty("orders") Long orders) {}
  }
}
