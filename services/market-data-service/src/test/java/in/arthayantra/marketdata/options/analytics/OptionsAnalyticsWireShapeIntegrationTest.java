package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * D3 Map-return burn-down WIRE GUARD for the four converted {@code /market/options} handlers
 * ({@code /oi-analysis}, {@code /oi-analysis/strike-series}, {@code /multiple-oi},
 * {@code /options-chart}).
 *
 * <p>Each handler used to return a {@code Map<String, Object>} — invisible to the contract gate, so
 * nothing anywhere pinned its emitted keys. The conversion to a typed record is only safe if the
 * wire is unmoved, and "the existing ITs still pass" does NOT prove that: they assert a handful of
 * VALUES by JSON path and would stay green if a key were added, dropped or renamed around them.
 *
 * <p>So this test asserts the EXACT ORDERED key list of every response object and of every item
 * object inside it. The literals below were captured by running this class against the pre-D3
 * {@code Map}/{@code LinkedHashMap} implementation and are unchanged by the conversion — that
 * equality, before and after, is the proof the wire did not move.
 *
 * <p>Two of the six Map handlers on this controller are DELIBERATELY not converted and so are not
 * guarded here: {@code /oi-expiry} and {@code /open-high-strategy} emit FEWER keys on their empty
 * path than when populated (3 vs 4 and 3 vs 5), so a record would add always-present nulls to the
 * empty response — a wire change, not a retyping. See {@code MapReturnRatchetTest}.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class OptionsAnalyticsWireShapeIntegrationTest extends MarketDataIntegrationTestBase {

  /** Unique to this class — ITs share the singleton DB with no per-method cleanup. */
  private static final String U = "WIRESHAPEOI";

  private static final LocalDate EXP = LocalDate.of(2026, 6, 25);
  private static final String SESSION = "2026-06-20";
  private static final OffsetDateTime B0 =
      OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final OffsetDateTime B1 = B0.plusMinutes(5);

  /** The chain used by the options-chart leg lookup (needs real listed instruments). */
  private static final String CHART_UNDERLYING = "NIFTY 50";

  private static final LocalDate CHART_EXPIRY = LocalDate.parse("2026-06-16");
  private static final String CHART_SESSION = "2026-06-15";

  /** {@code OptionsSnapshotReader.StrikePoint} — record component order IS the emitted order. */
  private static final List<String> STRIKE_POINT_KEYS =
      List.of("bucket", "strike", "optionType", "ltp", "oi", "oiChange", "iv", "spot", "volume");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private InstrumentRepository instruments;

  private void seed() {
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, B0, U, EXP, "57200", "CE", "100", 134820L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, B1, U, EXP, "57200", "CE", "105", 140000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, B0, U, EXP, "57100", "PE", "90", 90450L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, B1, U, EXP, "57100", "PE", "92", 92000L, 0L);
  }

  // ── /oi-analysis ────────────────────────────────────────────────────────────────────────────

  @Test
  void oiAnalysisEmitsTheItemsEnvelopeOfFullStrikePoints() throws Exception {
    seed();
    JsonNode body = getJson("/api/v1/market/options/oi-analysis", req -> req);
    assertThat(keys(body)).containsExactly("items");
    assertThat(body.get("items")).isNotEmpty();
    assertThat(keys(body.get("items").get(0))).containsExactlyElementsOf(STRIKE_POINT_KEYS);
  }

  // ── /oi-analysis/strike-series ──────────────────────────────────────────────────────────────

  @Test
  void strikeSeriesEmitsItsSixKeysAndFullStrikePointItems() throws Exception {
    seed();
    JsonNode body =
        getJson("/api/v1/market/options/oi-analysis/strike-series", req -> req.param("strike", "57200"));
    // Source was a 6-key Map.of, whose iteration order is JVM-salted — the record NORMALISES the
    // order rather than preserving one, so only the key SET is a before/after invariant here.
    assertThat(keys(body))
        .containsExactlyInAnyOrder("items", "underlying", "expiry", "strike", "interval", "asOf");
    assertThat(body.get("items")).isNotEmpty();
    assertThat(keys(body.get("items").get(0))).containsExactlyElementsOf(STRIKE_POINT_KEYS);
    // Values that must survive the retyping unchanged.
    assertThat(body.get("underlying").asText()).isEqualTo(U);
    assertThat(body.get("expiry").asText()).isEqualTo(EXP.toString());
    assertThat(body.get("interval").asText()).isEqualTo("5m");
    assertTextual(body, "strike");
    assertThat(new BigDecimal(body.get("strike").asText())).isEqualByComparingTo("57200");
  }

  // ── /multiple-oi ────────────────────────────────────────────────────────────────────────────

  @Test
  void multipleOiPreservesItsLinkedHashMapKeyOrderAndItemShapes() throws Exception {
    seed();
    JsonNode body =
        getJson("/api/v1/market/options/multiple-oi", req -> req.param("leg", "57200 CE", "57100 PE"));
    // Source was a LinkedHashMap — insertion order WAS the emitted order, so it is load-bearing
    // here (unlike the Map.of cases) and the record components mirror it exactly.
    assertThat(keys(body))
        .containsExactly("items", "spot", "underlying", "expiry", "interval", "asOf");
    assertThat(keys(body.get("items").get(0))).containsExactly("leg", "points");
    assertThat(keys(body.get("items").get(0).get("points").get(0))).containsExactly("bucket", "oi");
    assertThat(keys(body.get("spot").get(0))).containsExactly("bucket", "spot");
  }

  /**
   * The nullable leg point stays PRESENT-AND-NULL, not omitted. Jackson's default inclusion is
   * ALWAYS (no global {@code default-property-inclusion} in this service), so the old anonymous
   * map and the new record both emit {@code "oi": null} — this pins that the retyping did not
   * silently start omitting the key.
   */
  @Test
  void anUnseededLegEmitsAnExplicitNullOiRatherThanOmittingIt() throws Exception {
    seed();
    JsonNode body =
        getJson("/api/v1/market/options/multiple-oi", req -> req.param("leg", "99999 CE"));
    JsonNode point = body.get("items").get(0).get("points").get(0);
    assertThat(keys(point)).containsExactly("bucket", "oi");
    assertThat(point.has("oi")).isTrue();
    assertThat(point.get("oi").isNull()).isTrue();
  }

  // ── /options-chart ──────────────────────────────────────────────────────────────────────────

  @Test
  void optionsChartPreservesItsLinkedHashMapKeyOrderAndCandleShape() throws Exception {
    syncService.runSync();
    BigDecimal strike = midChartStrike();
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc,
        OffsetDateTime.parse(CHART_SESSION + "T09:16:40+05:30"),
        CHART_UNDERLYING,
        CHART_EXPIRY,
        strike.toPlainString(),
        "CE",
        "100.00",
        137250L,
        0L,
        1000L,
        "13.03");

    JsonNode body = chartJson(strike);

    // Source was a LinkedHashMap listing ce/pe FIRST — deliberately NOT the declaration order of
    // the service record, whose components were reordered to match this emitted order.
    assertThat(keys(body))
        .containsExactly(
            "ce",
            "pe",
            "underlying",
            "expiry",
            "strike",
            "ceTradingsymbol",
            "peTradingsymbol",
            "interval",
            "underlyingLtp",
            "underlyingDayOpen",
            "asOf");
    assertThat(body.get("ce")).isNotEmpty();
    assertThat(keys(body.get("ce").get(0)))
        .containsExactly("time", "open", "high", "low", "close", "volume", "oi", "iv");
  }

  // ── scalar wire TYPES (cross-vendor review, D3) ─────────────────────────────────────────────

  /**
   * Pins the JSON SCALAR TYPE of every decimal these newly-enumerated schemas publish.
   *
   * <p>{@code ArthaJacksonAutoConfiguration} registers {@code ToStringSerializer} for {@code
   * BigDecimal} platform-wide, so every decimal is a JSON STRING. Bare springdoc infers {@code
   * number} from the Java type, so the first cut of this conversion published {@code number} for
   * all of them — the spec asserted a type the service never emits, and the generated TS client
   * inherited it. Making an opaque Map visible has to make the claim TRUE, not merely present.
   *
   * <p>The key-set tests above CANNOT catch that: {@code asText()} returns {@code "57200"} for a
   * textual node AND for a numeric one, so a silent retype passes them. These assertions use
   * {@code isTextual()}/{@code isNumber()}, which do not.
   */
  @Test
  void everyPublishedDecimalIsTextualAndEveryCountIsNumeric() throws Exception {
    seed();

    JsonNode point = getJson("/api/v1/market/options/oi-analysis", r -> r).get("items").get(0);
    assertTextual(point, "strike");
    assertTextual(point, "ltp");
    assertTextual(point, "spot"); // insertRow hardcodes spot_price = 22480.00
    assertNumeric(point, "oi");
    assertNumeric(point, "oiChange");

    JsonNode series =
        getJson("/api/v1/market/options/oi-analysis/strike-series", r -> r.param("strike", "57200"));
    assertTextual(series, "strike");
    assertTextual(series.get("items").get(0), "strike");

    JsonNode multi =
        getJson("/api/v1/market/options/multiple-oi", r -> r.param("leg", "57200 CE"));
    assertTextual(multi.get("spot").get(0), "spot");
    assertNumeric(multi.get("items").get(0).get("points").get(0), "oi");
  }

  /** The same pin for the options-chart candle series, which needs the synced instrument chain. */
  @Test
  void optionsChartPublishesPremiumOhlcAndIvAsTextualNodes() throws Exception {
    syncService.runSync();
    BigDecimal strike = midChartStrike();
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc,
        OffsetDateTime.parse(CHART_SESSION + "T09:16:40+05:30"),
        CHART_UNDERLYING,
        CHART_EXPIRY,
        strike.toPlainString(),
        "CE",
        "100.00",
        137250L,
        0L,
        1000L,
        "13.03");

    JsonNode body = chartJson(strike);
    assertTextual(body, "strike");
    // Null off-hours when the mock quote does not resolve — but never a NUMBER either way.
    assertTextualOrNull(body, "underlyingLtp");
    assertTextualOrNull(body, "underlyingDayOpen");

    JsonNode candle = body.get("ce").get(0);
    for (String ohlc : List.of("open", "high", "low", "close")) {
      assertTextual(candle, ohlc);
    }
    assertNumeric(candle, "volume"); // primitive long — a genuine JSON number
    assertNumeric(candle, "oi"); // Long — also genuinely numeric
    assertTextual(candle, "iv");
  }

  private static void assertTextual(JsonNode owner, String field) {
    JsonNode n = owner.get(field);
    assertThat(n).as("%s present", field).isNotNull();
    assertThat(n.isTextual())
        .as("%s must be a JSON string (BigDecimal rides ToStringSerializer), was %s", field, n)
        .isTrue();
  }

  private static void assertTextualOrNull(JsonNode owner, String field) {
    JsonNode n = owner.get(field);
    assertThat(n).as("%s present", field).isNotNull();
    assertThat(n.isTextual() || n.isNull())
        .as("%s must be a JSON string or null, never a number, was %s", field, n)
        .isTrue();
  }

  private static void assertNumeric(JsonNode owner, String field) {
    JsonNode n = owner.get(field);
    assertThat(n).as("%s present", field).isNotNull();
    assertThat(n.isNumber()).as("%s must be a JSON number, was %s", field, n).isTrue();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────

  /** Ordered field names of a JSON object — the emitted key list this test exists to pin. */
  private static List<String> keys(JsonNode node) {
    assertThat(node).isNotNull();
    assertThat(node.isObject()).as("expected a JSON object, got %s", node.getNodeType()).isTrue();
    List<String> out = new ArrayList<>();
    node.fieldNames().forEachRemaining(out::add);
    return out;
  }

  /** A listed strike near mid-ladder of the synced chain (CE+PE both present). */
  private BigDecimal midChartStrike() {
    List<BigDecimal> strikes = instruments.strikes(CHART_UNDERLYING, CHART_EXPIRY);
    assertThat(strikes).isNotEmpty();
    return strikes.get(strikes.size() / 2);
  }

  /** Reads {@code /options-chart} for {@code strike} over the synced chain's session. */
  private JsonNode chartJson(BigDecimal strike) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/market/options/options-chart")
                    .param("name", CHART_UNDERLYING)
                    .param("mode", "history")
                    .param("date", CHART_SESSION)
                    .param("expiry", CHART_EXPIRY.toString())
                    .param("strike", strike.toPlainString())
                    .param("interval", "5"))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  /** Performs a history-mode read of {@code path} against the seeded synthetic chain. */
  private JsonNode getJson(String path, UnaryOperator<MockHttpServletRequestBuilder> extra)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                extra.apply(
                    get(path)
                        .param("name", U)
                        .param("expiry", EXP.toString())
                        .param("mode", "history")
                        .param("date", SESSION)
                        .param("interval", "5m")))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }
}
