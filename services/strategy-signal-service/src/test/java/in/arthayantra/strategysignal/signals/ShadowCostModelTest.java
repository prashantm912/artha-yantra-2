package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * F8: the shadow round trip priced through the engine fill model, hand-computed against the pinned
 * statutory schedule (FeeConstants, 2026-06-13 provenance). NIFTY option lot 75, BUY 100.00 → SELL
 * 140.00, zero added slippage:
 *
 * <pre>
 * BUY  7500: brokerage 20.00 + txn 2.63 + sebi 0.01 + stamp 0.23 + gst 4.08            = 26.95
 * SELL 10500: brokerage 20.00 + stt 10.50 + txn 3.68 + sebi 0.01 + gst 4.26            = 38.45
 * cost 65.40; net = (10500 − 38.45) − (7500 + 26.95) = 2934.60  (raw 40 pts × 75 = 3000)
 * </pre>
 *
 * The same figures are the owner's Zerodha-brokerage-calculator cross-check fixture.
 */
class ShadowCostModelTest {

  private MockRestServiceServer server;
  private ShadowCostModel model;

  private void wire() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    model = new ShadowCostModel(builder, "http://market-data:8081");
  }

  @Test
  void pricesTheStatutoryRoundTripExactToThePaisa() {
    wire();
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/instruments/NFO/")))
        .andRespond(withSuccess("{\"lotSize\":75,\"tickSize\":\"0.05\"}", MediaType.APPLICATION_JSON));

    ShadowCostModel.RoundTrip rt =
        model.price("NFO", "NIFTY26JUL24250CE", new BigDecimal("100.00"), new BigDecimal("140.00"));

    assertThat(rt.cost()).isEqualByComparingTo("65.40");
    assertThat(rt.pnlNet()).isEqualByComparingTo("2934.60");

    // lot size is memoized — a second pricing fires NO further instrument lookup
    ShadowCostModel.RoundTrip again =
        model.price("NFO", "NIFTY26JUL24250CE", new BigDecimal("100.00"), new BigDecimal("60.00"));
    assertThat(again.pnlNet()).isNotNull();
    assertThat(again.pnlNet().signum()).isNegative(); // −40 pts and costs on top
    server.verify();
  }

  @Test
  void losingTripCostsStackOnTopOfTheRawLoss() {
    wire();
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/instruments/NFO/")))
        .andRespond(withSuccess("{\"lotSize\":75}", MediaType.APPLICATION_JSON));

    // raw −40 pts × 75 = −3000; net must be strictly worse
    ShadowCostModel.RoundTrip rt =
        model.price("NFO", "SHDLOSS", new BigDecimal("100.00"), new BigDecimal("60.00"));
    assertThat(rt.pnlNet()).isLessThan(new BigDecimal("-3000"));
    assertThat(rt.cost()).isPositive();
  }

  @Test
  void unresolvableLotSizeOrPricelessExitStaysUnpriced() {
    wire();
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/instruments/NFO/")))
        .andRespond(withServerError());

    ShadowCostModel.RoundTrip failed =
        model.price("NFO", "SHDGONE", new BigDecimal("100.00"), new BigDecimal("140.00"));
    assertThat(failed.cost()).isNull();
    assertThat(failed.pnlNet()).isNull();

    ShadowCostModel.RoundTrip stale = model.price("NFO", "SHDGONE", new BigDecimal("100.00"), null);
    assertThat(stale.pnlNet()).isNull();
  }
}
