package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the Upstox Market-Information wire DTO {@link UpstoxMarketActivity} (twin of
 * {@code OpenAlgoWireContractTest} / {@code KiteWireContractTest}, ADR-0002 U7). Every documented
 * field of a FULL {@code get-fii}/{@code get-dii} fixture must round-trip into the record — a dropped
 * or mis-annotated field surfaces here as a null/zero assertion failure rather than being silently
 * lost in production — and an UNKNOWN field must never throw, so Upstox's additive wire changes can
 * never crash the FII/DII + derivative capture. Off the critical path: this guards the wire SHAPE
 * at build time; a live daily probe (a scheduled canary) is a separate, optional follow-up.
 */
class UpstoxWireContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void cashSegmentMirrorsEveryDocumentedField() throws Exception {
    // get-dii / get-fii NSE_EQ|CASH shape: only buy/sell amounts are populated; the rest are zero.
    String body =
        """
        {"status":"success","data":{"NSE_EQ|CASH":[
          {"time_stamp":1746633600000,"buy_amount":8523456789.0,"sell_amount":7234567890.5,
           "buy_contracts":0,"sell_contracts":0,"oi_contracts":0,"oi_amount":0.0,
           "total_long_contracts":0,"total_short_contracts":0,
           "total_call_long_contracts":0,"total_put_long_contracts":0,
           "total_call_short_contracts":0,"total_put_short_contracts":0}]}}
        """;

    UpstoxMarketActivity response = mapper.readValue(body, UpstoxMarketActivity.class);
    UpstoxMarketActivity.Activity a = response.data().get("NSE_EQ|CASH").get(0);

