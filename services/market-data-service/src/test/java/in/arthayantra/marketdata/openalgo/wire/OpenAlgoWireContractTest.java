package in.arthayantra.marketdata.openalgo.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the OpenAlgo wire DTOs (twin of {@code KiteWireContractTest}): every documented
 * field of a FULL fixture must round-trip into its record — a dropped or mis-annotated field
 * surfaces here as a null assertion failure rather than being silently lost in production — and an
 * UNKNOWN field must never throw, so OpenAlgo's (or the fronted broker's) additive wire changes can
 * never crash the live capture. The per-strike {@code ce.oi}/{@code pe.oi} assertion is the
 * load-bearing OI-present proof (plan M0). Companion to the daily {@link
 * in.arthayantra.marketdata.openalgo.canary.OpenAlgoContractCanary}.
 */
class OpenAlgoWireContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void quoteMirrorsEveryDocumentedField() throws Exception {
    String body =
        """
        {"status":"success","data":{
          "ltp":24137.55,"bid":24137.5,"ask":24138.0,"open":24100.0,"high":24200.0,
          "low":24050.0,"prev_close":24095.0,"volume":987654,"oi":106860000}}
        """;

    OpenAlgoQuoteResponse response = mapper.readValue(body, OpenAlgoQuoteResponse.class);
    OpenAlgoQuote q = response.data();

    assertThat(response.status()).isEqualTo("success");
    assertThat(q.ltp()).isNotNull();
    assertThat(q.bid()).isNotNull();
    assertThat(q.ask()).isNotNull();
    assertThat(q.open()).isNotNull();
    assertThat(q.high()).isNotNull();
    assertThat(q.low()).isNotNull();
    assertThat(q.prevClose()).isNotNull();
    assertThat(q.volume()).isEqualTo(987654L);
    assertThat(q.oi()).isEqualByComparingTo("106860000"); // /quotes ALWAYS carries oi (0 for cash)
  }

  @Test
  void quoteIgnoresUnknownFieldsSoOpenAlgoAdditionsNeverCrashLive() {
    String withNewFields =
        """
        {"status":"success","brand_new_block":{"x":1},"data":{
          "ltp":24137.55,"galaxy_brain_field":42,"upper_circuit":99999}}
        """;

    assertThatCode(
            () -> {
              OpenAlgoQuoteResponse r = mapper.readValue(withNewFields, OpenAlgoQuoteResponse.class);
              assertThat(r.data().ltp()).isNotNull();
            })
        .doesNotThrowAnyException();
  }

  @Test
  void historyRowDecodesOhlcvAndOi() throws Exception {
    String body =
        """
        {"status":"success","data":[
          {"timestamp":1750909500,"open":100.50,"high":101.00,"low":100.00,
           "close":100.75,"volume":1500,"oi":12345}]}
        """;

    OpenAlgoHistoryResponse response = mapper.readValue(body, OpenAlgoHistoryResponse.class);
    OpenAlgoCandle c = response.data().get(0);

    assertThat(c.timestamp()).isEqualTo(1750909500L); // epoch SECONDS, JSON integer
    assertThat(c.open()).isEqualByComparingTo("100.50");
    assertThat(c.high()).isEqualByComparingTo("101.00");
    assertThat(c.low()).isEqualByComparingTo("100.00");
    assertThat(c.close()).isEqualByComparingTo("100.75");
    assertThat(c.volume()).isEqualTo(1500L);
    assertThat(c.oi()).isEqualTo(12345L);
  }

  @Test
  void historyRowWithoutOiHasNullOpenInterest() throws Exception {
    String body =
        """
        {"status":"success","data":[
          {"timestamp":1718434500,"open":100.50,"high":101.00,"low":100.00,
           "close":100.75,"volume":1500}]}
        """;

    OpenAlgoHistoryResponse response = mapper.readValue(body, OpenAlgoHistoryResponse.class);
    OpenAlgoCandle c = response.data().get(0);

    assertThat(c.oi()).isNull();
    assertThat(c.volume()).isEqualTo(1500L);
    assertThat(c.timestamp()).isEqualTo(1718434500L);
  }

  @Test
  void optionChainRoundTripsPerStrikeOi() throws Exception {
    String body =
        """
        {"status":"success","underlying":"NIFTY","underlying_ltp":24137.55,
         "expiry_date":"19JUN26","atm_strike":24100.0,"chain":[
          {"strike":24000.0,
           "ce":{"symbol":"NIFTY19JUN2624000CE","label":"24000 CE","ltp":312.55,"bid":312.05,
                 "ask":313.00,"open":300.0,"high":320.0,"low":295.0,"prev_close":305.0,
                 "volume":123456,"oi":987654,"lotsize":75,"tick_size":0.05},
           "pe":{"symbol":"NIFTY19JUN2624000PE","label":"24000 PE","ltp":45.10,"oi":456789,
                 "volume":654321,"lotsize":75}}]}
        """;

    OpenAlgoOptionChainResponse response =
        mapper.readValue(body, OpenAlgoOptionChainResponse.class);
    OpenAlgoChainRow row = response.chain().get(0);

    assertThat(response.underlyingLtp()).isNotNull();
    assertThat(response.atmStrike()).isNotNull();
    assertThat(response.expiryDate()).isEqualTo("19JUN26");
    assertThat(row.strike()).isEqualByComparingTo("24000.0");
    // the load-bearing OI-present proof (plan M0)
    assertThat(row.ce().oi()).isEqualByComparingTo("987654");
    assertThat(row.pe().oi()).isEqualByComparingTo("456789");
    assertThat(row.ce().lotSize()).isEqualTo(75);
    assertThat(row.ce().tickSize()).isNotNull();
  }

  @Test
  void optionChainIgnoresUnknownLegFields() {
    String body =
        """
        {"status":"success","chain":[
          {"strike":24000.0,"ce":{"ltp":1,"oi":2,"new_greek_block":{"vanna":0.1}},
           "pe":{"ltp":3,"oi":4}}],"new_top_level":1}
        """;

    assertThatCode(
            () -> {
              OpenAlgoOptionChainResponse r =
                  mapper.readValue(body, OpenAlgoOptionChainResponse.class);
              assertThat(r.chain().get(0).ce().oi()).isNotNull();
            })
        .doesNotThrowAnyException();
  }
}
