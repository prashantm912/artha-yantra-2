package in.arthayantra.marketdata.bhavcopy;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.candles.Candle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Maps a bhavcopy row to a 1d BHAVCOPY candle bucketed at IST midnight. */
class BhavcopyCandlesTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Test
  void buildsOneDayBhavcopyCandleAtIstMidnight() {
    Candle c =
        BhavcopyCandles.of(
            "NSE",
            "SBIN",
            LocalDate.of(2026, 6, 18),
            new BigDecimal("100.00"),
            new BigDecimal("105.00"),
            new BigDecimal("99.00"),
            new BigDecimal("104.50"),
            123456L);

    assertThat(c.exchange()).isEqualTo("NSE");
    assertThat(c.tradingsymbol()).isEqualTo("SBIN");
    assertThat(c.interval()).isEqualTo("1d");
    assertThat(c.source()).isEqualTo("BHAVCOPY");
    assertThat(c.bucket()).isEqualTo(OffsetDateTime.of(2026, 6, 18, 0, 0, 0, 0, IST));
    assertThat(c.close()).isEqualByComparingTo("104.50");
    assertThat(c.volume()).isEqualTo(123456L);
    assertThat(c.oi()).isNull();
  }

  @Test
  void nullVolumeBecomesZero() {
    Candle c =
        BhavcopyCandles.of(
            "BSE", "RELIANCE", LocalDate.of(2026, 6, 18),
            new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"), null);
    assertThat(c.volume()).isZero();
  }

  @Test
  void missingOhlcYieldsNull() {
    assertThat(
            BhavcopyCandles.of(
                "NSE", "ZZ", LocalDate.of(2026, 6, 18),
                null, new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"), 1L))
        .isNull();
    assertThat(
            BhavcopyCandles.of(
                "NSE", "ZZ", LocalDate.of(2026, 6, 18),
                new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"), null, 1L))
        .isNull();
  }
}
