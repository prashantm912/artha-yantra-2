package in.arthayantra.marketdata.options.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class OptionsAnalyticsControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void oiStatsReturnsPcrAndMaxPainEnvelope() throws Exception {
    String u = "CTRLTEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        // BigDecimal serializes as a JSON string (decimal-string wire convention)
        .andExpect(jsonPath("$.pcr").value("1.5000"))
        .andExpect(jsonPath("$.maxPain").value("22500.00"));
  }

  /**
   * A WARMED STOCK chain is candle-derived data that is served TODAY, and live is the DEFAULT mode —
   * so its freshness must read {@code derived}/{@code candle-derived}. {@code oiFreshness} used to
   * carry a {@code !q.live()} conjunct that made {@code derived} structurally unreachable in live
   * mode, so {@code StockChainWarmService}'s {@code source='UPSTOX_1M'} rows (candle-derived, iv
   * null) were published as live captured OI — measured live 2026-08-03 on {@code SBIN}, on both
   * {@code /oi-stats} and {@code /active-strikes}.
   */
  @Test
  void warmedStockChainReadsAsDerivedInLiveMode() throws Exception {
    String u = "FRESHWARMSTOCK";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L, "UPSTOX_1M");
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L, "UPSTOX_1M");

    // live mode = no `mode` param at all (OiQuery.of defaults live=true, date=null)
    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.provenance").value("derived"))
        .andExpect(jsonPath("$.freshness.source").value("candle-derived"));

    mockMvc
        .perform(
            get("/api/v1/market/options/active-strikes")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.provenance").value("derived"))
        .andExpect(jsonPath("$.freshness.source").value("candle-derived"));
  }

  /**
   * The opposite regression the source-based discriminator must not introduce: a genuine LIVE-captured
   * INDEX chain whose legs carry NO iv yet (early session, or a forward the solver could not resolve)
   * must still read {@code live}/{@code capture}. {@code iv == null} is a SYMPTOM of derivation, never
   * its identity — this fixture is byte-identical to {@code warmedStockChainReadsAsDerivedInLiveMode}'s
   * except for the {@code source} label, so it isolates the discriminator itself.
   */
  @Test
  void liveCapturedIndexChainReadsAsCaptureEvenWithNullIv() throws Exception {
    String u = "FRESHLIVEINDEX";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L, "LIVE");
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L, "LIVE");

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.provenance").value("live"))
        .andExpect(jsonPath("$.freshness.source").value("capture"));

    mockMvc
        .perform(
            get("/api/v1/market/options/active-strikes")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.provenance").value("live"))
        .andExpect(jsonPath("$.freshness.source").value("capture"));
  }

  /**
   * MIXED SHAPE 1 (cross-vendor review, #1240) — same bucket, different timestamps, DERIVED wins.
   * {@code series()} folds each (bucket, strike, optionType) with {@code last(…, ts)}, so the 09:25
   * warm row wins the 09:20 5-minute bucket over the 09:24 capture and the response carries derived
   * values. Classifying on "the window holds SOME captured row" answers live here — the same false
   * live label this PR exists to remove, in a shape a homogeneous fixture cannot reach.
   */
  @Test
  void mixedBucketWhereDerivedRowWinsReadsAsDerived() throws Exception {
    String u = "FRESHMIXWIN";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    // Both land in bucket [09:20, 09:25) under end-of-window labelling (ts - 1s).
    OffsetDateTime captured =
        OffsetDateTime.of(2026, 6, 20, 9, 24, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime warmed = captured.plusMinutes(1); // 09:25 -> still bucket 09:20, and NEWER
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, captured, u, exp, "22500", "CE", "100", 1000L, 0L, "LIVE");
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, warmed, u, exp, "22500", "CE", "111", 1111L, 0L, "UPSTOX_1M");

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        // the served OI is the 09:25 warm row's -> the label must follow it
        .andExpect(jsonPath("$.ceOi").value(1111))
        .andExpect(jsonPath("$.freshness.provenance").value("derived"))
        .andExpect(jsonPath("$.freshness.source").value("candle-derived"));
  }

  /**
   * MIXED SHAPE 2 (cross-vendor review, #1240) — one derived LEG beside captured legs in the SAME
   * bucket. Every group is its own {@code last()} fold, so a chain whose 22600 CE is warm-derived is a
   * partly-derived chain however many captured legs sit beside it; only ALL-captured earns {@code live}.
   */
  @Test
  void mixedStrikesWithOneDerivedLegReadsAsDerived() throws Exception {
    String u = "FRESHMIXLEG";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 24, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L, "LIVE");
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L, "LIVE");
    // one warm leg is enough — this strike's group resolves to UPSTOX_1M
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, t0, u, exp, "22600", "CE", "60", 700L, 0L, "UPSTOX_1M");

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ceOi").value(1700)) // both CE legs really are in the fold
        .andExpect(jsonPath("$.freshness.provenance").value("derived"))
        .andExpect(jsonPath("$.freshness.source").value("candle-derived"));
  }

  /**
   * The over-correction guard, and the reason the rule is "the WINNER of each group", not "any derived
   * row taints the window": when the capture arrives LAST it wins its group, so {@code series()}
   * returns captured values and the read genuinely IS a capture. A blunter "any non-captured row =>
   * derived" rule would answer derived here and start under-reporting live data — the mirror of the
   * bug this PR fixes.
   */
  @Test
  void mixedBucketWhereCapturedRowWinsReadsAsCapture() throws Exception {
    String u = "FRESHMIXCAP";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime warmed =
        OffsetDateTime.of(2026, 6, 20, 9, 24, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime captured = warmed.plusMinutes(1); // 09:25 -> same bucket, and NEWER
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, warmed, u, exp, "22500", "CE", "111", 1111L, 0L, "UPSTOX_1M");
    OptionsSnapshotReaderIntegrationTest.insertSourcedRow(
        jdbc, captured, u, exp, "22500", "CE", "100", 1000L, 0L, "LIVE");

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ceOi").value(1000)) // the capture won the fold
        .andExpect(jsonPath("$.freshness.provenance").value("live"))
        .andExpect(jsonPath("$.freshness.source").value("capture"));
  }

  @Test
  void unsupportedIntervalIs400WithCode() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/options/oi-stats").param("name", "X").param("interval", "7m"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_INTERVAL_UNSUPPORTED"));
  }

  @Test
  void historyModeWithoutDateIs400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", "X")
                .param("mode", "history")
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void historyModeWithDateReturnsThatDaysBucket() throws Exception {
    String u = "CTRLHIST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime day1 =
        OffsetDateTime.of(2026, 6, 18, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime day2 =
        OffsetDateTime.of(2026, 6, 19, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, day1, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, day1, u, exp, "22500", "PE", "90", 1500L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, day2, u, exp, "22500", "CE", "999", 9L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("mode", "history")
                .param("date", "2026-06-18")
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ceOi").value(1000))
        .andExpect(jsonPath("$.peOi").value(1500)); // day2's decoy CE excluded
  }

  @Test
  void bigOiRanksByAbsOiChange() throws Exception {
    String u = "BIGOICTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 50L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22600", "PE", "80", 2000L, -900L);

    mockMvc
        .perform(
            get("/api/v1/market/options/big-oi")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].optionType").value("PE")) // |-900| ranks first
        .andExpect(jsonPath("$.items[0].oiChange").value(-900));
  }

  @Test
  void premiumFoldsAtmStraddle() throws Exception {
    String u = "PREMCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "80", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "PE", "70", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/premium")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        // BigDecimal -> string; scale depends on the column, so match the integer part
        .andExpect(jsonPath("$.atmStraddle").value(org.hamcrest.Matchers.startsWith("150")));
  }

  @Test
  void trendingReturnsBucketSeries() throws Exception {
    String u = "TRENDCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    // mid-bucket (legacy-style) captures -> buckets 09:15 and 09:20 under end-of-window labelling
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[1].trend").value("UP")); // 1500 > 1000
  }

  @Test
  void trendingServesTheFullSessionFromTheOpen() throws Exception {
    // Regression for the ~20-bucket window cap: the client folds cumulative Δ columns against the
    // FIRST returned bucket, so a rolling window silently rebased the session-open baseline.
    String u = "TRENDFULLSESS";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime open =
        OffsetDateTime.of(2026, 6, 22, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    // 25 boundary-aligned captures at 09:15..11:15 — state@09:15 (the pre-open carry) labels into
    // the pre-session window under end-of-window labelling (T2) and stays out of the session table.
    for (int i = 0; i < 25; i++) {
      OptionsSnapshotReaderIntegrationTest.insertRow(
          jdbc, open.plusMinutes(5L * i), u, exp, "22500", "CE", "100", 1000L + i, 0L);
    }

    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(24))
        // first row = the real 09:15 open bucket (state@09:20 -> oi 1001), not a mid-session rebase
        .andExpect(jsonPath("$.items[0].totalOi").value(1001));

    // The explicit rolling window the scalper confluence gate depends on (MarketOiClient).
    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("buckets", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(20))
        .andExpect(jsonPath("$.items[0].totalOi").value(1005));
  }

  @Test
  void trendingBasketRestrictsTheFoldAndPeodBaselinePrependsThePreviousSession() throws Exception {
    // Wave-5 parity extras (audit §7): strikes= restricts the fold to a basket; baseline=peod
    // prepends the previous trading session's last bucket so cumulative Δ rebases to prev-day EOD.
    String u = "TRENDPEOD";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime prevEod =
        OffsetDateTime.of(2026, 6, 19, 15, 28, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 22, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, prevEod, u, exp, "22500", "CE", "90", 800L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22600", "CE", "40", 50L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 1500L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22600", "CE", "42", 60L, 0L);

    // chain-wide (no basket): totals include the 22600 leg
    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("date", "2026-06-22")
                .param("mode", "history"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].totalOi").value(1050));

    // basket = 22500 only (normalization: trailing zeros ignored)
    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("date", "2026-06-22")
                .param("mode", "history")
                .param("strikes", "22500.00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].totalOi").value(1000))
        .andExpect(jsonPath("$.items[1].totalOi").value(1500));

    // positional: the previous session's last bucket becomes the first (baseline) row
    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("date", "2026-06-22")
                .param("mode", "history")
                .param("strikes", "22500")
                .param("baseline", "peod"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].totalOi").value(800));

    // unknown baseline value -> 400
    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("baseline", "yesterday"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void strikeSeriesReturnsOnlyTheChosenStrikeBuckets() throws Exception {
    String u = "STRIKESERIES";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5); // distinct 5-min bucket
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 1200L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "PE", "90", 1500L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "PE", "85", 1400L, 0L);
    // decoy strike — must be excluded by the SQL strike filter
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22600", "CE", "5", 50L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-analysis/strike-series")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("strike", "22500"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strike").value("22500"))
        .andExpect(jsonPath("$.interval").value("5m"))
        // 2 buckets × CE+PE = 4 points; the 22600 decoy excluded
        .andExpect(jsonPath("$.items.length()").value(4));
  }

  @Test
  void spurtReturnsRowsAndSummary() throws Exception {
    String u = "SPURTCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 1200L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/spurt")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].optionType").value("CE"))
        .andExpect(jsonPath("$.items[0].interpretation").value("LONG_BUILDUP"))
        .andExpect(jsonPath("$.items[0].oiChange").value(200))
        .andExpect(jsonPath("$.items[0].spurtPct").value("20.00"))
        // ltp 100 -> 110: ltpChangePct = (110-100)/100*100 = 10.00 (BigDecimal -> string)
        .andExpect(jsonPath("$.items[0].ltpChangePct").value("10.00"))
        // the faithful-OI-Spurt fields: prior LTP + absolute LTP change (scale-insensitive prefix)
        .andExpect(jsonPath("$.items[0].prevLtp").value(org.hamcrest.Matchers.startsWith("100")))
        .andExpect(jsonPath("$.items[0].ltpChange").value(org.hamcrest.Matchers.startsWith("10")))
        .andExpect(jsonPath("$.summary.interpretation").value("LONG_BUILDUP"))
        // single spurt row is the representative: oiChangePct=spurtPct, priceChangePct=ltpChangePct
        .andExpect(jsonPath("$.summary.oiChangePct").value("20.00"))
        .andExpect(jsonPath("$.summary.priceChangePct").value("10.00"));
  }

  @Test
  void spurtCumulativeWindowDiffsAgainstTheSessionOpen() throws Exception {
    // F6: three buckets — open (oi 1000 / ltp 100), mid (1500 / 130), newest (1400 / 120). The default
    // INTERVAL window diffs newest vs the PRIOR bucket (mid): OI 1400<1500 + LTP 120<130 => LONG_UNWINDING.
    // The CUMULATIVE window diffs newest vs the session OPEN: OI 1400>1000 + LTP 120>100 => LONG_BUILDUP.
    String u = "SPURTCUM";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, b0.plusMinutes(5), u, exp, "22500", "CE", "130", 1500L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, b0.plusMinutes(10), u, exp, "22500", "CE", "120", 1400L, 0L);

    // default (interval) window: newest vs the prior bucket → LONG_UNWINDING (ΔOI -100).
    mockMvc
        .perform(
            get("/api/v1/market/options/spurt")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("mode", "history")
                .param("date", "2026-06-20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].interpretation").value("LONG_UNWINDING"))
        .andExpect(jsonPath("$.items[0].oiChange").value(-100));

    // cumulative window: newest vs the session OPEN → the quadrant flips to LONG_BUILDUP (ΔOI +400).
    mockMvc
        .perform(
            get("/api/v1/market/options/spurt")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("mode", "history")
                .param("date", "2026-06-20")
                .param("window", "cumulative"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].interpretation").value("LONG_BUILDUP"))
        .andExpect(jsonPath("$.items[0].oiChange").value(400));
  }

  @Test
  void intervalWiseOiRanksTopGainersAndLosers() throws Exception {
    String u = "IWCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(15); // a distinct 15-min bucket
    // 22500 CE: OI 1000 -> 1300 (gainer +300; ltp up + oi up -> LONG_BUILDUP)
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 1300L, 0L);
    // 22600 PE: OI 2000 -> 1500 (loser -500)
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22600", "PE", "80", 2000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22600", "PE", "70", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/interval-wise-oi")
                .param("name", u)
                .param("expiry", "2026-06-25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gainers15[0].strike").value("22500 CE"))
        .andExpect(jsonPath("$.gainers15[0].oiChange").value(300))
        .andExpect(jsonPath("$.gainers15[0].interpretation").value("LONG_BUILDUP"))
        .andExpect(jsonPath("$.losers15[0].strike").value("22600 PE"))
        .andExpect(jsonPath("$.losers15[0].oiChange").value(-500));
  }

  @Test
  void activeStrikesOmitsSeriesWhenBucketsAbsent() throws Exception {
    String u = "ACTIVECTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/active-strikes")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        // buckets absent -> response shape UNCHANGED: NONE of the three series keys present
        .andExpect(jsonPath("$.sentimentSeries").doesNotExist())
        .andExpect(jsonPath("$.activeStrikeOiSeries").doesNotExist())
        .andExpect(jsonPath("$.activeStrikeIvSeries").doesNotExist())
        .andExpect(jsonPath("$.activeStrikeSideIvSeries").doesNotExist());
  }

  @Test
  void activeStrikesAddsSentimentSeriesWhenBucketsPresent() throws Exception {
    String u = "ACTIVESERIES";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    // b0: PE OI-change +300, CE OI-change -200; base 1000+1000 -> 100*500/2000 = 25.00
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, -200L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "PE", "90", 1000L, 300L);
    // b1: PE OI-change -100, CE OI-change +400; base 900+1100 -> 100*-500/2000 = -25.00
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 900L, 400L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "PE", "80", 1100L, -100L);

    mockMvc
        .perform(
            get("/api/v1/market/options/active-strikes")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("buckets", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sentimentSeries.length()").value(2))
        .andExpect(jsonPath("$.sentimentSeries[0].sentimentPct").value("25.00"))
        .andExpect(jsonPath("$.sentimentSeries[1].sentimentPct").value("-25.00")) // newest-last
        // §18.6 level-based number rides the SAME series: b0 (CE 1000/PE 1000) → 0.00; b1 (CE 900/PE
        // 1100) → 100*(1100-900)/1100 = 18.18. Distinct from the ΔOI-flow sentimentPct above.
        .andExpect(jsonPath("$.sentimentSeries[0].levelPct").value("0.00"))
        .andExpect(jsonPath("$.sentimentSeries[1].levelPct").value("18.18"))
        // The LEFT-chart OI series rides the SAME buckets param + same active strike (22500), one DB read.
        .andExpect(jsonPath("$.activeStrikeOiSeries.length()").value(2))
        .andExpect(jsonPath("$.activeStrikeOiSeries[0].ceOi").value(1000))
        .andExpect(jsonPath("$.activeStrikeOiSeries[0].peOi").value(1000))
        .andExpect(jsonPath("$.activeStrikeOiSeries[1].ceOi").value(900))
        .andExpect(jsonPath("$.activeStrikeOiSeries[1].peOi").value(1100));
  }

  @Test
  void activeStrikesAddsIvSeriesWhenBucketsPresent() throws Exception {
    String u = "ACTIVEIV";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    // Single active strike 22500 (iv seeded via the iv-overload): CE IV / PE IV + spot per bucket.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L, 0L, "13.84");
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "PE", "90", 1000L, 0L, 0L, "19.20");
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 900L, 0L, 0L, "12.50");
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "PE", "80", 1100L, 0L, 0L, "20.10");

    mockMvc
        .perform(
            get("/api/v1/market/options/active-strikes")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m")
                .param("buckets", "20"))
        .andExpect(status().isOk())
        // The IV series rides the SAME buckets param; values carry the column scale (iv NUMERIC(12,6),
        // spot_price NUMERIC(18,4) — both seeded by the iv-overload, spot_price=22480.0000).
        .andExpect(jsonPath("$.activeStrikeIvSeries.length()").value(2))
        .andExpect(jsonPath("$.activeStrikeIvSeries[0].ceIv").value("13.840000"))
        .andExpect(jsonPath("$.activeStrikeIvSeries[0].peIv").value("19.200000"))
        .andExpect(jsonPath("$.activeStrikeIvSeries[0].price").value("22480.0000"))
        .andExpect(jsonPath("$.activeStrikeIvSeries[1].ceIv").value("12.500000")) // newest-last
        // §10.2-1: the per-side SPOT-solved series rides the same buckets param (display path only —
        // the stored-iv series above stays byte-identical for the gate's iv_slope input).
        .andExpect(jsonPath("$.activeStrikeSideIvSeries.length()").value(2))
        .andExpect(jsonPath("$.activeStrikeSideIvSeries[0].ceIv").isNotEmpty())
        .andExpect(jsonPath("$.activeStrikeSideIvSeries[0].price").value("22480.0000"));
  }

  @Test
  void strikeSessionStatsPicksAtmSlicesWindowAndGradesOhOl() throws Exception {
    String u = "SESSCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    LocalDate session = LocalDate.of(2026, 6, 19); // Friday (trading day)
    // spot is hardcoded 22480.00 in insertRow -> ATM nearest = 22500.
    OffsetDateTime s0 =
        OffsetDateTime.of(2026, 6, 19, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime s1 = s0.plusMinutes(5);
    OffsetDateTime s2 = s0.plusMinutes(10);

    // ATM 22500 CE: open=100, rises to 140 (high) then last=120 -> NOT open=high.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, s0, u, exp, "22500", "CE", "100.00", 1000L, 0L, 2000L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, s1, u, exp, "22500", "CE", "140.00", 1100L, 0L, 2300L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, s2, u, exp, "22500", "CE", "120.00", 1200L, 0L, 2500L);
    // 22550 CE: open==high (open=200, only falls) -> ohMark true; last=180.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, s0, u, exp, "22550", "CE", "200.00", 800L, 0L, 1000L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, s1, u, exp, "22550", "CE", "180.00", 850L, 0L, 1200L);
    // 22450 CE: in window (window=1 -> 22450,22500,22550).
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, s0, u, exp, "22450", "CE", "300.00", 700L, 0L, 500L);
    // 22600 CE: OUT of window=1 (decoy, must be sliced away).
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, s0, u, exp, "22600", "CE", "10.00", 600L, 0L, 100L);

    // Prior session for prevClose of the ATM CE: newest bucket ltp = 110.
    OffsetDateTime p0 =
        OffsetDateTime.of(2026, 6, 18, 14, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, p0, u, exp, "22500", "CE", "110.00", 900L, 0L, 1500L);

    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", u)
                .param("expiry", "2026-06-25")
                .param("session", "2026-06-19")
                .param("window", "1")
                .param("interval", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value(u))
        .andExpect(jsonPath("$.atmStrike").value(org.hamcrest.Matchers.startsWith("22500")))
        // window=1 -> 3 strikes (22450/22500/22550), CE leg only seeded -> 3 items; 22600 excluded
        .andExpect(jsonPath("$.items.length()").value(3))
        // ATM 22500 CE: open=100 high=140 -> not OH; last=120 from prevClose 110 -> +9.09%
        .andExpect(
            jsonPath("$.items[?(@.strike =~ /22500.*/ && @.optionType == 'CE')].ohMark")
                .value(org.hamcrest.Matchers.contains(false)))
        .andExpect(
            jsonPath("$.items[?(@.strike =~ /22500.*/ && @.optionType == 'CE')].fallPctFromOpen")
                .value(org.hamcrest.Matchers.contains("20.0000")))
        .andExpect(
            jsonPath(
                    "$.items[?(@.strike =~ /22500.*/ && @.optionType == 'CE')].fallPctFromPrevClose")
                .value(org.hamcrest.Matchers.contains("9.0909")))
        // 22550 CE: open==high(200) -> ohMark true; low=180 so open-low=20 > tol -> olMark false
        .andExpect(
            jsonPath("$.items[?(@.strike =~ /22550.*/ && @.optionType == 'CE')].ohMark")
                .value(org.hamcrest.Matchers.contains(true)))
        .andExpect(
            jsonPath("$.items[?(@.strike =~ /22550.*/ && @.optionType == 'CE')].olMark")
                .value(org.hamcrest.Matchers.contains(false)))
        // decimals serialize as JSON strings (open of ATM CE)
        .andExpect(
            jsonPath("$.items[?(@.strike =~ /22500.*/ && @.optionType == 'CE')].open")
                .value(org.hamcrest.Matchers.contains("100.0000")));
  }

  @Test
  void strikeSessionStatsEmptyWindowReturns200WithEmptyItems() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", "SESSEMPTY")
                .param("expiry", "2026-06-25")
                .param("session", "2026-06-19")
                .param("interval", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.atmStrike").value(org.hamcrest.Matchers.nullValue()));
  }

  /**
   * The {@code expiry} param (task_e2e01m(b)) used to be parsed by hand ({@code LocalDate.parse}),
   * so a malformed value threw an uncaught {@code DateTimeParseException} -> the catch-all 500.
   * Typing the {@code @RequestParam} as {@code LocalDate} (matching {@code ExportController}'s
   * "expiry" param convention) makes Spring's own conversion failure map to 400 VALIDATION_FAILED.
   */
  @Test
  void strikeSessionStatsBadExpiryIs400NotNpe() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", "SESSBADEXP")
                .param("expiry", "test"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  /**
   * The {@code session} param (task_7cc34a70) used to be hand-parsed ({@code
   * LocalDate.parse(session)}), so a malformed value threw an uncaught {@code
   * DateTimeParseException} -> the catch-all 500. Retyped to {@code LocalDate} (same {@code expiry}
   * convention above) so Spring's own conversion failure maps to 400 VALIDATION_FAILED.
   */
  @Test
  void strikeSessionStatsMalformedSessionIs400NotServerError() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", "SESSBADSESS")
                .param("expiry", "2026-06-25")
                .param("session", "not-a-date"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  /** Absent {@code session} defaults to today IST — unchanged by the retype (no data -> empty items). */
  @Test
  void strikeSessionStatsAbsentSessionDefaultsToTodayNot400Or500() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", "SESSABSENTSESS")
                .param("expiry", "2026-06-25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  /**
   * A present-but-empty {@code session=} used to hit the SAME hand-rolled {@code LocalDate.parse("")}
   * as a malformed value (both throw {@code DateTimeParseException} -> 500) — unlike {@code farExpiry},
   * {@code session} had no explicit blank guard. Spring's {@code @DateTimeFormat} conversion treats a
   * blank query value as absent (no conversion attempted), so post-fix it now defaults to today exactly
   * like the absent case above, rather than error at all.
   */
  @Test
  void strikeSessionStatsEmptySessionBehavesLikeAbsentNot500() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", "SESSEMPTYSESS")
                .param("expiry", "2026-06-25")
                .param("session", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  /**
   * A negative {@code window} (task_c736e3ca) reached {@link OpenHighStatsService}'s {@code
   * Stream.limit(2*window+1)} unvalidated, so the limit itself went negative and threw an
   * uncaught {@code IllegalArgumentException} -> the catch-all 500. A hand-rolled guard (matching
   * the sibling {@code buckets} check on {@code /trending}) rejects it before the reader/grading
   * path runs.
   */
  @Test
  void strikeSessionStatsNegativeWindowIs400NotServerError() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", "SESSNEGWIN")
                .param("expiry", "2026-06-25")
                .param("window", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  /**
   * {@code window=0} is a genuinely different case from negative. The service computes a radius,
   * {@code 2 * window + 1}, so window=0 reaches {@code Stream.limit(1)} — legal, and it yields just
   * the single nearest (ATM) strike rather than an error. It must NOT be rejected by the
   * negative-window guard above. (An earlier version of this comment said {@code limit(0)}, which
   * would have yielded an EMPTY result and made the assertion below look wrong — cross-vendor review
   * caught the arithmetic.)
   */
  @Test
  void strikeSessionStatsZeroWindowKeepsOnlyAtmStrike() throws Exception {
    String u = "SESSZEROWIN";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime s0 = OffsetDateTime.of(2026, 6, 19, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    // spot is hardcoded 22480.00 in insertRow -> ATM nearest = 22500.
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, s0, u, exp, "22500", "CE", "100.00", 1000L, 0L, 2000L);
    // decoy strike, OUT of window=0 (must be sliced away).
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, s0, u, exp, "22550", "CE", "50.00", 800L, 0L, 1000L);

    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", u)
                .param("expiry", "2026-06-25")
                .param("session", "2026-06-19")
                .param("window", "0")
                .param("interval", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.atmStrike").value(org.hamcrest.Matchers.startsWith("22500")))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].strike").value(org.hamcrest.Matchers.startsWith("22500")));
  }

  /**
   * Recon (task_c736e3ca): unlike {@code window}, a negative {@code interval} needs no new guard.
   * {@code reader.sessionStats(...)} calls {@code OiInterval.parse(intervalMinutes + "m")} as ITS
   * OWN first statement (both the live-snapshot {@code OptionsSnapshotReader} and the
   * candle-derived {@code CandleDerivedChainReader} paths) — before the controller's own later
   * {@code OiInterval.parse} call even runs — so the interval gate already fires first, same 400
   * as {@code /oi-stats}'s {@code unsupportedIntervalIs400WithCode}.
   */
  @Test
  void strikeSessionStatsNegativeIntervalIs400NotServerError() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/strike-session-stats")
                .param("underlying", "SESSNEGIV")
                .param("expiry", "2026-06-25")
                .param("interval", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_INTERVAL_UNSUPPORTED"));
  }

  @Test
  void premiumSeriesTracksAtmStraddlePerBucket() throws Exception {
    String u = "PREMSERIES";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    // one ATM strike per bucket; straddle 150 then 170 (the intraday decay/move curve)
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "80", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "PE", "70", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "PE", "70", 1000L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/premium-series")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].atmStraddle").value(org.hamcrest.Matchers.startsWith("150")))
        .andExpect(jsonPath("$.items[1].atmStraddle").value(org.hamcrest.Matchers.startsWith("170")));
  }

  @Test
  void oiExpiryRollsUpPerStrikeDailyOhlcWithDayOverDayDeltas() throws Exception {
    String u = "OIEXPIRY";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    // Two IST sessions, two captures each → daily OHLC of premium (first/max/min/last per day).
    OffsetDateTime d1a =
        OffsetDateTime.of(2026, 6, 17, 9, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime d1b = d1a.plusHours(3); // same day
    OffsetDateTime d2a =
        OffsetDateTime.of(2026, 6, 18, 9, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime d2b = d2a.plusHours(3);
    // Day 1 CE: open 100, high 120, low 100, close 110, oi(last)=1000.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d1a, u, exp, "22500", "CE", "100", 900L, 0L, 4000L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d1b, u, exp, "22500", "CE", "110", 1000L, 0L, 5000L);
    // Day 2 CE: open 110, high 130, low 105, close 125, oi(last)=1200 → +13.64% close, +20% OI.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d2a, u, exp, "22500", "CE", "130", 1100L, 0L, 6000L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d2b, u, exp, "22500", "CE", "125", 1200L, 0L, 7000L);
    // a PE leg so the strike carries both halves.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d2a, u, exp, "22500", "PE", "90", 1500L, 0L, 3000L);

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-expiry")
                .param("name", u)
                .param("mode", "history")
                .param("date", "2026-06-18")
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].strike").value("22500.00"))
        // CE table newest-first: day 2 row carries the day-over-day deltas (decimal-string wire).
        .andExpect(jsonPath("$.items[0].ce.length()").value(2))
        .andExpect(jsonPath("$.items[0].ce[0].open").value("130.0000"))
        .andExpect(jsonPath("$.items[0].ce[0].high").value("130.0000"))
        .andExpect(jsonPath("$.items[0].ce[0].close").value("125.0000"))
        .andExpect(jsonPath("$.items[0].ce[0].changeInOiPct").value("20.00"))
        .andExpect(jsonPath("$.items[0].ce[0].interpretation").value("LONG_BUILDUP"))
        // the oldest CE row has no prior session → null deltas.
        .andExpect(jsonPath("$.items[0].ce[1].changeInClosePct").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.items[0].pe.length()").value(1));
  }

  @Test
  void oiExpiryEmptyWhenNoSnapshot() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/oi-expiry")
                .param("name", "NOEXPIRYDATA")
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void openHighStrategyGradesThePriorSessionAndReportsTheLiveBreak() throws Exception {
    // foldLive semantics (audit §10.2-8, #465): the O=H/O=L pattern grades on the session BEFORE
    // the viewed day; the viewed day's buckets fill New D.High/Low, the live LTP and the Hit.
    String u = "OHSTRAT";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    // Three IST sessions, two captures each → daily premium OHLC (first/max/min/last per day).
    OffsetDateTime d1a =
        OffsetDateTime.of(2026, 6, 16, 9, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime d2a =
        OffsetDateTime.of(2026, 6, 17, 9, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime d3a =
        OffsetDateTime.of(2026, 6, 18, 9, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    // CE d1: open(first capture)=120 == high → Open=High hit (the one session before the pattern
    // day → probability 1/1 = 100%).
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d1a, u, exp, "22500", "CE", "120", 900L, 0L, 4000L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d1a.plusHours(3), u, exp, "22500", "CE", "100", 1000L, 0L, 5000L);
    // CE d2 (the PATTERN day for a 06-18 view): open=100, high=118 → not Open=High.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d2a, u, exp, "22500", "CE", "100", 1100L, 0L, 6000L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d2a.plusHours(3), u, exp, "22500", "CE", "118", 1150L, 0L, 6500L);
    // CE d3 (the VIEWED day): first bucket 125 > the pattern day's high 118 → Hit at the first
    // bucket; ends 110 → fall% = (110-125)/125 = -12.00.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d3a, u, exp, "22500", "CE", "125", 1200L, 0L, 7000L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d3a.plusHours(3), u, exp, "22500", "CE", "110", 1250L, 0L, 7500L);
    // a PE leg only on the viewed day: no pattern session → live fields only, no Hit.
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d3a, u, exp, "22500", "PE", "90", 1500L, 0L, 3000L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, d3a.plusHours(3), u, exp, "22500", "PE", "130", 1450L, 0L, 3500L);

    mockMvc
        .perform(
            get("/api/v1/market/options/open-high-strategy")
                .param("name", u)
                .param("mode", "history")
                .param("date", "2026-06-18")
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].strike").value("22500.00"))
        .andExpect(jsonPath("$.items[0].ce.optionType").value("CE"))
        // pattern = d2 (the session before the viewed day); d1 is its only prior → 100%.
        .andExpect(jsonPath("$.items[0].ce.latestDate").value("2026-06-17"))
        .andExpect(jsonPath("$.items[0].ce.sessions").value(2))
        .andExpect(jsonPath("$.items[0].ce.ohMark").value(false))
        .andExpect(jsonPath("$.items[0].ce.probability").value("100.00"))
        // the viewed day broke the pattern day's high (125 > 118) at its first bucket.
        .andExpect(jsonPath("$.items[0].ce.triggered").value(true))
        .andExpect(jsonPath("$.items[0].ce.triggeredTime").value("09:15"))
        .andExpect(jsonPath("$.items[0].ce.newDayHigh").value(org.hamcrest.Matchers.startsWith("125")))
        .andExpect(jsonPath("$.items[0].ce.newDayLow").value(org.hamcrest.Matchers.startsWith("110")))
        .andExpect(jsonPath("$.items[0].ce.liveLtp").value(org.hamcrest.Matchers.startsWith("110")))
        .andExpect(jsonPath("$.items[0].ce.fallPctFromHigh").value("-12.00"))
        // the PE leg has no pattern session — live fields only, never a Hit.
        .andExpect(jsonPath("$.items[0].pe.olMark").value(false))
        .andExpect(jsonPath("$.items[0].pe.triggered").value(false))
        .andExpect(jsonPath("$.items[0].pe.liveLtp").value(org.hamcrest.Matchers.startsWith("130")));
  }

  @Test
  void openHighStrategyEmptyWhenNoSnapshot() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/open-high-strategy")
                .param("name", "NOOHDATA")
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  // ── /calendar-spread: farExpiry (task_7cc34a70) ─────────────────────────────────────────────────

  /**
   * {@code farExpiry} used to be hand-parsed ({@code java.time.LocalDate.parse(farExpiry)}), so a
   * malformed value threw an uncaught {@code DateTimeParseException} -> the catch-all 500. Retyped to
   * {@code LocalDate} (same convention as {@code strike-session-stats}'s {@code session}/{@code expiry})
   * so Spring's own conversion failure maps to 400 VALIDATION_FAILED. No option-chain fixture needed —
   * the malformed value fails at argument binding, before the handler body (and its chain lookups) ever runs.
   */
  @Test
  void calendarSpreadMalformedFarExpiryIs400NotServerError() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/calendar-spread")
                .param("name", "CALSPREADBAD")
                .param("strike", "100")
                .param("nearExpiry", "2026-06-16")
                .param("farExpiry", "not-a-date"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  /**
   * Absent {@code farExpiry} -> null -> the service defaults it to "the next listed expiry after near"
   * ({@code CalendarSpreadChartService.nextExpiryAfter}); for an underlying with NO listed expiries at
   * all that resolves to null, which the service's own 400 catches ("farExpiry must be after
   * nearExpiry") — a business-rule 400, not a param-binding one. This is the BASELINE the empty-string
   * case below is compared against.
   */
  @Test
  void calendarSpreadAbsentFarExpiryHitsBusinessValidationNot500() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/calendar-spread")
                .param("name", "CALSPREADABSENT")
                .param("strike", "100")
                .param("nearExpiry", "2026-06-16"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("farExpiry must be after nearExpiry (2026-06-16)"));
  }

  /**
   * The behaviour nuance this task exists to guard: {@code farExpiry=} (present but empty) used to be
   * explicitly blank-tolerant ({@code farExpiry.isBlank() ? null : ...}) and reach the SAME business
   * validation as the absent case above (same 400 message). Retyping to {@code LocalDate} must not turn
   * this into a raw Spring type-mismatch 400 ("Invalid parameter value") instead — that would be a wire
   * regression (right status, wrong reason) hiding behind an unchanged status code. Asserting the exact
   * {@code $.message} (not just the status) is what catches that: a MethodArgumentTypeMismatchException
   * would produce a DIFFERENT message ("Invalid parameter value") from the business-rule one pinned here.
   */
  @Test
  void calendarSpreadEmptyFarExpiryBehavesExactlyLikeAbsent() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/calendar-spread")
                .param("name", "CALSPREADEMPTY")
                .param("strike", "100")
                .param("nearExpiry", "2026-06-16")
                .param("farExpiry", ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("farExpiry must be after nearExpiry (2026-06-16)"));
  }

  /**
   * A syntactically valid, after-near {@code farExpiry} must still parse and pass the near/far
   * validation (unchanged by the retype) — it proceeds to the per-leg instrument lookup, which 422s for
   * this unlisted (underlying, strike) pair. That 422 (DATA_GAP), not a 400, is the tell that farExpiry
   * parsed correctly and cleared the {@code far.isAfter(near)} gate.
   */
  @Test
  void calendarSpreadValidFarExpiryClearsValidationReaches422OnUnlistedStrike() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/calendar-spread")
                .param("name", "CALSPREADVALID")
                .param("strike", "100")
                .param("nearExpiry", "2026-06-16")
                .param("farExpiry", "2026-06-23"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }
}
