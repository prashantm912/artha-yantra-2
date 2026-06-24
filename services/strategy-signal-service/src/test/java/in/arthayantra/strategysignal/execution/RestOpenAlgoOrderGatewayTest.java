package in.arthayantra.strategysignal.execution;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.strategysignal.execution.OrderGateway.OrderAck;
import in.arthayantra.strategysignal.execution.OrderGateway.OrderRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for the §17.3 OpenAlgo order-WRITE gateway. WireMock stubs OpenAlgo's
 * {@code /api/v1/placeorder} ONLY — NO real HTTP to any broker / OpenAlgo, NO real account, NO real
 * order. Locks: the domain {@link OrderRequest} → OpenAlgo payload mapping
 * (action/symbol/exchange/qty/product/pricetype/strategy), the {@code orderid}/{@code status}
 * response → {@link OrderAck} mapping, and — the never-propagate contract — that a 5xx OR an error
 * JSON yields a {@code FAILED} ack WITHOUT throwing (a broker hiccup can never break signal
 * emission). The §18.1 reads are delegated and verified pass-through.
 */
class RestOpenAlgoOrderGatewayTest {

  private static WireMockServer wireMock;

  @BeforeAll
  static void start() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
  }

  @BeforeEach
  void reset() {
    wireMock.resetAll();
  }

  private static RestOpenAlgoOrderGateway gateway() {
    return new RestOpenAlgoOrderGateway(
        RestClient.builder(), wireMock.baseUrl(), "test-key", "ArthaYantra", new ObjectMapper());
  }

  @Test
  void mapsDomainOrderToOpenAlgoPayloadAndOrderIdResponse() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/placeorder"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"orderid\":\"240307000614705\",\"status\":\"success\"}")));

    OrderAck ack =
        gateway()
            .place(
                new OrderRequest(
                    "NFO", "NIFTY24JUN24000CE", "BUY", 50, "MIS", "MARKET", null));

    // Response → domain ack.
    assertThat(ack.orderId()).isEqualTo("240307000614705");
    assertThat(ack.status()).isEqualTo("OPEN");

    // Domain request → OpenAlgo placeorder payload — every documented field, exact values. Quantity
    // and price are STRING-typed on the wire (the documented placeorder sample sends them quoted);
    // null price ⇒ "0".
    wireMock.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/placeorder"))
            .withRequestBody(
                equalToJson(
                    "{\"apikey\":\"test-key\",\"strategy\":\"ArthaYantra\",\"exchange\":\"NFO\","
                        + "\"symbol\":\"NIFTY24JUN24000CE\",\"action\":\"BUY\",\"product\":\"MIS\","
                        + "\"pricetype\":\"MARKET\",\"quantity\":\"50\",\"price\":\"0\"}")));
    assertThat(wireMock.getAllServeEvents()).as("single placeorder call").hasSize(1);
  }

  @Test
  void mapsLimitOrderPriceAndSellSide() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/placeorder"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"orderid\":\"OA-2\",\"status\":\"success\"}")));

    gateway()
        .place(
            new OrderRequest(
                "NFO", "NIFTY24JUN24000PE", "SELL", 75, "NRML", "LIMIT", new BigDecimal("123.45")));

    wireMock.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/placeorder"))
            .withRequestBody(matchingJsonPath("$.action", equalTo("SELL")))
            .withRequestBody(matchingJsonPath("$.product", equalTo("NRML")))
            .withRequestBody(matchingJsonPath("$.pricetype", equalTo("LIMIT")))
            .withRequestBody(matchingJsonPath("$.quantity", equalTo("75")))
            .withRequestBody(matchingJsonPath("$.price", equalTo("123.45"))));
  }

  @Test
  void serverErrorYieldsFailedAckWithoutThrowing() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/placeorder"))
            .willReturn(aResponse().withStatus(500)));

    OrderAck[] ack = new OrderAck[1];
    assertThatCode(
            () ->
                ack[0] =
                    gateway()
                        .place(
                            new OrderRequest(
                                "NFO", "NIFTY24JUN24000CE", "BUY", 50, "MIS", "MARKET", null)))
        .doesNotThrowAnyException();

    assertThat(ack[0].status()).isEqualTo("FAILED");
    assertThat(ack[0].orderId()).isNull();
  }

  @Test
  void errorJsonYieldsFailedAckWithoutThrowing() {
    // OpenAlgo returns a non-200 + {"status":"error","message":...} on a rejection.
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/placeorder"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"error\",\"message\":\"Invalid openalgo apikey\"}")));

    OrderAck ack =
        gateway()
            .place(new OrderRequest("NFO", "NIFTY24JUN24000CE", "BUY", 50, "MIS", "MARKET", null));

    assertThat(ack.status()).isEqualTo("FAILED");
    assertThat(ack.orderId()).isNull();
  }

  @Test
  void delegatesTheReadSurfaceToTheReadGateway() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/orderbook"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"orders\":[{\"action\":\"BUY\","
                            + "\"exchange\":\"NFO\",\"order_status\":\"complete\",\"orderid\":\"OA-9\","
                            + "\"price\":0,\"pricetype\":\"MARKET\",\"product\":\"MIS\",\"quantity\":50,"
                            + "\"symbol\":\"NIFTY24JUN24000CE\",\"timestamp\":\"09-Dec-2024 09:44:09\","
                            + "\"trigger_price\":0}],\"statistics\":{}}}")));

    assertThat(gateway().orderbook()).hasSize(1);
    assertThat(gateway().orderbook().get(0).orderId()).isEqualTo("OA-9");
  }
}
