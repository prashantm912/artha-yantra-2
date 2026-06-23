package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Wire mirror of Upstox {@code GET /v2/expired-instruments/option/contract} and
 * {@code .../future/contract} — the per-contract roster for one (underlying, expiry). Each row
 * carries the {@code instrument_key} (the expired_instrument_key fed back into the historical-candle
 * call) plus the contract spec (strike, lot, tick, type). {@code @JsonIgnoreProperties} so a field
 * Upstox adds never crashes the feed (the kite/wire convention).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxExpiredContract(String status, List<Contract> data) {

  /** One expired option/future contract. {@code strikePrice}/{@code instrumentType} are null for futures. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Contract(
      String name,
      String segment,
      String exchange,
      String expiry,
      Boolean weekly,
      @JsonProperty("instrument_key") String instrumentKey,
      @JsonProperty("exchange_token") String exchangeToken,
      @JsonProperty("trading_symbol") String tradingSymbol,
      @JsonProperty("tick_size") BigDecimal tickSize,
      @JsonProperty("lot_size") Integer lotSize,
      @JsonProperty("instrument_type") String instrumentType,
      @JsonProperty("freeze_quantity") BigDecimal freezeQuantity,
      @JsonProperty("underlying_key") String underlyingKey,
      @JsonProperty("underlying_type") String underlyingType,
      @JsonProperty("underlying_symbol") String underlyingSymbol,
      @JsonProperty("strike_price") BigDecimal strikePrice,
      @JsonProperty("minimum_lot") Integer minimumLot) {}
}