    assertThat(response.status()).isEqualTo("success");
    assertThat(a.timeStamp()).isEqualTo(1746633600000L);
    assertThat(a.buyAmount()).isEqualByComparingTo("8523456789.0");
    assertThat(a.sellAmount()).isEqualByComparingTo("7234567890.5");
    assertThat(a.buyContracts()).isZero();
    assertThat(a.sellContracts()).isZero();
    assertThat(a.oiContracts()).isZero();
    assertThat(a.oiAmount()).isEqualByComparingTo("0.0");
    assertThat(a.totalLongContracts()).isZero();
    assertThat(a.totalShortContracts()).isZero();
    assertThat(a.totalCallLongContracts()).isZero();
    assertThat(a.totalPutLongContracts()).isZero();
    assertThat(a.totalCallShortContracts()).isZero();
    assertThat(a.totalPutShortContracts()).isZero();
  }

  @Test
  void fnoSegmentMirrorsEveryDocumentedField() throws Exception {
    // get-fii NSE_FO|INDEX_OPTIONS shape: full position breakdown (the U6 derivative-stats source).
    String body =
        """
        {"status":"success","data":{"NSE_FO|INDEX_OPTIONS":[
          {"time_stamp":1777487400000,"buy_amount":797967.36,"sell_amount":794438.53,
           "buy_contracts":5094129,"sell_contracts":5072195,"oi_contracts":1995796,
           "oi_amount":313760.14,"total_long_contracts":0,"total_short_contracts":0,
           "total_call_long_contracts":351772,"total_put_long_contracts":715640,
           "total_call_short_contracts":572110,"total_put_short_contracts":356275}]}}
        """;

    UpstoxMarketActivity response = mapper.readValue(body, UpstoxMarketActivity.class);
    UpstoxMarketActivity.Activity a = response.data().get("NSE_FO|INDEX_OPTIONS").get(0);

    assertThat(a.timeStamp()).isEqualTo(1777487400000L);
    assertThat(a.buyAmount()).isEqualByComparingTo("797967.36");
    assertThat(a.sellAmount()).isEqualByComparingTo("794438.53");
    assertThat(a.buyContracts()).isEqualTo(5094129L);
    assertThat(a.sellContracts()).isEqualTo(5072195L);
    assertThat(a.oiContracts()).isEqualTo(1995796L);
    assertThat(a.oiAmount()).isEqualByComparingTo("313760.14");
    assertThat(a.totalCallLongContracts()).isEqualTo(351772L);
    assertThat(a.totalPutLongContracts()).isEqualTo(715640L);
    assertThat(a.totalCallShortContracts()).isEqualTo(572110L);
    assertThat(a.totalPutShortContracts()).isEqualTo(356275L);
  }

  @Test
  void multipleSegmentsKeyByDataType() throws Exception {
    // The load-bearing shape U6 depends on: data is a map keyed by the requested data_type segment.
    String body =
        """
        {"status":"success","data":{
          "NSE_FO|INDEX_FUTURES":[{"time_stamp":1777487400000,"buy_amount":1.0,"sell_amount":2.0}],
          "NSE_FO|STOCK_FUTURES":[{"time_stamp":1777487400000,"buy_amount":3.0,"sell_amount":4.0}]}}
        """;

    UpstoxMarketActivity response = mapper.readValue(body, UpstoxMarketActivity.class);

    assertThat(response.data())
        .containsKeys("NSE_FO|INDEX_FUTURES", "NSE_FO|STOCK_FUTURES");
    assertThat(response.data().get("NSE_FO|INDEX_FUTURES").get(0).buyAmount())
        .isEqualByComparingTo("1.0");
  }

  @Test
  void optionAnalyticsMirrorsMaxPainAndPcrFields() throws Exception {
    // get-max-pain / get-pcr share this shape (the irrelevant value field is null per endpoint).
    String body =
        """
        {"status":"success","data":{"instrument_key":"NSE_INDEX|Nifty 50","expiry_date":"23-06-2026",
         "max_pain":24100.0,"pcr":0.8762,"spot_closing_price":24087.2,
         "insights":[{"max_pain":24100.0,"pcr":0.7832,"spot_price":24075.1,"time":"09:15"}]}}
        """;

    UpstoxOptionAnalytics r = mapper.readValue(body, UpstoxOptionAnalytics.class);

    assertThat(r.status()).isEqualTo("success");
    assertThat(r.data().maxPain()).isEqualByComparingTo("24100.0");
    assertThat(r.data().pcr()).isEqualByComparingTo("0.8762");
    assertThat(r.data().spotClosingPrice()).isEqualByComparingTo("24087.2");
    UpstoxOptionAnalytics.Insight i = r.data().insights().get(0);
    assertThat(i.maxPain()).isEqualByComparingTo("24100.0");
    assertThat(i.pcr()).isEqualByComparingTo("0.7832");
    assertThat(i.spotPrice()).isEqualByComparingTo("24075.1");
    assertThat(i.time()).isEqualTo("09:15");
  }

  @Test
  void optionAnalyticsTolueratesNullDataAndUnknownFields() {
    assertThatCode(
            () -> {
              UpstoxOptionAnalytics r =
                  mapper.readValue(
                      "{\"status\":\"success\",\"new_field\":1,\"data\":null}",
                      UpstoxOptionAnalytics.class);
              assertThat(r.data()).isNull();
            })
        .doesNotThrowAnyException();
  }

  @Test
  void ignoresUnknownFieldsSoUpstoxAdditionsNeverCrashLive() {
    String withNewFields =
        """
        {"status":"success","brand_new_block":{"x":1},"data":{"NSE_EQ|CASH":[
          {"time_stamp":1746633600000,"buy_amount":100.0,"galaxy_brain_field":42}]}}
        """;

    assertThatCode(
            () -> {
              UpstoxMarketActivity r = mapper.readValue(withNewFields, UpstoxMarketActivity.class);
              List<UpstoxMarketActivity.Activity> cash = r.data().get("NSE_EQ|CASH");
              assertThat(cash.get(0).buyAmount()).isEqualByComparingTo("100.0");
            })
        .doesNotThrowAnyException();
  }
}
