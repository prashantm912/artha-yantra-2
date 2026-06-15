package in.arthayantra.marketdata.kite.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the Kite wire DTOs (owner directive 2026-06-15): every documented field of a
 * FULL fixture must round-trip into its record — a dropped or mis-annotated field surfaces here as
 * a null assertion failure rather than being silently lost in production — and an UNKNOWN field
 * must never throw, so Kite's additive wire changes can never crash the live feed. Companion to the
 * daily {@link in.arthayantra.marketdata.kite.canary.ContractCanary}, which catches drift live.
 */
class KiteWireContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void quoteMirrorsEveryDocumentedField() throws Exception {
    String body =
        """
        {"status":"success","data":{"NSE:NIFTY 50":{
          "instrument_token":256265,"timestamp":"2026-06-15 15:30:00",
          "last_trade_time":"2026-06-15 15:29:59","last_price":24137.55,"last_quantity":50,
          "buy_quantity":1200,"sell_quantity":1500,"volume":987654,"average_price":24130.10,
          "oi":12345678,"oi_day_high":12500000,"oi_day_low":12000000,"net_change":42.5,
          "lower_circuit_limit":21000.0,"upper_circuit_limit":27000.0,
          "ohlc":{"open":24100.0,"high":24200.0,"low":24050.0,"close":24095.0},
          "depth":{"buy":[{"price":24137.5,"quantity":10,"orders":2}],
                   "sell":[{"price":24138.0,"quantity":12,"orders":3}]}}}}
        """;

    KiteQuoteResponse response = mapper.readValue(body, KiteQuoteResponse.class);
    KiteQuote q = response.data().get("NSE:NIFTY 50");

