package in.arthayantra.marketdata.upstox.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Wire mirror of Upstox {@code GET /v2/market-quote/quotes?instrument_key=&lt;csv&gt;} — the FULL
 * market quote for a batch of instruments in a single call, returned on the 1-yr analytics token
 * (Wave U2; the same login-free side-channel as {@link UpstoxOptionChain}). The {@code data} block is
 * a MAP keyed by the response key (e.g. {@code "NSE_INDEX:Nifty 50"} — Upstox swaps the request
 * {@code |} for a {@code :} in the key), each value a {@link Tick} carrying LTP + day OHLC + volume +
 * OI + net_change + depth. Every documented field is mirrored even when unused (the {@code kite/wire}
 * convention); {@code @JsonIgnoreProperties} so a field Upstox adds never crashes the live quote feed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxMarketQuote(String status, Map<String, Tick> data) {

  /** One instrument's full quote — the live tradable snapshot the quote seam consumes. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Tick(
      Ohlc ohlc,
      Depth depth,
      @JsonProperty("last_price") BigDecimal lastPrice,
      @JsonProperty("instrument_token") String instrumentToken,
      String symbol,
      Long volume,
      @JsonProperty("average_price") BigDecimal averagePrice,
      Long oi,
      @JsonProperty("net_change") BigDecimal netChange,
      @JsonProperty("total_buy_quantity") Long totalBuyQuantity,
      @JsonProperty("total_sell_quantity") Long totalSellQuantity,
      @JsonProperty("lower_circuit_limit") BigDecimal lowerCircuitLimit,
      @JsonProperty("upper_circuit_limit") BigDecimal upperCircuitLimit,
      @JsonProperty("last_trade_time") String lastTradeTime,
      @JsonProperty("oi_day_high") Long oiDayHigh,
      @JsonProperty("oi_day_low") Long oiDayLow) {}

  /** Day range from the venue quote; {@code close} is the PREVIOUS day's close (Kite convention). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Ohlc(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}

  /** Top-of-book buy/sell ladders — best bid/ask is the first level of each side. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Depth(List<Level> buy, List<Level> sell) {}

  /** One depth ladder rung. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Level(
      BigDecimal price,
      Long quantity,
      @JsonProperty("orders") Long orders) {}
}
