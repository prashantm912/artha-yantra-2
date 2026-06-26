package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.candles.RollEventsRepository;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Date;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 2b-E1 IT: {@link ContinuousFuturesBackfill} reconstructs a HISTORICAL {@code {root}-FUT-CONT} 1m
 * series from the {@code expired_contracts} FUT roster (no live contracts in the fixture). It must
 * stitch each contract only over its FRONT-MONTH segment (so the post-roll bars come from the next
 * contract, not the expiring one), and append exactly one {@code roll_events} row whose gap is
 * derived from the LAST 1m close of the roll day — expired contracts carry 1m bars but no native 1d,
 * so the {@code closeAt("1d")} → {@code lastIntradayClose} fallback is what makes the gap non-null.
 * Uses a throwaway {@code TSTNF} underlying so it never collides with the singleton DB's real rows.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ContinuousFuturesBackfillIntegrationTest extends MarketDataIntegrationTestBase {

  private static final Instant NOW_INSTANT =
      OffsetDateTime.parse("2026-03-02T12:00:00+05:30").toInstant();
  private static final AtomicReference<Instant> NOW = new AtomicReference<>(NOW_INSTANT);

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

  @Autowired private ContinuousFuturesBackfill backfill;
  @Autowired private CandleRepository candles;
  @Autowired private RollEventsRepository rollEvents;
  @Autowired private JdbcTemplate jdbc;

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  private void seedExpiredFut(String symbol, String expiry) {
    jdbc.update(
        "INSERT INTO expired_contracts (exchange, tradingsymbol, instrument_type, underlying_symbol,"
            + " expiry, lot_size, upstox_key, underlying_key)"
            + " VALUES ('NFO', ?, 'FUT', 'TSTNF', ?, 50, ?, 'NSE_INDEX|TSTNF')",
        symbol,
        Date.valueOf(expiry),
        symbol);
  }

  private void seed1m(String symbol, String tsIst, String close) {
    BigDecimal c = new BigDecimal(close);
    candles.upsert(new Candle("NFO", symbol, "1m", ist(tsIst), c, c, c, c, 100, 0L, "MOCK"));
  }

  @BeforeEach
  void cleanSlateAndSeed() {
    NOW.set(NOW_INSTANT);
    jdbc.update("DELETE FROM candles WHERE tradingsymbol LIKE 'TSTNF%'");
    jdbc.update("DELETE FROM roll_events WHERE underlying = 'TST NF 50'");
    jdbc.update("DELETE FROM expired_contracts WHERE underlying_symbol = 'TSTNF'");
    jdbc.update("DELETE FROM instruments WHERE tradingsymbol LIKE 'TSTNF%'");

    // Two adjacent monthly futures around a JAN->FEB roll (expiry Wed, roll = prior trading Tue).
    seedExpiredFut("TSTNF28JAN26FUT", "2026-01-28"); // roll 2026-01-27
    seedExpiredFut("TSTNF25FEB26FUT", "2026-02-25"); // roll 2026-02-24

    // front-month JAN bars
    seed1m("TSTNF28JAN26FUT", "2026-01-23T15:29:00", "100");
    seed1m("TSTNF28JAN26FUT", "2026-01-27T15:29:00", "101"); // roll-day close (outgoing)
    // FEB bars: a roll-day bar (for the gap incoming) PLUS its own front-month segment
    seed1m("TSTNF25FEB26FUT", "2026-01-27T15:29:00", "110"); // roll-day close (incoming) — NOT stitched
    seed1m("TSTNF25FEB26FUT", "2026-01-28T15:29:00", "111"); // first bar FEB is front
    seed1m("TSTNF25FEB26FUT", "2026-02-24T15:29:00", "120");
  }

  @Test
  void reconstructsFrontMonthContinuousAcrossARoll() {
    int contracts = backfill.backfill("TSTNF", "NSE", "TST NF 50");
    assertThat(contracts).isEqualTo(2);

    List<Candle> cont =
        candles.range(
            "NFO", "TSTNF-FUT-CONT", "1m", ist("2026-01-01T00:00:00"), ist("2026-03-01T00:00:00"));

    // JAN contract over its front segment (..roll], then FEB contract from the next session on:
    // the JAN-27 roll-day FEB bar (110) is excluded; FEB only contributes from 2026-01-28.
    assertThat(cont).extracting(c -> c.close().stripTrailingZeros().toPlainString())
        .containsExactly("100", "101", "111", "120");

    // Exactly one roll event; gap derived from the 1m fallback (110 incoming − 101 outgoing).
    List<RollEventsRepository.RollEvent> rolls = rollEvents.rollsFor("TST NF 50");
    assertThat(rolls).hasSize(1);
    assertThat(rolls.get(0).fromTradingsymbol()).isEqualTo("TSTNF28JAN26FUT");
    assertThat(rolls.get(0).toTradingsymbol()).isEqualTo("TSTNF25FEB26FUT");
    assertThat(rolls.get(0).priceGap()).isEqualByComparingTo("9"); // 110 − 101
  }

  @Test
  void isIdempotentOnReRun() {
    backfill.backfill("TSTNF", "NSE", "TST NF 50");
    backfill.backfill("TSTNF", "NSE", "TST NF 50");

    List<Candle> cont =
        candles.range(
            "NFO", "TSTNF-FUT-CONT", "1m", ist("2026-01-01T00:00:00"), ist("2026-03-01T00:00:00"));
    assertThat(cont).hasSize(4);
    assertThat(rollEvents.rollsFor("TST NF 50")).hasSize(1);
  }
}