    assertThat(q.instrumentToken()).isEqualTo(256265L);
    assertThat(q.timestamp()).isNotNull();
    assertThat(q.lastTradeTime()).isNotNull();
    assertThat(q.lastPrice()).isNotNull();
    assertThat(q.lastQuantity()).isNotNull();
    assertThat(q.buyQuantity()).isNotNull();
    assertThat(q.sellQuantity()).isNotNull();
    assertThat(q.volume()).isNotNull();
    assertThat(q.averagePrice()).isNotNull();
    assertThat(q.oi()).isNotNull();
    assertThat(q.oiDayHigh()).isNotNull();
    assertThat(q.oiDayLow()).isNotNull();
    assertThat(q.netChange()).isNotNull();
    assertThat(q.lowerCircuitLimit()).isNotNull();
    assertThat(q.upperCircuitLimit()).isNotNull();
    assertThat(q.ohlc().open()).isNotNull();
    assertThat(q.ohlc().high()).isNotNull();
    assertThat(q.ohlc().low()).isNotNull();
    assertThat(q.ohlc().close()).isNotNull();
    KiteQuote.Depth.Level bid = q.depth().buy().get(0);
    assertThat(bid.price()).isNotNull();
    assertThat(bid.quantity()).isNotNull();
    assertThat(bid.orders()).isNotNull();
    assertThat(q.depth().sell().get(0).price()).isNotNull();
  }

  @Test
  void quoteIgnoresUnknownFieldsSoKiteAdditionsNeverCrashLive() {
    String withNewFields =
        """
        {"status":"success","brand_new_block":{"x":1},"data":{"NSE:NIFTY 50":{
          "last_price":24137.55,"galaxy_brain_field":42,
          "ohlc":{"open":1,"high":1,"low":1,"close":1,"some_new_ohlc_key":9}}}}
        """;

    assertThatCode(
            () -> {
              KiteQuoteResponse r = mapper.readValue(withNewFields, KiteQuoteResponse.class);
              assertThat(r.data().get("NSE:NIFTY 50").lastPrice()).isNotNull();
            })
        .doesNotThrowAnyException();
  }

  @Test
  void sessionMirrorsEveryDocumentedField() throws Exception {
    String body =
        """
        {"status":"success","data":{
          "user_id":"AB1234","user_name":"Owner Name","user_shortname":"Owner",
          "email":"o@x.in","user_type":"individual","broker":"ZERODHA",
          "exchanges":["NSE","BSE","NFO","BFO"],"products":["CNC","MIS","NRML"],
          "order_types":["MARKET","LIMIT"],"avatar_url":"https://x/y.png",
          "access_token":"at","public_token":"pt","refresh_token":"rt","enctoken":"et",
          "api_key":"ak","login_time":"2026-06-15 08:30:00","silo":"a",
          "meta":{"demat_consent":"consent"}}}
        """;

    KiteSession s = mapper.readValue(body, KiteSessionResponse.class).data();

    assertThat(s.userId()).isNotNull();
    assertThat(s.userName()).isNotNull();
    assertThat(s.userShortname()).isNotNull();
    assertThat(s.email()).isNotNull();
    assertThat(s.userType()).isNotNull();
    assertThat(s.broker()).isNotNull();
    assertThat(s.exchanges()).contains("BSE");
    assertThat(s.products()).isNotEmpty();
    assertThat(s.orderTypes()).isNotEmpty();
    assertThat(s.avatarUrl()).isNotNull();
    assertThat(s.accessToken()).isNotNull();
    assertThat(s.publicToken()).isNotNull();
    assertThat(s.refreshToken()).isNotNull();
    assertThat(s.enctoken()).isNotNull();
    assertThat(s.apiKey()).isNotNull();
    assertThat(s.loginTime()).isNotNull();
    assertThat(s.silo()).isNotNull();
    assertThat(s.meta().dematConsent()).isNotNull();
  }

  @Test
  void profileMirrorsEveryDocumentedField() throws Exception {
    String body =
        """
        {"status":"success","data":{
          "user_id":"AB1234","user_name":"Owner Name","user_shortname":"Owner",
          "email":"o@x.in","user_type":"individual","broker":"ZERODHA",
          "exchanges":["NSE","BSE"],"products":["CNC"],"order_types":["MARKET"],
          "avatar_url":"https://x/y.png","meta":{"demat_consent":"consent"}}}
        """;

    KiteProfile p = mapper.readValue(body, KiteProfileResponse.class).data();

    assertThat(p.userId()).isNotNull();
    assertThat(p.userName()).isNotNull();
    assertThat(p.userShortname()).isNotNull();
    assertThat(p.email()).isNotNull();
    assertThat(p.userType()).isNotNull();
    assertThat(p.broker()).isNotNull();
    assertThat(p.exchanges()).isNotEmpty();
    assertThat(p.products()).isNotEmpty();
    assertThat(p.orderTypes()).isNotEmpty();
    assertThat(p.avatarUrl()).isNotNull();
    assertThat(p.meta().dematConsent()).isNotNull();
  }

  @Test
  void historicalCandleDecodesAllSevenPositions() throws Exception {
    String body =
        """
        {"status":"success","data":{"candles":[
          ["2026-06-15T09:15:00+0530",100.50,101.00,100.00,100.75,1500,12345]]}}
        """;

    KiteHistoricalResponse response = mapper.readValue(body, KiteHistoricalResponse.class);
    List<JsonNode> row = response.data().candles().get(0);
    KiteCandle candle = KiteCandle.of(row);

    assertThat(candle.timestamp()).isNotNull();
    assertThat(candle.open()).isEqualByComparingTo("100.50");
    assertThat(candle.high()).isEqualByComparingTo("101.00");
    assertThat(candle.low()).isEqualByComparingTo("100.00");
    assertThat(candle.close()).isEqualByComparingTo("100.75");
    assertThat(candle.volume()).isEqualTo(1500L);
    assertThat(candle.openInterest()).isEqualTo(12345L);
  }

  @Test
  void historicalCandleWithoutOiHasNullOpenInterest() throws Exception {
    String body =
        """
        {"status":"success","data":{"candles":[
          ["2026-06-15T09:15:00+0530",100.50,101.00,100.00,100.75,1500]]}}
        """;

    KiteHistoricalResponse response = mapper.readValue(body, KiteHistoricalResponse.class);
    KiteCandle candle = KiteCandle.of(response.data().candles().get(0));

    assertThat(candle.openInterest()).isNull();
    assertThat(candle.volume()).isEqualTo(1500L);
  }
}
