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
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
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
        .andExpect(jsonPath("$.activeStrikeIvSeries").doesNotExist());
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
        .andExpect(jsonPath("$.activeStrikeIvSeries[1].ceIv").value("12.500000")); // newest-last
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
}
