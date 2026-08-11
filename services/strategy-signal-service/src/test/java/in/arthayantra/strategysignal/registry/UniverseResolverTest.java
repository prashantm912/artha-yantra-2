package in.arthayantra.strategysignal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import in.arthayantra.strategysignal.registry.UniverseResolver.Constituent;
import in.arthayantra.strategysignal.registry.UniverseResolver.ResolvedUniverse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Pure resolver math (Phase 44): canonical checksum + explicit/exclude resolution, no REST. */
class UniverseResolverTest {

  private final UniverseResolver resolver =
      new UniverseResolver(RestClient.builder(), "http://unused");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void futuresOfUnderlyingSendsTheTRADINGSYMBOLFromTheInstrumentRefObject() throws Exception {
    // The bug, found by cross-vendor review on PR #1344. schema-v1 declares universe.underlying as an
    // instrumentRef OBJECT and REQUIRES it for futures_of_underlying, but this class read it with a
    // bare .asText(), which on an ObjectNode is the EMPTY STRING — so every schema-VALID config asked
    // market-data for `?underlying=`, got a 404, and the pin silently never happened.
    //
    // The assertion is on the OUTBOUND QUERY, deliberately. Asserting only on the returned items
    // would pass with an empty underlying too, because the stub answers whatever it is asked.
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(ExpectedCount.once(), requestTo(containsString("underlying=NIFTY")))
        .andRespond(
            withSuccess(
                "{\"contracts\":[{\"tradingsymbol\":\"NIFTY26AUGFUT\"},"
                    + "{\"tradingsymbol\":\"NIFTY26SEPFUT\"}]}",
                MediaType.APPLICATION_JSON));
    UniverseResolver futuresResolver = new UniverseResolver(builder, "http://market-data:8081");

    ResolvedUniverse u =
        futuresResolver.resolve(
            mapper.readTree(
                "{\"universe\":{\"mode\":\"futures_of_underlying\","
                    + "\"underlying\":{\"exchange\":\"NSE\",\"tradingsymbol\":\"NIFTY 50\"}}}"));

    assertThat(u.mode()).isEqualTo("futures_of_underlying");
    assertThat(u.items()).extracting(Constituent::tradingsymbol).containsExactly("NIFTY26AUGFUT");
    server.verify();
  }

  @Test
  void futuresOfUnderlyingStillAcceptsTheLegacyColonStringForm() throws Exception {
    // BacktestRunner tolerates "EXCH:SYMBOL" because it predates the schema and survives in old
    // configs; the resolver must not be stricter than the runner that consumes it.
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(ExpectedCount.once(), requestTo(containsString("underlying=NIFTY")))
        .andRespond(
            withSuccess(
                "{\"contracts\":[{\"tradingsymbol\":\"NIFTY26AUGFUT\"}]}",
                MediaType.APPLICATION_JSON));
    UniverseResolver futuresResolver = new UniverseResolver(builder, "http://market-data:8081");

    ResolvedUniverse u =
        futuresResolver.resolve(
            mapper.readTree(
                "{\"universe\":{\"mode\":\"futures_of_underlying\","
                    + "\"underlying\":\"NSE:NIFTY 50\"}}"));

    assertThat(u.items()).extracting(Constituent::tradingsymbol).containsExactly("NIFTY26AUGFUT");
    server.verify();
  }

  @Test
  void futuresOfUnderlyingPinsAnEmptyUniverseWhenTheLadderIsEmpty() throws Exception {
    // The test the class never had: nothing anywhere drove {"contracts":[]} through resolveFutures.
    //
    // ⚠️ It pins that the resolver returns EMPTY and does NOT throw. That is a deliberate departure
    // from how #1340 treated the same shape on the LIVE path, for two reasons: this class also backs
    // the GET /api/v1/strategies/{id}/universe preview, where "show me what this resolves to" must answer
    // honestly rather than 5xx; and ApiException(503, UPSTREAM_UNAVAILABLE) would be a false claim
    // when the upstream ANSWERED, with 200 and an empty ladder.
    //
    // ⚠️ And an empty pin here is NOT the silent-backtest hazard it looks like:
    // BacktestRunner.signalInstrument gates pinned-array routing to futures_screener and the two
    // funnel modes, so a futures_of_underlying run signals on the underlying SPOT and never reads
    // this pin. Verified before adding a submission-time guard for it — the guard would have refused
    // runs that work.
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(ExpectedCount.once(), requestTo(containsString("underlying=NIFTY")))
        .andRespond(withSuccess("{\"contracts\":[]}", MediaType.APPLICATION_JSON));
    UniverseResolver futuresResolver = new UniverseResolver(builder, "http://market-data:8081");

    ResolvedUniverse u =
        futuresResolver.resolve(
            mapper.readTree(
                "{\"universe\":{\"mode\":\"futures_of_underlying\",\"underlying\":{\"exchange\":\"NSE\",\"tradingsymbol\":\"NIFTY 50\"}}}"));

    assertThat(u.items()).isEmpty();
    assertThat(u.checksum()).isEqualTo(UniverseResolver.checksum(List.of()));
    server.verify();
  }

