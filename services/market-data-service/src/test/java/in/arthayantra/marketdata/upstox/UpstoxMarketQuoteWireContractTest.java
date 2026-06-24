package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.upstox.wire.UpstoxMarketQuote;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the Wave-U2 wire DTO {@link UpstoxMarketQuote} (twin of {@code
 * UpstoxOptionChainWireContractTest} / {@code KiteWireContractTest}). Every CONSUMED field of a real
 * {@code /v2/market-quote/quotes} tick — LTP, day OHLC, volume, OI, net_change, depth bid/ask — must
 * round-trip into the record, and an UNKNOWN field must never throw, so Upstox's additive wire
 * changes can never crash the live quote feed.
 */
class UpstoxMarketQuoteWireContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  // A NIFTY-FUT tick of the live /v2/market-quote/quotes shape (full field set).
  private static final String BODY =
      """
      {"status":"success","data":{
        "NSE_FO:53001":{
          "instrument_token":"NSE_FO|53001","symbol":"NIFTY 26 JUN FUT",
          "last_price":23870.5,"net_change":120.0,"volume":12500000,"average_price":23855.0,
          "oi":11250000,"oi_day_high":11500000,"oi_day_low":11000000,
          "total_buy_quantity":345600,"total_sell_quantity":290100,
          "lower_circuit_limit":21483.0,"upper_circuit_limit":26257.0,
          "last_trade_time":"2026-06-24T15:30:00+05:30",
          "ohlc":{"open":23760.0,"high":23925.0,"low":23740.0,"close":23750.5},
          "depth":{"buy":[{"price":23870.0,"quantity":75,"orders":3}],
                   "sell":[{"price":23871.0,"quantity":150,"orders":5}]}}
      }}
      """;

  @Test
  void mirrorsEveryConsumedField() throws Exception {
    UpstoxMarketQuote response = mapper.readValue(BODY, UpstoxMarketQuote.class);

    assertThat(response.status()).isEqualTo("success");
    UpstoxMarketQuote.Tick tick = response.data().get("NSE_FO:53001");
    assertThat(tick.instrumentToken()).isEqualTo("NSE_FO|53001");
    assertThat(tick.symbol()).isEqualTo("NIFTY 26 JUN FUT");
    assertThat(tick.lastPrice()).isEqualByComparingTo("23870.5");
    assertThat(tick.netChange()).isEqualByComparingTo("120.0");
    assertThat(tick.volume()).isEqualTo(12500000L);
    assertThat(tick.averagePrice()).isEqualByComparingTo("23855.0");
    assertThat(tick.oi()).isEqualTo(11250000L);
    assertThat(tick.oiDayHigh()).isEqualTo(11500000L);
    assertThat(tick.oiDayLow()).isEqualTo(11000000L);
    assertThat(tick.totalBuyQuantity()).isEqualTo(345600L);
    assertThat(tick.totalSellQuantity()).isEqualTo(290100L);
    assertThat(tick.lowerCircuitLimit()).isEqualByComparingTo("21483.0");
    assertThat(tick.upperCircuitLimit()).isEqualByComparingTo("26257.0");
    assertThat(tick.lastTradeTime()).isEqualTo("2026-06-24T15:30:00+05:30");

    assertThat(tick.ohlc().open()).isEqualByComparingTo("23760.0");
    assertThat(tick.ohlc().high()).isEqualByComparingTo("23925.0");
    assertThat(tick.ohlc().low()).isEqualByComparingTo("23740.0");
    assertThat(tick.ohlc().close()).isEqualByComparingTo("23750.5");

    assertThat(tick.depth().buy().get(0).price()).isEqualByComparingTo("23870.0");
    assertThat(tick.depth().buy().get(0).quantity()).isEqualTo(75L);
    assertThat(tick.depth().buy().get(0).orders()).isEqualTo(3L);
    assertThat(tick.depth().sell().get(0).price()).isEqualByComparingTo("23871.0");
  }

  @Test
  void ignoresUnknownFieldsSoUpstoxAdditionsNeverCrashLive() {
    String withNewFields =
        """
        {"status":"success","brand_new_block":{"x":1},"data":{
          "NSE_INDEX:Nifty 50":{"last_price":23845.85,"galaxy_brain_field":42,
            "ohlc":{"open":23733.45,"close":23733.45,"new_ohlc_field":9},
            "depth":{"buy":[{"price":1.0,"future_level_field":7}],"sell":[]}}
        }}
        """;

    assertThatCode(
            () -> {
              UpstoxMarketQuote r = mapper.readValue(withNewFields, UpstoxMarketQuote.class);
              UpstoxMarketQuote.Tick t = r.data().get("NSE_INDEX:Nifty 50");
              assertThat(t.lastPrice()).isEqualByComparingTo("23845.85");
              assertThat(t.ohlc().open()).isEqualByComparingTo("23733.45");
              assertThat(t.oi()).isNull(); // absent field stays null, never throws
            })
        .doesNotThrowAnyException();
  }

  @Test
  void toleratesNullDataAndEmptyMap() {
    assertThatCode(
            () -> {
              UpstoxMarketQuote nullData =
                  mapper.readValue("{\"status\":\"success\",\"data\":null}", UpstoxMarketQuote.class);
              assertThat(nullData.data()).isNull();
              UpstoxMarketQuote empty =
                  mapper.readValue("{\"status\":\"success\",\"data\":{}}", UpstoxMarketQuote.class);
              assertThat(empty.data()).isEmpty();
            })
        .doesNotThrowAnyException();
  }
}
