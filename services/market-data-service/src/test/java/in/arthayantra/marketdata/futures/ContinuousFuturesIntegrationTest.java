package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.candles.RollEventsRepository;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-15B IT: the mock FUT ladder's roll boundary produces exactly one {@code roll_events} row
 * with the fixture gap; the CONT stitch is continuous and UNADJUSTED at rest; {@code adjust=back}
 * shifts pre-roll bars by the cumulative gap at read time; the SYN-CONT row survives re-syncs;
 * the D10 grant pattern covers {@code roll_events}. Deterministic across repeated runs.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.futures.underlyings=NIFTY 50"
    })
@AutoConfigureMockMvc
class ContinuousFuturesIntegrationTest extends MarketDataIntegrationTestBase {

  private static final Instant ROLL_DAY = OffsetDateTime.parse("2026-06-24T17:00:00+05:30").toInstant();
  private static final Instant EXPIRY_DAY = OffsetDateTime.parse("2026-06-25T17:00:00+05:30").toInstant();
  private static final AtomicReference<Instant> NOW = new AtomicReference<>(ROLL_DAY);

  @TestConfiguration
  static class MutableClockConfig {
    @Bean
    @Primary
    Clock mutableClock() {
      return new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return NOW.get();
        }
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private InstrumentRepository instruments;
  @Autowired private CandleRepository candles;
  @Autowired private RollEventsRepository rollEvents;
  @Autowired private ContinuousFuturesRoller roller;
  @Autowired private JdbcTemplate jdbc;

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  private void seedDaily(String symbol, String date, String close) {
    BigDecimal c = new BigDecimal(close);
    candles.upsert(
        new Candle("NFO", symbol, "1d", ist(date + "T00:00:00"), c, c, c, c, 100, 50_000L, "MOCK"));
  }

  @BeforeEach
  void cleanSlateAndSeed() {
    NOW.set(ROLL_DAY);
    syncService.runSync();
    jdbc.update("DELETE FROM roll_events WHERE underlying = 'NIFTY 50'");
    jdbc.update("DELETE FROM candles WHERE tradingsymbol = 'NIFTY-FUT-CONT'");
    jdbc.update(
        "DELETE FROM candles WHERE tradingsymbol IN ('NIFTY26JUNFUT','NIFTY26JULFUT') AND \"interval\" = '1d'");
    // the hand fixture around the JUN→JUL roll boundary (expiry 2026-06-25, roll 2026-06-24)
    seedDaily("NIFTY26JUNFUT", "2026-06-22", "24100.0000");
    seedDaily("NIFTY26JUNFUT", "2026-06-23", "24150.0000");
    seedDaily("NIFTY26JUNFUT", "2026-06-24", "24200.0000");
    seedDaily("NIFTY26JULFUT", "2026-06-23", "24290.0000");
    seedDaily("NIFTY26JULFUT", "2026-06-24", "24350.0000");
    seedDaily("NIFTY26JULFUT", "2026-06-25", "24400.0000");
  }

  @Test
  void rollBoundaryProducesExactlyOneEventAndAContinuousStitch() {
    roller.rollNow(); // 2026-06-24 — the roll date

    List<RollEventsRepository.RollEvent> rolls = rollEvents.rollsFor("NIFTY 50");
    assertThat(rolls).hasSize(1);
    assertThat(rolls.get(0).fromTradingsymbol()).isEqualTo("NIFTY26JUNFUT");
    assertThat(rolls.get(0).toTradingsymbol()).isEqualTo("NIFTY26JULFUT");
    assertThat(rolls.get(0).priceGap()).isEqualByComparingTo("150.0000"); // 24350 − 24200

    NOW.set(EXPIRY_DAY);
    roller.rollNow(); // the day after the roll: JUL is active, bar 25 joins the stitch
    roller.rollNow(); // determinism: a re-run appends nothing

    assertThat(rollEvents.rollsFor("NIFTY 50")).hasSize(1);
    List<Candle> cont =
        candles.range(
            "NFO", "NIFTY-FUT-CONT", "1d", ist("2026-06-22T00:00:00"), ist("2026-06-26T00:00:00"));
    assertThat(cont).hasSize(4);
    assertThat(cont.get(0).close()).isEqualByComparingTo("24100.0000"); // stored UNADJUSTED
    assertThat(cont.get(2).close()).isEqualByComparingTo("24200.0000"); // roll-day bar = outgoing
    assertThat(cont.get(3).close()).isEqualByComparingTo("24400.0000"); // post-roll = incoming
  }

  @Test
  void adjustBackShiftsPreRollBarsAtReadTimeOnly() throws Exception {
    roller.rollNow();
    NOW.set(EXPIRY_DAY);
    roller.rollNow();

    mockMvc
        .perform(
            get("/api/v1/market/candles")
                .param("exchange", "NFO")
                .param("tradingsymbol", "NIFTY-FUT-CONT")
                .param("interval", "1d")
                .param("adjust", "none")
                .param("from", "2026-06-22T00:00:00+05:30")
                .param("to", "2026-06-26T00:00:00+05:30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].close").value("24100.0000"))
        .andExpect(jsonPath("$.items[3].close").value("24400.0000"));

    mockMvc
        .perform(
            get("/api/v1/market/candles")
                .param("exchange", "NFO")
                .param("tradingsymbol", "NIFTY-FUT-CONT")
                .param("interval", "1d")
                .param("adjust", "back")
                .param("from", "2026-06-22T00:00:00+05:30")
                .param("to", "2026-06-26T00:00:00+05:30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].close").value("24250.0000")) // +150 cumulative gap
        .andExpect(jsonPath("$.items[1].close").value("24300.0000"))
        .andExpect(jsonPath("$.items[2].close").value("24350.0000")) // roll-day bar adjusts too
        .andExpect(jsonPath("$.items[3].close").value("24400.0000")); // post-roll untouched
  }

  @Test
  void synConTombstoneExemptionSurvivesResyncAndSymbolStaysOffKite() {
    roller.rollNow();
    syncService.runSync(); // a second sync must NOT tombstone the synthetic row

    var row = instruments.findByKey("NFO", "NIFTY-FUT-CONT");
    assertThat(row).isPresent();
    assertThat(row.get().active()).isTrue();
    assertThat(row.get().segment()).isEqualTo("SYN-CONT");
    assertThat(row.get().instrumentToken()).as("no token — can never reach a Kite port").isNull();
  }

  @Test
  void backtestRoleCanSelectRollEventsButNeverInsert() throws Exception {
    roller.rollNow();

    try (Connection connection = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE ay_backtest");
      try (var rs = statement.executeQuery("SELECT count(*) FROM marketdata.roll_events")) {
        assertThat(rs.next()).isTrue();
      }
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO marketdata.roll_events (underlying, roll_date, from_tradingsymbol,"
                          + " to_tradingsymbol, price_gap) VALUES ('X','2026-01-01','A','B',1)"))
          .hasMessageContaining("permission denied");
    }
  }
}
