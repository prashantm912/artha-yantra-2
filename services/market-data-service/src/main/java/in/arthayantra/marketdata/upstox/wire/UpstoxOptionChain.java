package in.arthayantra.marketdata.upstox.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Wire mirror of Upstox {@code GET /v2/option/chain?instrument_key=&lt;key&gt;&amp;expiry_date=yyyy-MM-dd}
 * — the WHOLE live option chain for one (underlying, expiry) in a single call, returned on the 1-yr
 * analytics token (proven live 2026-06-24). Each {@code data} row is one strike carrying the
 * underlying spot + PCR + a CE and PE leg, each leg with its {@code market_data} (LTP, OI, prev_oi,
 * volume, bid/ask) and Upstox-computed {@code option_greeks}. Every documented field is mirrored even
 * when unused (the {@code kite/wire} convention); {@code @JsonIgnoreProperties} so a field Upstox adds
 * never crashes the live capture.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxOptionChain(String status, List<Row> data) {

  /** One strike: the underlying context (spot/PCR/expiry) plus the CE and PE legs. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Row(
      String expiry,
      BigDecimal pcr,
      @JsonProperty("strike_price") BigDecimal strikePrice,
      @JsonProperty("underlying_key") String underlyingKey,
      @JsonProperty("underlying_spot_price") BigDecimal underlyingSpotPrice,
      @JsonProperty("call_options") Option callOptions,
      @JsonProperty("put_options") Option putOptions) {}

  /** One side of a strike: the contract's instrument key plus its market data and greeks. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Option(
      @JsonProperty("instrument_key") String instrumentKey,
      @JsonProperty("market_data") MarketData marketData,
      @JsonProperty("option_greeks") OptionGreeks optionGreeks) {}

  /** The live tradable snapshot for one contract — OI + prev_oi (so oi_change = oi − prev_oi). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MarketData(
      BigDecimal ltp,
      Long volume,
      Long oi,
      @JsonProperty("close_price") BigDecimal closePrice,
      @JsonProperty("bid_price") BigDecimal bidPrice,
      @JsonProperty("bid_qty") Long bidQty,
      @JsonProperty("ask_price") BigDecimal askPrice,
      @JsonProperty("ask_qty") Long askQty,
      @JsonProperty("prev_oi") Long prevOi) {}

  /** Upstox-computed greeks/IV — mirrored for the cross-check; NOT the source (black76 stays). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OptionGreeks(
      BigDecimal vega,
      BigDecimal theta,
      BigDecimal gamma,
      BigDecimal delta,
      BigDecimal iv,
      BigDecimal pop) {}
}
