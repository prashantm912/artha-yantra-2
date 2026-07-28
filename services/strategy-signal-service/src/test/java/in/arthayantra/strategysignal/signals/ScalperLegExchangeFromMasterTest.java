package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.scalper.MarketOiClient;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The option leg's exchange is the INSTRUMENT MASTER's, end to end: market-data publishes it on each
 * {@code /options/chain} leg, {@link MarketOiClient} carries it onto the {@link
 * StrikePicker.Candidate}, and {@code SignalEngine.tradeableLeg} stamps exactly that value on the
 * signal — the value that then drives paper sizing and, when {@code artha.scalper.execution=live},
 * broker order routing.
 *
 * <p>These drive the REAL path (chain JSON → candidate → stamp), not a helper in isolation. That is
 * the point: the previous implementation derived the exchange from the underlying's NAME (a
 * "starts with SENSEX/BANKEX/FOCIT ⇒ BFO" prefix guess), which silently mis-routes any newly listed
 * BSE root. {@link #theExchangeComesFromTheMasterNotTheSymbolsName()} fails outright if that
 * heuristic is ever reinstated anywhere on this path.
 */
class ScalperLegExchangeFromMasterTest {

  private MockRestServiceServer server;
  private MarketOiClient client;
  private SimpleMeterRegistry meters;

  private void wire() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    meters = new SimpleMeterRegistry();
    client =
        new MarketOiClient(
            builder, new ObjectMapper(), MarketCalendar.nse(), meters, "http://market-data:8081");
  }

  /**
   * Today's universe, unchanged: every live option root resolves to the exchange it actually trades
   * on. NIFTY is the only NFO index root the scalpers use; SENSEX/BANKEX are the live BSE roots and
   * FOCIT is the third BFO option root in {@code marketdata.instruments} (no strategy trades it, but
   * it costs nothing to prove the path is root-agnostic).
   */
  @Test
  void everyLiveRootResolvesToItsRealExchange() {
    assertStamped("NIFTY 50", "NFO", "NIFTY26AUG25000CE");
    assertStamped("SENSEX", "BFO", "SENSEX26JUL76300CE");
    assertStamped("BANKEX", "BFO", "BANKEX26JUL62000CE");
    assertStamped("FOCIT", "BFO", "FOCIT26JUL38000CE");
  }

  /**
   * The discriminating case. The payload's exchange CONTRADICTS what a name-prefix guess would say,
   * both ways round. Only a master-sourced exchange survives this; the old
   * {@code startsWith("SENSEX") ? "BFO" : "NFO"} heuristic returns the opposite on both legs.
   */
  @Test
  void theExchangeComesFromTheMasterNotTheSymbolsName() {
    // A NIFTY-named symbol the master says is BFO — a prefix guess would say NFO.
    assertStamped("NIFTY 50", "BFO", "NIFTY26AUG25000CE");
    // A SENSEX-named symbol the master says is NFO — a prefix guess would say BFO.
    assertStamped("SENSEX", "NFO", "SENSEX26JUL76300CE");
  }

  /**
   * Fail-closed at the chain boundary: a leg with no exchange has no canonical key, so it is DROPPED
   * rather than guessed. With no other tradeable leg the whole snapshot is absent — the same shape
   * the seam already handles for an unavailable chain — and the drop is counted for alerting.
   */
  @Test
  void aLegWithoutAnExchangeIsDroppedNotGuessed() {
    wire();
    stubChain(
        "NIFTY 50",
        legJson(null, "NIFTY26AUG25000CE", "152.65"),
        legJson(null, "NIFTY26AUG25000PE", "140.10"));

    Optional<MarketOiClient.ChainSnapshot> snapshot = client.chain("NIFTY 50");

    assertThat(snapshot).isEmpty();
    assertThat(meters.counter("ay_scalper_chain_leg_no_exchange_total").count()).isEqualTo(2.0);
    server.verify();
  }

  /** A blank exchange is treated exactly like a missing one — no empty-string instrument keys. */
  @Test
  void aBlankExchangeIsAlsoDropped() {
    wire();
    stubChain(
        "NIFTY 50",
        legJson("", "NIFTY26AUG25000CE", "152.65"),
        legJson("NFO", "NIFTY26AUG25000PE", "140.10"));

    List<StrikePicker.Candidate> candidates = client.chain("NIFTY 50").orElseThrow().candidates();

    assertThat(candidates).singleElement().satisfies(c -> {
      assertThat(c.exchange()).isEqualTo("NFO");
      assertThat(c.tradingsymbol()).isEqualTo("NIFTY26AUG25000PE");
    });
    assertThat(meters.counter("ay_scalper_chain_leg_no_exchange_total").count()).isEqualTo(1.0);
    server.verify();
  }

  /** Drives chain JSON → candidate → stamped tradeable leg and asserts the stamped exchange. */
  private void assertStamped(String underlying, String exchange, String symbol) {
    wire();
    stubChain(underlying, legJson(exchange, symbol, "152.65"), legJson(exchange, "IGNORED_PE", "9"));

    StrikePicker.Candidate candidate =
        client.chain(underlying).orElseThrow().candidates().stream()
            .filter(c -> c.tradingsymbol().equals(symbol))
            .findFirst()
            .orElseThrow();
    SignalEngine.TradeableLeg stamped =
        SignalEngine.tradeableLeg(
            "NFO", "NIFTY26AUGFUT", new BigDecimal("24092.00"), decision(candidate));

    assertThat(stamped.exchange()).as("stamped exchange for %s", symbol).isEqualTo(exchange);
    assertThat(stamped.tradingsymbol()).isEqualTo(symbol);
    assertThat(stamped.premium()).isEqualByComparingTo("152.65");
    assertThat(meters.counter("ay_scalper_chain_leg_no_exchange_total").count()).isZero();
    server.verify();
  }

  private void stubChain(String underlying, String ce, String pe) {
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/market/options/chain")))
        .andRespond(
            withSuccess(
                "{\"underlying\":\"" + underlying + "\",\"expiry\":\"2026-07-30\",\"spot\":\"25000\","
                    + "\"forward\":\"25010\",\"rows\":[{\"strike\":\"25000\",\"ce\":" + ce
                    + ",\"pe\":" + pe + "}]}",
                MediaType.APPLICATION_JSON));
  }

  /** One chain leg in market-data's wire shape; {@code exchange} null ⇒ the field is JSON null. */
  private static String legJson(String exchange, String tradingsymbol, String ltp) {
    return "{\"exchange\":" + (exchange == null ? "null" : "\"" + exchange + "\"")
        + ",\"tradingsymbol\":\"" + tradingsymbol + "\",\"ltp\":\"" + ltp + "\",\"iv\":\"0.14\"}";
  }

  private static ScalperConfluenceGate.Decision decision(StrikePicker.Candidate candidate) {
    return new ScalperConfluenceGate.Decision(
        OptionType.CE,
        List.of(
            new ScalperConfluenceGate.Leg(
                OptionType.CE, new StrikePicker.Pick(candidate, new BigDecimal("0.65")))),
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