  @Test
  void checksumIsDeterministicAndOrderSensitive() {
    List<Constituent> a = List.of(new Constituent("NSE", "RELIANCE"), new Constituent("NSE", "TCS"));
    List<Constituent> b = List.of(new Constituent("NSE", "TCS"), new Constituent("NSE", "RELIANCE"));
    assertThat(UniverseResolver.checksum(a)).isEqualTo(UniverseResolver.checksum(a)); // deterministic
    assertThat(UniverseResolver.checksum(a)).isNotEqualTo(UniverseResolver.checksum(b)); // order matters
    // a constituent rebalance (different membership) yields a different checksum
    List<Constituent> c = List.of(new Constituent("NSE", "RELIANCE"), new Constituent("NSE", "INFY"));
    assertThat(UniverseResolver.checksum(a)).isNotEqualTo(UniverseResolver.checksum(c));
  }

  @Test
  void screenerPicksTakesTopNUnderlyingsForTheChosenSide() throws Exception {
    var screen =
        mapper.readTree(
            """
            {"longCandidates":[{"symbol":"HDFCBANK"},{"symbol":"ICICIBANK"},{"symbol":"SBIN"}],
             "shortCandidates":[{"symbol":"PNB"},{"symbol":"YESBANK"}]}
            """);
    // long side, capped at 2, in conviction (list) order
    assertThat(UniverseResolver.screenerPicks(screen, "long", 2))
        .containsExactly("HDFCBANK", "ICICIBANK");
    // short side reads the other list
    assertThat(UniverseResolver.screenerPicks(screen, "short", 5))
        .containsExactly("PNB", "YESBANK");
    // missing list / blank symbols -> empty, never throws
    assertThat(UniverseResolver.screenerPicks(mapper.readTree("{}"), "long", 5)).isEmpty();
  }

  @Test
  void explicitUniverseResolvesFromTheConfigList() throws Exception {
    var config =
        mapper.readTree(
            """
            {"universe":{"mode":"explicit","instruments":[
              {"exchange":"NSE","tradingsymbol":"RELIANCE"},
              {"exchange":"NSE","tradingsymbol":"TCS"}]}}
            """);
    ResolvedUniverse u = resolver.resolve(config);
    assertThat(u.mode()).isEqualTo("explicit");
    assertThat(u.items()).hasSize(2);
    assertThat(u.checksum()).isNotBlank();
    assertThat(u.survivorshipCaveat()).isNull();
  }

  // task_03b9f52d / task_9062b5f1: the funnel-CHOSEN screen date must be VISIBLE — the resolver
  // propagates the funnel response's screenDate into ResolvedUniverse.asOf for both funnel modes, so
  // backtest run provenance can record which persisted screen fed the pinned universe (a weekend/
  // holiday submission is then interpretable). Uses a MockRestServiceServer-bound RestClient to stand
  // in for market-data's REST funnel (this service holds no marketdata grant).

  @Test
  void manasFunnelCarriesTheScreenDateIntoAsOf() throws Exception {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            ExpectedCount.once(),
            requestTo(containsString("/api/v1/market/screener/manas-arora/funnel")))
        .andRespond(
            withSuccess(
                "{\"screenDate\":\"2026-07-10\",\"regime\":null,"
                    + "\"immediatelyBuyable\":[{\"symbol\":\"RELIANCE\"}],"
                    + "\"onDeck\":[{\"symbol\":\"TCS\"}],\"watch\":[]}",
                MediaType.APPLICATION_JSON));
    UniverseResolver funnelResolver = new UniverseResolver(builder, "http://market-data:8081");

    ResolvedUniverse u =
        funnelResolver.resolve(mapper.readTree("{\"universe\":{\"mode\":\"manas_arora_funnel\"}}"));

