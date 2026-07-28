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
            builder,
            new ObjectMapper(),
            MarketCalendar.nse(),
            meters,
            new in.arthayantra.strategysignal.scalper.OptionExchangeResolver(
                builder, "http://market-data:8081"),
            "http://market-data:8081");
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
   * The DEPLOY-ORDERING case (cross-vendor review Major 3). A market-data older than this build
   * publishes no {@code exchange} on any leg. Without a second route to the master, every entry
   * would be refused for as long as that lasted — scalpers silently stop firing, which looks exactly
   * like a quiet tape. {@link
   * in.arthayantra.strategysignal.scalper.OptionExchangeResolver} re-reads the SAME authority
   * through {@code /instruments/search}, an endpoint that predates the new field, so the leg is
   * still tradeable and still master-sourced — never a name guess.
   */
  @Test
  void anOlderMarketDataIsHandledByResolvingTheExchangeFromTheMaster() {
    wire();
    stubChain(
        "NIFTY 50",
        legJson(null, "NIFTY26AUG25000CE", "152.65"),
        legJson(null, "NIFTY26AUG25000PE", "140.10"));
    // The master's answer CONTRADICTS a prefix guess, so only a real lookup can produce it.
    stubSearch("NIFTY26AUG25000CE", "[{\"exchange\":\"BFO\",\"tradingsymbol\":\"NIFTY26AUG25000CE\"}]");
    stubSearch("NIFTY26AUG25000PE", "[{\"exchange\":\"BFO\",\"tradingsymbol\":\"NIFTY26AUG25000PE\"}]");

    List<StrikePicker.Candidate> candidates = client.chain("NIFTY 50").orElseThrow().candidates();

    assertThat(candidates).hasSize(2).allSatisfy(c -> assertThat(c.exchange()).isEqualTo("BFO"));
    assertThat(
            SignalEngine.tradeableLeg(
                    "NFO", "NIFTY26AUGFUT", new BigDecimal("24092.00"), decision(candidates.get(0)))
                .exchange())
        .as("a resolved leg is tradeable — the deploy window costs a lookup, not the entry")
        .isEqualTo("BFO");
    assertThat(meters.counter("ay_scalper_chain_leg_no_exchange_total").count())
        .as("nothing was lost, so the loss counter must stay at zero")
        .isZero();
    assertThat(meters.get("ay_scalper_chain_exchange_capability").gauge().value())
        .as("the degraded upstream is a dashboard fact, not an inference from a quiet tape")
        .isZero();
    server.verify();
  }

  /**
   * Both routes exhausted: the payload omits the exchange AND the master cannot resolve it. The leg
   * is RETAINED for analysis but is NOT tradeable — the two are separate concerns (cross-vendor
   * review Critical 1).
   *
   * <p>Dropping it at the chain boundary was an ENTRY-safety reflex applied to a path that also
   * serves EXITS: this same snapshot feeds the read-only confluence-flip exit oracle, and an absent
   * decision reads as "do not exit" ({@code SignalEngine.confluenceFlipExit} → {@code
   * now.isPresent()}). A held position would have silently lost its CONFLUENCE_FLIP rail for as long
   * as any leg lacked an exchange — inverting the project doctrine that entries need FRESH truth
   * (you can always not enter) while exits need the BEST AVAILABLE truth (you cannot refuse to leave
   * forever). The candidate therefore survives with a null exchange, and {@link
   * SignalEngine#tradeableLeg} is what refuses it for ENTRY.
   */
  @Test
  void anUnresolvableLegSurvivesForAnalysisButIsNotTradeable() {
    wire();
    stubChain(
        "NIFTY 50",
        legJson(null, "NIFTY26AUG25000CE", "152.65"),
        legJson(null, "NIFTY26AUG25000PE", "140.10"));
    // The master knows nothing about either symbol — no exact match, so no exchange.
    stubSearch("NIFTY26AUG25000CE", "[]");
    stubSearch("NIFTY26AUG25000PE", "[]");

    MarketOiClient.ChainSnapshot snapshot = client.chain("NIFTY 50").orElseThrow();

    // Analytical eligibility: the oracle must still see the true market side on BOTH legs.
    assertThat(snapshot.candidates())
        .as("candidates are retained so the confluence-flip EXIT oracle can still evaluate")
        .hasSize(2)
        .allSatisfy(c -> assertThat(c.exchange()).isNull());
    assertThat(meters.counter("ay_scalper_chain_leg_no_exchange_total").count()).isEqualTo(2.0);

    // TRADE eligibility: the very same candidate is refused at the entry/stamp boundary.
    assertThat(
            SignalEngine.tradeableLeg(
                "NFO", "NIFTY26AUGFUT", new BigDecimal("24092.00"),
                decision(snapshot.candidates().get(0))))
        .as("an exchange-less leg must never be stamped or sized")
        .isNull();
    server.verify();
  }

  /**
   * An AMBIGUOUS master answer is refused, not broken arbitrarily. {@code (exchange, tradingsymbol)}
   * is the master's primary key, so the same symbol on two exchanges is a genuine ambiguity — and
   * {@code /search} is a RANKED trigram typeahead whose order is not a contract, so "take the first"
   * would be a coin toss on a money path.
   */
  @Test
  void anAmbiguousMasterAnswerIsRefusedRatherThanPickedFrom() {
    wire();
    stubChain("NIFTY 50", legJson(null, "NIFTY26AUG25000CE", "152.65"), legJson("NFO", "NIFTY26AUG25000PE", "140.10"));
    stubSearch(
        "NIFTY26AUG25000CE",
        "[{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY26AUG25000CE\"},"
            + "{\"exchange\":\"BFO\",\"tradingsymbol\":\"NIFTY26AUG25000CE\"}]");

    List<StrikePicker.Candidate> candidates = client.chain("NIFTY 50").orElseThrow().candidates();
    StrikePicker.Candidate ambiguous =
        candidates.stream().filter(c -> c.tradingsymbol().endsWith("CE")).findFirst().orElseThrow();

    assertThat(ambiguous.exchange()).isNull();
    assertThat(
            SignalEngine.tradeableLeg(
                "NFO", "NIFTY26AUGFUT", new BigDecimal("24092.00"), decision(ambiguous)))
        .isNull();
    assertThat(meters.counter("ay_scalper_chain_leg_no_exchange_total").count()).isEqualTo(1.0);
    server.verify();
  }

  /** A blank exchange is treated exactly like a missing one — no empty-string instrument keys. */
  @Test
  void aBlankExchangeIsAlsoUntradeable() {
    wire();
    stubChain(
        "NIFTY 50",
        legJson("", "NIFTY26AUG25000CE", "152.65"),
        legJson("NFO", "NIFTY26AUG25000PE", "140.10"));
    stubSearch("NIFTY26AUG25000CE", "[]");

    List<StrikePicker.Candidate> candidates = client.chain("NIFTY 50").orElseThrow().candidates();

    assertThat(candidates).hasSize(2);
    StrikePicker.Candidate blank =
        candidates.stream().filter(c -> "NIFTY26AUG25000CE".equals(c.tradingsymbol())).findFirst().orElseThrow();
    StrikePicker.Candidate good =
        candidates.stream().filter(c -> "NIFTY26AUG25000PE".equals(c.tradingsymbol())).findFirst().orElseThrow();

    assertThat(good.exchange()).isEqualTo("NFO");
    assertThat(
            SignalEngine.tradeableLeg(
                "NFO", "NIFTY26AUGFUT", new BigDecimal("24092.00"), decision(good)))
        .isNotNull();
    assertThat(
            SignalEngine.tradeableLeg(
                "NFO", "NIFTY26AUGFUT", new BigDecimal("24092.00"), decision(blank)))
        .as("a blank exchange is as untradeable as a missing one")
        .isNull();
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
    assertThat(meters.get("ay_scalper_chain_exchange_capability").gauge().value())
        .as("market-data published it, so the capability gauge reads healthy")
        .isEqualTo(1.0);
    // No /instruments/search was stubbed: the mock server fails an unexpected request, so this
    // asserting-by-omission IS the zero-extra-lookup claim on the emission hot path.
    server.verify();
  }

  /** One {@code /instruments/search} answer — the master's second route, used only as a fallback. */
  private void stubSearch(String tradingsymbol, String rowsJson) {
    server
        .expect(
            ExpectedCount.once(),
            requestTo(containsString("/api/v1/instruments/search?q=" + tradingsymbol)))
        .andRespond(withSuccess(rowsJson, MediaType.APPLICATION_JSON));
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
