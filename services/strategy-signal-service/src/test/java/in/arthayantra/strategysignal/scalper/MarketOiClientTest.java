package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Locks the §12.2 mapping of market-data analytics JSON into the local scalper OI/macro records,
 * and the conservative-default contract: an upstream miss must degrade to a value that never
 * confirms a side (NEUTRAL quadrant / null soft-numeric / 0 breadth), never throw.
 */
class MarketOiClientTest {

  private static final String UNDERLYING = "NIFTY 50";
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 25);
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 6, 20);

  private MockRestServiceServer server;
  private MarketOiClient client;

  private void wire() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    client = new MarketOiClient(builder, new ObjectMapper(), "http://market-data:8081");
  }

  private void stub(String pathFragment, String json) {
    server
        .expect(ExpectedCount.once(), requestTo(containsString(pathFragment)))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void mapsOiHalfFromAnalyticsEndpoints() {
    wire();
    // underlying chain read: SHORT_COVERING (price↑, OI↓ — shorts exiting → bullish)
    stub("/api/v1/market/options/spurt", "{\"summary\":{\"interpretation\":\"SHORT_COVERING\"}}");
    // futures grid: the NEAREST expiry leg decides; far leg must be ignored
    stub(
        "/api/v1/market/futures/banks",
        "{\"items\":["
            + "{\"expiry\":\"2026-07-30\",\"interpretation\":\"LONG_UNWINDING\",\"basis\":\"30\"},"
            + "{\"expiry\":\"2026-06-25\",\"interpretation\":\"LONG_BUILDUP\",\"basis\":\"10\"}]}");
    stub("/api/v1/market/options/active-strikes", "{\"sentimentPct\":\"12.5\",\"items\":[]}");
    // trending: latest point PE 200 / CE 100 of 300 total → (200-100)*100/300 = 33.3333
    stub(
        "/api/v1/market/options/trending",
        "{\"items\":[{\"ceOi\":50,\"peOi\":50},{\"ceOi\":100,\"peOi\":200}]}");
    stub(
        "/api/v1/market/futures/term-structure",
        "{\"contracts\":["
            + "{\"expiry\":\"2026-07-30\",\"basisAbsolute\":\"25\"},"
            + "{\"expiry\":\"2026-06-25\",\"basisAbsolute\":\"10.5\"}]}");

    Oi oi = client.oi(UNDERLYING, EXPIRY);

    assertThat(oi.underlying()).isEqualTo(OiQuadrant.SHORT_COVERING);
    assertThat(oi.futures()).isEqualTo(OiQuadrant.LONG_BUILDUP); // nearest expiry, not the far leg
    assertThat(oi.sentimentPct()).isEqualByComparingTo("12.5");
    assertThat(oi.trendingPeMinusCePct()).isEqualByComparingTo("33.3333");
    assertThat(oi.futuresBasis()).isEqualByComparingTo("10.5"); // front leg basis
    server.verify();
  }

  @Test
  void mapsMacroHalfAndScalesIvRankToHundred() {
    wire();
    // rank is a 0..1 fraction upstream; must surface ×100 so the scorer's <50 gate is meaningful
    stub(
        "/api/v1/market/options/iv-history",
        "{\"currentIv\":\"0.14\",\"rank\":\"0.30\",\"percentile\":28,\"insufficientHistory\":false}");
    stub(
        "/api/v1/market/breadth",
        "{\"summary\":{\"advances\":35,\"declines\":12,\"unchanged\":3,\"total\":50}}");
    stub(
        "/api/v1/market/fii-dii/long-short",
        "{\"items\":[{\"fiiLong\":60,\"fiiShort\":40},{\"fiiLong\":70,\"fiiShort\":30}]}");

    Macro m = client.macro(UNDERLYING, TRADE_DATE);

    assertThat(m.atmIv()).isEqualByComparingTo("0.14");
    assertThat(m.ivRank()).isEqualByComparingTo("30"); // 0.30 × 100
    assertThat(m.advances()).isEqualTo(35);
    assertThat(m.declines()).isEqualTo(12);
    assertThat(m.fiiLongPct()).isEqualByComparingTo("70"); // 70 / (70+30) × 100
    assertThat(m.vixLevel()).isNull(); // no VIX endpoint yet — documented v1 gap
    assertThat(m.vixRising()).isNull();
    server.verify();
  }

  @Test
  void nullIvRankWhenHistoryInsufficient() {
    wire();
    stub(
        "/api/v1/market/options/iv-history",
        "{\"currentIv\":\"0.18\",\"rank\":null,\"insufficientHistory\":true}");
    stub("/api/v1/market/breadth", "{\"summary\":{\"advances\":5,\"declines\":40}}");
    stub("/api/v1/market/fii-dii/long-short", "{\"items\":[]}");

    Macro m = client.macro(UNDERLYING, TRADE_DATE);

    assertThat(m.ivRank()).isNull(); // not 0 — "unknown", so the iv_rank dot stays unconfirmed
    assertThat(m.fiiLongPct()).isNull(); // empty envelope
  }

  @Test
  void flattensTheNearestChainIntoBothSideCandidates() {
    wire();
    stub(
        "/api/v1/market/options/chain",
        "{\"underlying\":\"NIFTY 50\",\"expiry\":\"2026-06-25\",\"spot\":\"20000\",\"forward\":\"20040\","
            + "\"rows\":["
            + "{\"strike\":\"19900\",\"ce\":{\"tradingsymbol\":\"NIFTY19900CE\",\"ltp\":\"180\",\"iv\":\"0.14\"},"
            + "\"pe\":{\"tradingsymbol\":\"NIFTY19900PE\",\"ltp\":\"60\",\"iv\":\"0.15\"}},"
            // a leg with null iv is dropped (cannot price it)
            + "{\"strike\":\"20000\",\"ce\":{\"tradingsymbol\":\"NIFTY20000CE\",\"ltp\":\"110\",\"iv\":null},"
            + "\"pe\":{\"tradingsymbol\":\"NIFTY20000PE\",\"ltp\":\"120\",\"iv\":\"0.13\"}}]}");

    MarketOiClient.ChainSnapshot snap = client.chain(UNDERLYING).orElseThrow();

    assertThat(snap.expiry()).isEqualTo(EXPIRY);
    assertThat(snap.basis()).isEqualByComparingTo("40"); // forward 20040 − spot 20000
    // 4 legs present, 1 (20000 CE, null iv) dropped → 3 candidates
    assertThat(snap.candidates()).hasSize(3);
    assertThat(snap.candidates())
        .anySatisfy(
            c -> {
              assertThat(c.tradingsymbol()).isEqualTo("NIFTY19900CE");
              assertThat(c.strike()).isEqualByComparingTo("19900");
              assertThat(c.type()).isEqualTo(in.arthayantra.black76.Black76.OptionType.CE);
              assertThat(c.ltp()).isEqualByComparingTo("180");
              assertThat(c.iv()).isEqualByComparingTo("0.14");
            });
    server.verify();
  }

  @Test
  void chainIsEmptyWhenUpstreamFails() {
    wire();
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/market/options/chain")))
        .andRespond(withServerError());

    assertThat(client.chain(UNDERLYING)).isEmpty();
  }

  @Test
  void degradesEveryOiPrimitiveToConservativeDefaultOnUpstreamFailure() {
    wire();
    for (String path :
        new String[] {
          "/api/v1/market/options/spurt",
          "/api/v1/market/futures/banks",
          "/api/v1/market/options/active-strikes",
          "/api/v1/market/options/trending",
          "/api/v1/market/futures/term-structure"
        }) {
      server.expect(ExpectedCount.once(), requestTo(containsString(path))).andRespond(withServerError());
    }

    Oi oi = client.oi(UNDERLYING, EXPIRY);

    // NEUTRAL is bullish()==false AND bearish()==false → cannot confirm either side
    assertThat(oi.underlying()).isEqualTo(OiQuadrant.NEUTRAL);
    assertThat(oi.futures()).isEqualTo(OiQuadrant.NEUTRAL);
    assertThat(oi.underlying().bullish()).isFalse();
    assertThat(oi.underlying().bearish()).isFalse();
    assertThat(oi.sentimentPct()).isNull();
    assertThat(oi.trendingPeMinusCePct()).isNull();
    assertThat(oi.futuresBasis()).isNull();
    server.verify();
  }

  @Test
  void breadthDefaultsToZeroZeroSoTheGateCannotConfirm() {
    wire();
    stub("/api/v1/market/options/iv-history", "{\"currentIv\":\"0.14\",\"rank\":\"0.5\"}");
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/market/breadth")))
        .andRespond(withServerError());
    stub("/api/v1/market/fii-dii/long-short", "{\"items\":[]}");

    Macro m = client.macro(UNDERLYING, TRADE_DATE);

    assertThat(m.advances()).isZero();
    assertThat(m.declines()).isZero();
    // ScalperGates.breadth: count > 32 → 0 > 32 is false for BOTH sides → no false confirmation
    assertThat(ScalperGates.breadth(m, in.arthayantra.black76.Black76.OptionType.CE).pass()).isFalse();
    assertThat(ScalperGates.breadth(m, in.arthayantra.black76.Black76.OptionType.PE).pass()).isFalse();
  }
}
