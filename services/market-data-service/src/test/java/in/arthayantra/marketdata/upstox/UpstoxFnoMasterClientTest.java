package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Wire-contract test for {@link UpstoxFnoMasterClient}: it fetches the gzipped Upstox instrument master
 * from the assets CDN, gunzips + parses it through the {@link UpstoxInstrumentMaster} anti-corruption
 * DTO, and resolves an ArthaYantra F&amp;O leg ({@code (exchange, underlying, type, expiry, strike)})
 * to the Upstox {@code NSE_FO|<token>} / {@code BSE_FO|<token>} {@code instrument_key}.
 *
 * <p>The fixture mirrors the real master shape (verified 2026-06-24): one NSE_FO FUT + one NSE_FO
 * option + one BSE_FO option, with {@code expiry} as the end-of-day IST epoch-millis and
 * {@code asset_symbol} as the underlying root. A non-F&amp;O row (NSE_INDEX) and an unknown leg both
 * resolve to {@code null} (left to the Kite path).
 */
class UpstoxFnoMasterClientTest {

  private static WireMockServer wireMock;

  private static final String MASTER_PATH = "/market-quote/instruments/exchange/complete.json.gz";

  // 1785263399000 ms = 2026-07-28T23:59:59+05:30 IST → expiry date 2026-07-28
  // 1782844199000 ms = 2026-06-30T23:59:59+05:30 IST → expiry date 2026-06-30
  // ⚠️ EVERY row carries an exchange_token, because every row in the real master does — `computed`
  // 2026-09-03: 117,344 of 117,344, none absent. An earlier version of this fixture gave the token
  // only to the NSE_EQ rows, and that made the NSE_EQ scoping UNTESTABLE: widening the predicate to
  // startsWith("NSE") stayed GREEN, because the extra NSE rows were skipped for a DIFFERENT reason
  // (null token) rather than by the scoping under test. Caught by red-proof, not by review.
  // NSE_INDEX deliberately carries token 1001 — the documented real collision (1001 is both
  // NIFTY 50 and a bond), so the fixture reproduces the hazard the scoping exists to prevent.
  private static final String MASTER_JSON =
      """
      [
        {"segment":"NSE_FO","name":"NIFTY","asset_symbol":"NIFTY","underlying_symbol":"NIFTY",
         "instrument_key":"NSE_FO|61093","instrument_type":"FUT","trading_symbol":"NIFTY FUT 28 JUL 26","exchange_token":"61093",
         "expiry":1785263399000,"strike_price":0.0},
        {"segment":"NSE_FO","name":"NIFTY","asset_symbol":"NIFTY","underlying_symbol":"NIFTY",
         "instrument_key":"NSE_FO|50973","instrument_type":"CE","trading_symbol":"NIFTY 27000 CE 30 JUN 26","exchange_token":"50973",
         "expiry":1782844199000,"strike_price":27000.0,"lot_size":50},
        {"segment":"BSE_FO","name":"SENSEX","asset_symbol":"SENSEX","underlying_symbol":"SENSEX",
         "instrument_key":"BSE_FO|1174631","instrument_type":"PE","trading_symbol":"SENSEX 67900 PE 30 JUN 26","exchange_token":"1174631",
         "expiry":1782844199000,"strike_price":67900.0},
        {"segment":"NSE_INDEX","name":"NIFTY 50","asset_symbol":null,"underlying_symbol":null,
         "instrument_key":"NSE_INDEX|Nifty 50","instrument_type":"INDEX","trading_symbol":"Nifty 50","exchange_token":"1001",
         "expiry":null,"strike_price":0.0},
        {"segment":"NSE_EQ","name":"RELIANCE INDUSTRIES","instrument_key":"NSE_EQ|INE002A01018",
         "instrument_type":"EQ","trading_symbol":"RELIANCE","exchange_token":"2885",
         "isin":"INE002A01018","expiry":null,"strike_price":0.0},
        {"segment":"NSE_EQ","name":"749RJ35","instrument_key":"NSE_EQ|IN2920250163",
         "instrument_type":"SG","trading_symbol":"749RJ35","exchange_token":"758718",
         "isin":"IN2920250163","expiry":null,"strike_price":0.0},
        {"segment":"GLOBAL_INDEX","name":"DOW JONES","instrument_key":"GLOBAL_INDEX|DJI",
         "instrument_type":"INDEX","trading_symbol":"DJI","exchange_token":"",
         "expiry":null,"strike_price":0.0}
      ]
      """;

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
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/gzip")
                    .withBody(gzip(MASTER_JSON))));
  }

  private static UpstoxFnoMasterClient client() {
    return client(Clock.systemUTC());
  }

  private static UpstoxFnoMasterClient client(Clock clock) {
    return new UpstoxFnoMasterClient(
        RestClient.builder(),
        new ObjectMapper(),
        new UpstoxAnalyticsProperties(null, null, null, wireMock.baseUrl()),
        clock);
  }

  @Test
  void resolvesAFutureLegToItsUpstoxKey() {
    UpstoxFnoMasterClient client = client();

    String key = client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null);

    assertThat(key).isEqualTo("NSE_FO|61093");
    // ONE master fetch from the assets CDN.
    wireMock.verify(getRequestedFor(urlPathEqualTo(MASTER_PATH)));
  }

  @Test
  void resolvesAnNseOptionLegToItsUpstoxKey() {
    String key =
        client().keyFor("NFO", "NIFTY", "CE", LocalDate.of(2026, 6, 30), new BigDecimal("27000"));

    assertThat(key).isEqualTo("NSE_FO|50973");
  }

  @Test
  void resolvesABseOptionLegToItsUpstoxKey() {
    String key =
        client()
            .keyFor("BFO", "SENSEX", "PE", LocalDate.of(2026, 6, 30), new BigDecimal("67900.00"));

    assertThat(key).as("decimal-scaled strike matches the master row").isEqualTo("BSE_FO|1174631");
  }

  @Test
  void resolveExposesTheMasterLotSizeAlongsideTheKey() {
    // resolve() returns the tradable lot from the master row (lot_size:50) so callers (the margin
    // drift-probe) size the basket off the master, not a hardcode that goes stale on an NSE lot change.
    UpstoxFnoMasterClient.FnoLeg leg =
        client().resolve("NFO", "NIFTY", "CE", LocalDate.of(2026, 6, 30), new BigDecimal("27000"));

    assertThat(leg).isNotNull();
    assertThat(leg.instrumentKey()).isEqualTo("NSE_FO|50973");
    assertThat(leg.lotSize()).isEqualTo(50);
  }

  @Test
  void resolveLotIsNullWhenTheMasterRowOmitsLotSize() {
    // The FUT row carries no lot_size — resolve() still returns the key, with a null lot (the caller
    // then falls back to its documented default). keyFor stays unaffected.
    UpstoxFnoMasterClient client = client();

    UpstoxFnoMasterClient.FnoLeg leg =
        client.resolve("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null);

    assertThat(leg).isNotNull();
    assertThat(leg.instrumentKey()).isEqualTo("NSE_FO|61093");
    assertThat(leg.lotSize()).isNull();
    // keyFor still returns the same key (signature + behaviour unchanged for its live callers).
    assertThat(client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null))
        .isEqualTo("NSE_FO|61093");
  }

  @Test
  void unknownLegAndWrongStrikeResolveToNull() {
    UpstoxFnoMasterClient client = client();

    // right contract, wrong strike
    assertThat(client.keyFor("NFO", "NIFTY", "CE", LocalDate.of(2026, 6, 30), new BigDecimal("28000")))
        .isNull();
    // unknown underlying
    assertThat(client.keyFor("NFO", "BANKNIFTY", "FUT", LocalDate.of(2026, 7, 28), null)).isNull();
    // non-F&O exchange is never looked up
    assertThat(client.keyFor("NSE", "NIFTY 50", "EQ", LocalDate.of(2026, 7, 28), null)).isNull();
  }

  @Test
  void aFailedOrMalformedMasterFetchDegradesToNullNeverThrows() {
    // The enrichment must never break the source: a 5xx / non-gzip body returns null (Kite path stays
    // the source), it does NOT propagate out of the lookup.
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(aResponse().withStatus(500).withBody("upstream error")));

    assertThat(client().keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null)).isNull();

    // a body that is not valid gzip also degrades to null rather than throwing
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH)).willReturn(aResponse().withBody("not-gzip-bytes")));
    assertThat(client().keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null)).isNull();
  }

  @Test
  void cachesTheMasterAcrossLookups() {
    UpstoxFnoMasterClient client = client();

    client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null);
    client.keyFor("NFO", "NIFTY", "CE", LocalDate.of(2026, 6, 30), new BigDecimal("27000"));

    // The master is loaded ONCE and reused — no per-lookup re-fetch.
    assertThat(wireMock.getAllServeEvents()).hasSize(1);
  }

  @Test
  void warmLoadsTheMasterSoTheNextLookupPaysNothing() {
    // The point of the warm: the 5MB cold load happens OFF the caller's path, so the lookup that a
    // live margin call makes (through a 2000ms budget) finds the cache already populated.
    UpstoxFnoMasterClient client = client();

    client.warm();
    assertThat(wireMock.getAllServeEvents()).as("the warm itself fetched the master").hasSize(1);

    String key = client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null);

    assertThat(key).isEqualTo("NSE_FO|61093");
    assertThat(wireMock.getAllServeEvents())
        .as("the lookup re-used the warmed cache — no second fetch on the caller's thread")
        .hasSize(1);
  }

  @Test
  void failedWarmNeverThrowsAndLeavesTheLookupWorking() {
    // The assets CDN is auth-free but can be down. A failed warm must cost nothing beyond the
    // pre-existing lazy behaviour — never propagate out of startup or the scheduler.
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(aResponse().withStatus(503).withBody("cdn down")));
    UpstoxFnoMasterClient client = client();

    assertThatCode(client::warm).doesNotThrowAnyException();

    // ...and the client still answers, degrading to null exactly as it did before the warm existed.
    assertThat(client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null)).isNull();
  }

  @Test
  void failedEmptyWarmIsRetriedByALaterLookupAfterTheBackoff() {
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(aResponse().withStatus(503).withBody("cdn down")));
    MutableClock clock = new MutableClock(Instant.parse("2026-08-08T03:30:00Z"));
    UpstoxFnoMasterClient client = client(clock);

    assertThat(client.warm()).isFalse();

    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/gzip")
                    .withBody(gzip(MASTER_JSON))));
    clock.advance(UpstoxFnoMasterClient.RETRY_BACKOFF);

    assertThat(client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null))
        .as("a failed empty load must be retried by a later lookup")
        .isEqualTo("NSE_FO|61093");
    wireMock.verify(getRequestedFor(urlPathEqualTo(MASTER_PATH)));
  }

  /**
   * A payload that parses cleanly but maps ZERO F&O legs must NOT replace a good cache — the
   * catalogued "success-shaped nothing" shape, and cross-vendor review found it live here
   * (2026-08-10). Upstox serving a truncated file, or a schema change that makes the F&O predicate
   * match nothing, both land on this path. Before the fix it assigned {@code {}} into the live
   * cache, stamped {@code loadedAt} fresh and returned true — after which every instrument-key
   * lookup missed and every margin call returned {@code unpriced} for a full refresh interval, with
   * the warmer logging success throughout.
   */
  @Test
  void aMasterThatMapsZeroLegsIsRefusedAndTheGoodCacheSurvives() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-08T03:30:00Z"));
    UpstoxFnoMasterClient client = client(clock);
    assertThat(client.warm()).as("the good master loads first").isTrue();

    // Now Upstox serves a well-formed master with no F&O rows at all.
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/gzip")
                    .withBody(gzip("[]"))));
    clock.advance(UpstoxFnoMasterClient.REFRESH);

    assertThat(client.warm()).as("an empty index is a FAILED warm, not a successful one").isFalse();
    assertThat(client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null))
        .as("the previously good cache must survive the empty payload")
        .isEqualTo("NSE_FO|61093");
  }

  /**
   * A FAILED load must not buy the stale cache another refresh interval. {@code loadedAt} used to be
   * advanced on failure whenever a prior cache existed, which is the "cache stores a FAILURE with a
   * fresh timestamp" shape: a source broken for hours read as freshly warmed, and the next lookup
   * would not retry until a whole REFRESH had passed. {@code lastAttemptAt} + backoff is the only
   * thing that should bound retries.
   */
  @Test
  void aFailedLoadDoesNotExtendTheLifeOfTheCacheItKept() {
    // ⚠️ This MUST drive the LAZY path (keyFor -> cache() -> loadDue), not warm(). warm() calls
    // reload() unconditionally, so it cannot observe loadedAt at all — a first draft of this test
    // used warm() and stayed GREEN against the very defect it was written for. The bug lives
    // entirely in loadDue's arithmetic, and only a lookup consults it.
    MutableClock clock = new MutableClock(Instant.parse("2026-08-08T03:30:00Z"));
    UpstoxFnoMasterClient client = client(clock);
    assertThat(client.warm()).as("the good master loads first").isTrue();
    wireMock.resetAll();

    // The refresh window lapses, upstream is down, and a LOOKUP triggers the lazy reload.
    clock.advance(UpstoxFnoMasterClient.REFRESH);
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(aResponse().withStatus(503).withBody("cdn down")));
    assertThat(client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null))
        .as("the kept cache still answers while upstream is down")
        .isEqualTo("NSE_FO|61093");
    wireMock.verify(1, getRequestedFor(urlPathEqualTo(MASTER_PATH)));

    // Upstream recovers. Only the short RETRY_BACKOFF should stand between us and a fresh fetch —
    // NOT another full REFRESH, which is exactly what advancing loadedAt on a FAILURE imposed.
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/gzip")
                    .withBody(gzip(MASTER_JSON))));
    clock.advance(UpstoxFnoMasterClient.RETRY_BACKOFF);
    client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null);

    // The observable is the REQUEST, not the returned key: the kept cache answers correctly either
    // way, so a value assertion here would pass against the defect.
    wireMock.verify(
        1,
        getRequestedFor(urlPathEqualTo(MASTER_PATH)));
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // H26 U-A2 — the NSE cash index. ⚠️ Before these existed the fixture carried NO NSE_EQ row at all,
  // so indexNseCash() returned empty on every load in the whole suite and only its "mapped ZERO"
  // WARN branch was ever executed. The five shipped tests exercised a one-line ternary and nothing
  // else — including neither of the two DTO fields this unit was built to add.
  // -----------------------------------------------------------------------------------------------

  @Test
  void indexesNseCashScopedToTheEqSegmentAndExcludesEveryOther() {
    // Two NSE_EQ rows out of six. ⚠️ This is the assertion that pins the scoping the javadoc calls
    // load-bearing: widen the predicate to startsWith("NSE") and NSE_INDEX/NSE_FO join the index,
    // which is exactly the token collision the scoping exists to stop (token 1001 is both NIFTY 50
    // and a bond). Before this test, that widening passed every test in the file.
    assertThat(client().nseCashSize()).isEqualTo(2);
  }

  @Test
  void deserializesTheExchangeTokenAndIsinIdentityFields() {
    // ⚠️ The defect this pins really happened on this very branch: both fields were absent from the
    // DTO while a receipt claimed they were "already parsed", and only the compiler caught it.
    // Untested, the same regression returns SILENTLY — a dropped field yields null, and a null
    // token is skipped by indexNseCash rather than failing.
    UpstoxFnoMasterClient.NseCashIdentity reliance = client().nseCashIdentity(2885L);

    assertThat(reliance).isNotNull();
    assertThat(reliance.upstoxInstrumentKey()).isEqualTo("NSE_EQ|INE002A01018");
    assertThat(reliance.isin()).isEqualTo("INE002A01018");
    assertThat(reliance.upstoxTradingSymbol()).isEqualTo("RELIANCE");
    assertThat(reliance.series()).isEqualTo("EQ");
    assertThat(reliance.derivedKiteTradingsymbol())
        .as("EQ is the one series Kite leaves bare")
        .isEqualTo("RELIANCE");
  }

  @Test
  void derivesTheSuffixedKiteSymbolThroughTheRealParse() {
    // The ternary is trivially unit-testable; what was NOT covered is that it is reached with the
    // series Jackson actually produced from instrument_type, through the real payload.
    assertThat(client().nseCashIdentity(758718L).derivedKiteTradingsymbol()).isEqualTo("749RJ35-SG");
  }

  @Test
  void parsesAnEmptyExchangeTokenAsNullWithoutBreakingTheLoad() {
    // ⚠️ exchange_token is a JSON STRING on the wire, and 13 live rows carry "" (10 GLOBAL_INDEX +
    // 3 GLOBAL_INDICATOR — computed 2026-09-03 over all 117,344 rows). Mapping the field moved it
    // out of the ignoreUnknown bucket, so its coercion to null is now load-bearing for the WHOLE
    // parse: were it to throw, reload() would keep the prior cache and never warm again. The
    // coercion is a global Jackson default this class does not own, which is exactly why it is
    // pinned here rather than assumed.
    UpstoxFnoMasterClient client = client();

    assertThat(client.nseCashSize()).as("the load survived the empty token").isEqualTo(2);
    assertThat(client.keyFor("NFO", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null))
        .as("and the F&O index still warmed from the same payload")
        .isEqualTo("NSE_FO|61093");
  }

  private static byte[] gzip(String json) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
