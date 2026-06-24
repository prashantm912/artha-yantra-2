package in.arthayantra.strategysignal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.strategysignal.execution.OrderGateway.Funds;
import in.arthayantra.strategysignal.execution.OrderGateway.OrderbookEntry;
import in.arthayantra.strategysignal.execution.OrderGateway.PositionEntry;
import in.arthayantra.strategysignal.execution.OrderGateway.TradebookEntry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Locks the §18.1 wire→domain mapping of {@link RestOpenAlgoOrderReadGateway}: each OpenAlgo
 * account/order response maps into the broker-agnostic {@link OrderGateway} record, the request is a
 * {@code POST} carrying just the {@code apikey}, the signed-net-qty → BUY/SELL/FLAT derivation
 * holds, and an upstream error surfaces as an {@link ApiException} (502), never a leaked broker type.
 */
class RestOpenAlgoOrderReadGatewayTest {

  private MockRestServiceServer server;
  private RestOpenAlgoOrderReadGateway gateway;

  private void wire() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    gateway = new RestOpenAlgoOrderReadGateway(builder, "http://openalgo:5000", "test-key", new ObjectMapper());
  }

  private void stub(String path, String json) {
    server
        .expect(requestTo(containsString(path)))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.apikey").value("test-key"))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void mapsOrderbookRow() {
    wire();
    stub(
        "/api/v1/orderbook",
        "{\"status\":\"success\",\"data\":{\"orders\":[{\"action\":\"SELL\",\"exchange\":\"NSE\","
            + "\"order_status\":\"complete\",\"orderid\":\"24120900000213\",\"price\":1500,"
            + "\"pricetype\":\"LIMIT\",\"product\":\"MIS\",\"quantity\":5,\"symbol\":\"INFY\","
            + "\"timestamp\":\"09-Dec-2024 09:44:09\",\"trigger_price\":0}],\"statistics\":{}}}");

    List<OrderbookEntry> orders = gateway.orderbook();

    assertThat(orders).hasSize(1);
    OrderbookEntry o = orders.get(0);
    assertThat(o.symbol()).isEqualTo("INFY");
    assertThat(o.action()).isEqualTo("SELL");
    assertThat(o.qty()).isEqualByComparingTo("5");
    assertThat(o.price()).isEqualByComparingTo("1500");
    assertThat(o.pricetype()).isEqualTo("LIMIT");
    assertThat(o.status()).isEqualTo("complete");
    assertThat(o.orderId()).isEqualTo("24120900000213");
    assertThat(o.timestamp()).isEqualTo("09-Dec-2024 09:44:09");
    server.verify();
  }

  @Test
  void mapsPositionsAndDerivesSideFromSignedQty() {
    wire();
    stub(
        "/api/v1/positionbook",
        "{\"status\":\"success\",\"data\":["
            + "{\"average_price\":\"100.00\",\"exchange\":\"NSE\",\"product\":\"MIS\",\"quantity\":1,"
            + "\"symbol\":\"RELIANCE\",\"ltp\":\"110.00\",\"pnl\":\"10.00\"},"
            + "{\"average_price\":\"0.00\",\"exchange\":\"NSE\",\"product\":\"MIS\",\"quantity\":-1,\"symbol\":\"INFY\"},"
            + "{\"average_price\":\"0.00\",\"exchange\":\"NSE\",\"product\":\"MIS\",\"quantity\":0,\"symbol\":\"YESBANK\"}]}");

    List<PositionEntry> positions = gateway.positions();

    assertThat(positions).hasSize(3);
    assertThat(positions.get(0).side()).isEqualTo("BUY"); // qty +1
    assertThat(positions.get(0).ltp()).isEqualByComparingTo("110.00");
    assertThat(positions.get(0).mtmPnl()).isEqualByComparingTo("10.00");
    assertThat(positions.get(1).side()).isEqualTo("SELL"); // qty -1
    assertThat(positions.get(2).side()).isEqualTo("FLAT"); // qty 0
    server.verify();
  }

  @Test
  void mapsTradebookRow() {
    wire();
    stub(
        "/api/v1/tradebook",
        "{\"status\":\"success\",\"data\":[{\"action\":\"BUY\",\"average_price\":1914.4,"
            + "\"exchange\":\"NSE\",\"orderid\":\"24120900009388\",\"product\":\"MIS\",\"quantity\":1,"
            + "\"symbol\":\"INFY\",\"timestamp\":\"09-Dec-2024 09:16:48\",\"trade_value\":1914.4}]}");

    List<TradebookEntry> trades = gateway.tradebook();

    assertThat(trades).hasSize(1);
    TradebookEntry t = trades.get(0);
    assertThat(t.symbol()).isEqualTo("INFY");
    assertThat(t.action()).isEqualTo("BUY");
    assertThat(t.qty()).isEqualByComparingTo("1");
    assertThat(t.price()).isEqualByComparingTo("1914.4");
    assertThat(t.tradeValue()).isEqualByComparingTo("1914.4");
    assertThat(t.orderId()).isEqualTo("24120900009388");
    assertThat(t.tradeTime()).isEqualTo("09-Dec-2024 09:16:48");
    server.verify();
  }

  @Test
  void mapsFundsWithOkStatus() {
    wire();
    stub(
        "/api/v1/funds",
        "{\"status\":\"success\",\"data\":{\"availablecash\":\"18083.01\",\"collateral\":\"0.00\","
            + "\"m2mrealized\":\"1.50\",\"m2munrealized\":\"-2.50\",\"utiliseddebits\":\"500.00\"}}");

    Funds funds = gateway.funds();

    assertThat(funds.status()).isEqualTo("OK");
    assertThat(funds.availableCash()).isEqualByComparingTo("18083.01");
    assertThat(funds.collateral()).isEqualByComparingTo("0.00");
    assertThat(funds.m2mRealized()).isEqualByComparingTo("1.50");
    assertThat(funds.m2mUnrealized()).isEqualByComparingTo("-2.50");
    assertThat(funds.utilisedDebits()).isEqualByComparingTo("500.00");
    server.verify();
  }

  @Test
  void upstreamErrorSurfacesAsApiException() {
    wire();
    server.expect(requestTo(containsString("/api/v1/funds"))).andRespond(withServerError());

    assertThatThrownBy(() -> gateway.funds())
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("openalgo");
  }
}
