package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.upstox.wire.UpstoxOptionChain;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the Wave-U1 wire DTO {@link UpstoxOptionChain} (twin of {@code
 * UpstoxWireContractTest} / {@code KiteWireContractTest}). Every CONSUMED field of a real {@code
 * /v2/option/chain} row — underlying spot, strike, PCR, and per-side LTP/OI/prev_oi/bid/ask/volume —
 * must round-trip into the record, and an UNKNOWN field must never throw, so Upstox's additive wire
 * changes can never crash the live OI capture.
 */
class UpstoxOptionChainWireContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  // One strike of the live /v2/option/chain shape (probed 2026-06-24), both CE and PE populated.
  private static final String BODY =
      """
      {"status":"success","data":[
        {"expiry":"2026-06-30","pcr":1.44,"strike_price":25000.0,
         "underlying_key":"NSE_INDEX|Nifty 50","underlying_spot_price":23845.85,
         "call_options":{"instrument_key":"NSE_FO|58624",
           "market_data":{"ltp":120.5,"volume":345600,"oi":1820075,"close_price":118.0,
             "bid_price":120.0,"bid_qty":75,"ask_price":121.0,"ask_qty":150,"prev_oi":1750000},
           "option_greeks":{"vega":12.3,"theta":-8.1,"gamma":0.0004,"delta":0.42,"iv":13.6,"pop":38.2}},
         "put_options":{"instrument_key":"NSE_FO|58625",
           "market_data":{"ltp":255.75,"volume":290100,"oi":2105500,"close_price":260.0,
             "bid_price":255.0,"bid_qty":75,"ask_price":256.5,"ask_qty":225,"prev_oi":2200000},
           "option_greeks":{"vega":12.1,"theta":-7.9,"gamma":0.0004,"delta":-0.58,"iv":14.1,"pop":61.8}}}
      ]}
      """;

  @Test
  void mirrorsEveryConsumedFieldForBothSides() throws Exception {
    UpstoxOptionChain response = mapper.readValue(BODY, UpstoxOptionChain.class);

    assertThat(response.status()).isEqualTo("success");
    UpstoxOptionChain.Row row = response.data().get(0);
    assertThat(row.expiry()).isEqualTo("2026-06-30");
    assertThat(row.pcr()).isEqualByComparingTo("1.44");
    assertThat(row.strikePrice()).isEqualByComparingTo("25000.0");
    assertThat(row.underlyingKey()).isEqualTo("NSE_INDEX|Nifty 50");
    assertThat(row.underlyingSpotPrice()).isEqualByComparingTo("23845.85");

    UpstoxOptionChain.MarketData ce = row.callOptions().marketData();
    assertThat(row.callOptions().instrumentKey()).isEqualTo("NSE_FO|58624");
    assertThat(ce.ltp()).isEqualByComparingTo("120.5");
    assertThat(ce.volume()).isEqualTo(345600L);
    assertThat(ce.oi()).isEqualTo(1820075L);
    assertThat(ce.prevOi()).isEqualTo(1750000L);
    assertThat(ce.closePrice()).isEqualByComparingTo("118.0");
    assertThat(ce.bidPrice()).isEqualByComparingTo("120.0");
    assertThat(ce.bidQty()).isEqualTo(75L);
    assertThat(ce.askPrice()).isEqualByComparingTo("121.0");
    assertThat(ce.askQty()).isEqualTo(150L);
    // greeks are mirrored for the cross-check but NOT consumed (black76 stays the source)
    assertThat(row.callOptions().optionGreeks().iv()).isEqualByComparingTo("13.6");
    assertThat(row.callOptions().optionGreeks().delta()).isEqualByComparingTo("0.42");

    UpstoxOptionChain.MarketData pe = row.putOptions().marketData();
    assertThat(pe.ltp()).isEqualByComparingTo("255.75");
    assertThat(pe.oi()).isEqualTo(2105500L);
    assertThat(pe.prevOi()).isEqualTo(2200000L);
    assertThat(row.putOptions().optionGreeks().delta()).isEqualByComparingTo("-0.58");
  }

  @Test
  void ignoresUnknownFieldsSoUpstoxAdditionsNeverCrashLive() {
    String withNewFields =
        """
        {"status":"success","brand_new_block":{"x":1},"data":[
          {"expiry":"2026-06-30","strike_price":25000.0,"underlying_spot_price":23845.85,
           "galaxy_brain_field":42,
           "call_options":{"instrument_key":"NSE_FO|58624","future_field":7,
             "market_data":{"ltp":120.5,"oi":1820075,"prev_oi":1750000,"new_md_field":9}}}
        ]}
        """;

    assertThatCode(
            () -> {
              UpstoxOptionChain r = mapper.readValue(withNewFields, UpstoxOptionChain.class);
              UpstoxOptionChain.Row row = r.data().get(0);
              assertThat(row.callOptions().marketData().oi()).isEqualTo(1820075L);
              assertThat(row.callOptions().marketData().prevOi()).isEqualTo(1750000L);
              assertThat(row.putOptions()).isNull(); // absent side stays null, never throws
            })
        .doesNotThrowAnyException();
  }

  @Test
  void toleratesNullDataAndEmptyChain() {
    assertThatCode(
            () -> {
              UpstoxOptionChain nullData =
                  mapper.readValue("{\"status\":\"success\",\"data\":null}", UpstoxOptionChain.class);
              assertThat(nullData.data()).isNull();
              UpstoxOptionChain empty =
                  mapper.readValue("{\"status\":\"success\",\"data\":[]}", UpstoxOptionChain.class);
              assertThat(empty.data()).isEmpty();
            })
        .doesNotThrowAnyException();
  }
}