    assertThat(u.mode()).isEqualTo("manas_arora_funnel");
    assertThat(u.asOf()).isEqualTo("2026-07-10"); // the funnel-chosen (latest persisted) screen date
    assertThat(u.items()).extracting(Constituent::tradingsymbol).containsExactly("RELIANCE", "TCS");
    server.verify();
  }

  @Test
  void minerviniFunnelCarriesTheScreenDateIntoAsOf() throws Exception {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            ExpectedCount.once(),
            requestTo(containsString("/api/v1/market/screener/minervini/funnel")))
        .andRespond(
            withSuccess(
                "{\"screenDate\":\"2026-07-09\",\"regime\":null,"
                    + "\"immediatelyBuyable\":[{\"symbol\":\"INFY\"}],\"onDeck\":[],\"watch\":[]}",
                MediaType.APPLICATION_JSON));
    UniverseResolver funnelResolver = new UniverseResolver(builder, "http://market-data:8081");

    ResolvedUniverse u =
        funnelResolver.resolve(
            // bucket=buyable so on-deck is NOT read — still carries the chosen screen date
            mapper.readTree(
                "{\"universe\":{\"mode\":\"minervini_funnel\",\"bucket\":\"buyable\"}}"));

    assertThat(u.mode()).isEqualTo("minervini_funnel");
    assertThat(u.asOf()).isEqualTo("2026-07-09");
    assertThat(u.items()).extracting(Constituent::tradingsymbol).containsExactly("INFY");
    server.verify();
  }

  @Test
  void funnelWithNoScreenDateLeavesAsOfNull() throws Exception {
    // No screen has ever run: the funnel serves null screenDate + empty lists. asOf must stay a real
    // Java null (never the literal string "null" — the isTextual() guard in funnelScreenDate).
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            ExpectedCount.once(),
            requestTo(containsString("/api/v1/market/screener/manas-arora/funnel")))
        .andRespond(
            withSuccess(
                "{\"screenDate\":null,\"regime\":null,"
                    + "\"immediatelyBuyable\":[],\"onDeck\":[],\"watch\":[]}",
                MediaType.APPLICATION_JSON));
    UniverseResolver funnelResolver = new UniverseResolver(builder, "http://market-data:8081");

    ResolvedUniverse u =
        funnelResolver.resolve(mapper.readTree("{\"universe\":{\"mode\":\"manas_arora_funnel\"}}"));

    assertThat(u.asOf()).isNull();
    assertThat(u.items()).isEmpty();
    server.verify();
  }

  /**
   * The ONE definition of {@code universe.bucket}, pinned input-by-input. The submission pin and the
   * live swing batch both call this method, so agreement between them is structural — what this table
   * pins is the RESOLUTION each input gets, which is what a future edit could silently change.
   *
   * <p>Only the exact literal {@code "buyable"} narrows to the buyable bucket. The schema enums the
   * leaf to {@code buyable | buyable_on_deck}, so the unknown/case/null rows below are unreachable
   * through a validating publish — they are pinned because {@code asText(default)} is defensive and a
   * pre-enum version row could still carry one; every such row must resolve PERMISSIVELY (both
   * buckets), never silently narrow a live strategy's universe.
   */
  @Test
  void bucketResolvesPermissivelyForEveryInputExceptTheExactBuyableLiteral() throws Exception {
    assertThat(includesOnDeckFor("{}")).as("absent → schema default buyable_on_deck").isTrue();
    assertThat(includesOnDeckFor("{\"bucket\":null}")).as("JSON null → default").isTrue();
    assertThat(includesOnDeckFor("{\"bucket\":\"buyable\"}")).as("the ONE narrowing value").isFalse();
    assertThat(includesOnDeckFor("{\"bucket\":\"buyable_on_deck\"}")).isTrue();
    assertThat(includesOnDeckFor("{\"bucket\":\"BUYABLE\"}")).as("case variant → NOT narrowing").isTrue();
    assertThat(includesOnDeckFor("{\"bucket\":\"Buyable\"}")).as("case variant → NOT narrowing").isTrue();
    assertThat(includesOnDeckFor("{\"bucket\":\" buyable \"}")).as("padded → NOT narrowing").isTrue();
    assertThat(includesOnDeckFor("{\"bucket\":\"nonsense\"}")).as("unknown string → default").isTrue();
    assertThat(includesOnDeckFor("{\"bucket\":7}")).as("non-string → default").isTrue();
  }

  private boolean includesOnDeckFor(String universeJson) throws Exception {
    return UniverseResolver.includesOnDeck(mapper.readTree(universeJson));
  }
}
