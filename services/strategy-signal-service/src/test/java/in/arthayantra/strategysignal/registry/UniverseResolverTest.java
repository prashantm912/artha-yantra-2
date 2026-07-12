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
}
