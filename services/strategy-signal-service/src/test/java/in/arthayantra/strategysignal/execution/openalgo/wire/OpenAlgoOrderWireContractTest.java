package in.arthayantra.strategysignal.execution.openalgo.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the OpenAlgo order-read wire DTOs (twin of market-data's
 * {@code OpenAlgoWireContractTest}): every documented field of a FULL docs.openalgo.in fixture must
 * round-trip into its record — a dropped or mis-annotated field surfaces here as a null assertion
 * failure rather than being silently lost in production — and an UNKNOWN field must never throw, so
 * OpenAlgo's (or the fronted broker's) additive wire changes can never crash a read. Companion to
 * the {@code package-info} anti-corruption convention.
 */
class OpenAlgoOrderWireContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void orderbookMirrorsEveryDocumentedField() throws Exception {
    String body =
        """
        {"status":"success","data":{"orders":[
          {"action":"SELL","exchange":"NSE","order_status":"complete","orderid":"24120900000213",
           "price":1500,"pricetype":"LIMIT","product":"MIS","quantity":5,"symbol":"INFY",
           "timestamp":"09-Dec-2024 09:44:09","trigger_price":0}],
         "statistics":{"total_buy_orders":1,"total_sell_orders":1,"total_completed_orders":2,
           "total_open_orders":0,"total_rejected_orders":0}}}
        """;

    OpenAlgoOrderbookResponse response = mapper.readValue(body, OpenAlgoOrderbookResponse.class);
    OpenAlgoOrder o = response.data().orders().get(0);

    assertThat(response.status()).isEqualTo("success");
    assertThat(o.symbol()).isEqualTo("INFY");
    assertThat(o.exchange()).isEqualTo("NSE");
    assertThat(o.action()).isEqualTo("SELL");
    assertThat(o.quantity()).isEqualByComparingTo("5");
    assertThat(o.price()).isEqualByComparingTo("1500");
    assertThat(o.triggerPrice()).isEqualByComparingTo("0");
    assertThat(o.pricetype()).isEqualTo("LIMIT");
    assertThat(o.product()).isEqualTo("MIS");
    assertThat(o.orderid()).isEqualTo("24120900000213");
    assertThat(o.orderStatus()).isEqualTo("complete");
    assertThat(o.timestamp()).isEqualTo("09-Dec-2024 09:44:09");
    assertThat(response.data().statistics().totalCompletedOrders()).isEqualTo(2);
  }

  @Test
  void tradebookMirrorsEveryDocumentedField() throws Exception {
    String body =
        """
        {"status":"success","data":[
          {"action":"BUY","average_price":1914.4,"exchange":"NSE","orderid":"24120900009388",
           "product":"MIS","quantity":1,"symbol":"INFY","timestamp":"09-Dec-2024 09:16:48",
           "trade_value":1914.4}]}
        """;

    OpenAlgoTradebookResponse response = mapper.readValue(body, OpenAlgoTradebookResponse.class);
    OpenAlgoTrade t = response.data().get(0);

    assertThat(t.symbol()).isEqualTo("INFY");
    assertThat(t.exchange()).isEqualTo("NSE");
    assertThat(t.action()).isEqualTo("BUY");
    assertThat(t.quantity()).isEqualByComparingTo("1");
    assertThat(t.averagePrice()).isEqualByComparingTo("1914.4");
    assertThat(t.tradeValue()).isEqualByComparingTo("1914.4");
    assertThat(t.product()).isEqualTo("MIS");
    assertThat(t.orderid()).isEqualTo("24120900009388");
    assertThat(t.timestamp()).isEqualTo("09-Dec-2024 09:16:48");
  }

  @Test
  void positionbookMirrorsEveryDocumentedFieldIncludingStringAvgPriceAndSignedQty() throws Exception {
    String body =
        """
        {"status":"success","data":[
          {"average_price":"0.00","exchange":"NSE","product":"MIS","quantity":-1,"symbol":"INFY",
           "ltp":"1900.50","pnl":"-14.50"}]}
        """;

    OpenAlgoPositionbookResponse response =
        mapper.readValue(body, OpenAlgoPositionbookResponse.class);
    OpenAlgoPosition p = response.data().get(0);

    assertThat(p.symbol()).isEqualTo("INFY");
    assertThat(p.exchange()).isEqualTo("NSE");
    assertThat(p.product()).isEqualTo("MIS");
    assertThat(p.quantity()).isEqualByComparingTo("-1"); // SIGNED net qty (short)
    assertThat(p.averagePrice()).isEqualByComparingTo("0.00"); // STRING decimal on the wire
    assertThat(p.ltp()).isEqualByComparingTo("1900.50");
    assertThat(p.pnl()).isEqualByComparingTo("-14.50");
  }

  @Test
  void fundsMirrorsEveryDocumentedStringDecimalField() throws Exception {
    String body =
        """
        {"status":"success","data":{
          "availablecash":"18083.01","collateral":"0.00","m2mrealized":"0.00",
          "m2munrealized":"0.00","utiliseddebits":"0.00"}}
        """;

    OpenAlgoFundsResponse response = mapper.readValue(body, OpenAlgoFundsResponse.class);
    OpenAlgoFunds f = response.data();

    assertThat(f.availableCash()).isEqualByComparingTo("18083.01"); // STRING decimal → BigDecimal
    assertThat(f.collateral()).isEqualByComparingTo("0.00");
    assertThat(f.m2mRealized()).isEqualByComparingTo("0.00");
    assertThat(f.m2mUnrealized()).isEqualByComparingTo("0.00");
    assertThat(f.utilisedDebits()).isEqualByComparingTo("0.00");
  }

  @Test
  void ignoresUnknownFieldsSoOpenAlgoAdditionsNeverCrashAread() {
    String withNewFields =
        """
        {"status":"success","brand_new_block":{"x":1},"data":{"orders":[
          {"symbol":"INFY","action":"BUY","galaxy_brain_field":42,"new_status_enum":"queued"}],
         "statistics":{"total_buy_orders":1,"new_stat":99}}}
        """;

    assertThatCode(
            () -> {
              OpenAlgoOrderbookResponse r =
                  mapper.readValue(withNewFields, OpenAlgoOrderbookResponse.class);
              assertThat(r.data().orders().get(0).symbol()).isEqualTo("INFY");
            })
        .doesNotThrowAnyException();
  }
}
