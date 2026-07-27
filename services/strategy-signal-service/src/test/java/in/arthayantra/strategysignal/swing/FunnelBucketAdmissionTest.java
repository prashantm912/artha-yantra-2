package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.manas.ManasDoctrine;
import in.arthayantra.strategysignal.manas.ManasFunnelClient;
import in.arthayantra.strategysignal.manas.ManasPyramidPolicy;
import in.arthayantra.strategysignal.minervini.MinerviniFunnelClient;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.registry.UniverseResolver;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * The {@code universe.bucket} leaf must mean the SAME thing live as it does at submission.
 *
 * <p>The leaf is schema-declared on {@code minervini_funnel}/{@code manas_arora_funnel} and read by
 * {@link UniverseResolver} (the submission pin), but the live swing batch used to collect BOTH funnel
 * buckets unconditionally — so a {@code bucket: "buyable"} strategy silently traded on-deck names live
 * while its pinned universe excluded them. These tests drive the REAL funnel client → doctrine → {@link
 * SwingBatchEngine} chain off stubbed funnel JSON, which is the only place "this candidate is on-deck"
 * is expressible independently of the fix.
 *
 * <p>The engine's bucket filter is family-NEUTRAL (it lives in the shared {@link SwingBatchEngine}), so
 * one family exercises it end-to-end; the per-family risk is the client's bucket TAGGING, which both
 * clients are asserted on directly.
 */
class FunnelBucketAdmissionTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  // ---- the live-path admission decision (the defect) -------------------------------------------

  @Test
  void buyableOnlyStrategyNeverEntersAnOnDeckCandidate() throws IOException {
    // The defect: live ignored the leaf, so this on-deck name fired for a buyable-only strategy.
    assertThat(entriesFor("buyable", true))
        .as("bucket=buyable excludes on-deck live, exactly as it does in the submission pin")
        .isZero();
  }

  @Test
  void buyableOnlyStrategyStillEntersTheBuyableBucket() throws IOException {
    // Guards the fix against over-filtering: bucket=buyable must still admit the buyable bucket.
    assertThat(entriesFor("buyable", false)).isEqualTo(1);
  }

  @Test
  void buyableOnDeckStrategyEntersAnOnDeckCandidate() throws IOException {
    // THE LIVE NO-OP PROOF: every published+enabled strategy declares buyable_on_deck, so honouring
    // the leaf live changes nothing for any strategy running today.
    assertThat(entriesFor("buyable_on_deck", true)).isEqualTo(1);
  }

  @Test
  void anAbsentBucketDefaultsToAdmittingOnDeck() throws IOException {
    // The schema default is buyable_on_deck — an absent leaf must match the resolver's default.
    assertThat(entriesFor(null, true)).isEqualTo(1);
  }

  // ---- the per-family bucket tagging ------------------------------------------------------------

  @Test
  void theManasClientTagsEachCandidateWithItsFunnelBucket() {
    RestClient.Builder builder = RestClient.builder();
    stubFunnel(builder, "manas-arora", "RELIANCE", "TESTCO");

    List<ManasFunnelClient.Candidate> out =
        new ManasFunnelClient(builder, new ObjectMapper(), "http://market-data:8081")
            .buyableAndOnDeck();

    assertThat(out).extracting(ManasFunnelClient.Candidate::symbol).containsExactly("RELIANCE", "TESTCO");
    assertThat(out).extracting(ManasFunnelClient.Candidate::onDeck).containsExactly(false, true);
  }

  @Test
  void theMinerviniClientTagsEachCandidateWithItsFunnelBucket() {
    RestClient.Builder builder = RestClient.builder();
    stubFunnel(builder, "minervini", "INFY", "WIPRO");

    List<MinerviniFunnelClient.Candidate> out =
        new MinerviniFunnelClient(builder, new ObjectMapper(), "http://market-data:8081")
            .buyableAndOnDeck();

    assertThat(out).extracting(MinerviniFunnelClient.Candidate::symbol).containsExactly("INFY", "WIPRO");
    assertThat(out).extracting(MinerviniFunnelClient.Candidate::onDeck).containsExactly(false, true);
  }

  private static void stubFunnel(RestClient.Builder builder, String family, String buyable, String onDeck) {
    MockRestServiceServer.bindTo(builder)
        .build()
        .expect(requestTo(containsString("/api/v1/market/screener/" + family + "/funnel")))
        .andRespond(withSuccess(funnelJson(family, buyable, onDeck), MediaType.APPLICATION_JSON));
  }

  // ---- harness ---------------------------------------------------------------------------------

  /**
   * Runs one Manas batch over a funnel carrying TESTCO in the {@code onDeck} bucket (or the buyable
   * bucket when {@code candidateOnDeck} is false) against a strategy declaring {@code bucket}, and
   * returns the entries fired. TESTCO's series breaks out above its 150 pivot on the last bar, so the
   * ONLY thing that can suppress the entry is the bucket filter.
   */
  private int entriesFor(String bucket, boolean candidateOnDeck) throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = breakoutConfig(bucket);
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll())
        .thenReturn(
            List.of(
                new StrategyRepository.StrategyRow(
                    strategyId, "manas-arora-breakout", "Manas Breakout", null, null,
                    List.of("manas-arora"), true, publishedVersion, null, null, false, null)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(
            Optional.of(
                new StrategyRepository.VersionRow(
                    publishedVersion, strategyId, "1", null, config, "1", "chk", "published", null,
                    null, null, null)));

    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries()).thenReturn(List.of()); // nothing held → a FRESH entry
    when(signals.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(55L);

    RestClient.Builder builder = RestClient.builder();
    stubFunnel(
        builder, "manas-arora", candidateOnDeck ? "RELIANCE" : "TESTCO",
        candidateOnDeck ? "TESTCO" : "RELIANCE");

    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(breakoutSeries());
    when(candles.fetch(eq("NSE"), eq("RELIANCE"), eq("1d"), any(), any())).thenReturn(List.of());

    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, mock(SignalPublisher.class),
            mock(ApplicationEventPublisher.class), Optional.empty(), passthroughTx(),
            new ObjectMapper(), Clock.systemUTC());
    ManasDoctrine doctrine =
        new ManasDoctrine(
            new ManasFunnelClient(builder, new ObjectMapper(), "http://market-data:8081"), signals,
            new ManasPyramidPolicy(false, new BigDecimal("5.0"), 3, new BigDecimal("6.0")),
            new ObjectMapper(), true, 520, 10, 1440);

    return engine.runDaily(doctrine).entries();
  }

  /** The manas-arora-breakout config with {@code universe.bucket} set to {@code bucket} (null = absent). */
  private static JsonNode breakoutConfig(String bucket) throws IOException {
    try (InputStream in =
        FunnelBucketAdmissionTest.class.getResourceAsStream(
            "/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      JsonNode config =
          StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
      ObjectNode universe = (ObjectNode) config.path("universe");
      if (bucket == null) {
        universe.remove("bucket");
      } else {
        universe.put("bucket", bucket);
      }
      return config;
    }
  }

  /** A funnel response with one name in each bucket (the shape both clients parse). */
  private static String funnelJson(String family, String buyable, String onDeck) {
    String pivots =
        "manas-arora".equals(family)
            ? "\"pivot\":150,\"breakoutPivot\":150,\"setupType\":\"breakout\""
            : "\"pivot\":150,\"thrust\":false,\"stage\":2";
    return "{\"screenDate\":\"2026-07-16\",\"regime\":null,\"immediatelyBuyable\":[{\"symbol\":\""
        + buyable + "\",\"close\":152," + pivots + "}],\"onDeck\":[{\"symbol\":\"" + onDeck
        + "\",\"close\":152," + pivots + "}],\"watch\":[]}";
  }

  /** A 25-bar base that breaks out above the 150 pivot on high volume on the last bar. */
  private static List<EngineCandle> breakoutSeries() {
    double[] tail = {146, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 18; d++) {
      bars.add(bar(d, 100.0 + (149.0 - 100.0) * d / 18.0, 1_000L));
    }
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(19 + i, tail[i], 1_000L));
    }
    bars.add(bar(24, 152.0, 3_000L));
    return bars;
  }

  private static EngineCandle bar(int day, double price, long volume) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal p = BigDecimal.valueOf(price);
    return new EngineCandle(bucket, p, p, p, p, volume, null);
  }

  private static TransactionTemplate passthroughTx() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(inv -> inv.<TransactionCallback<Long>>getArgument(0).doInTransaction(null));
    return tx;
  }
}
