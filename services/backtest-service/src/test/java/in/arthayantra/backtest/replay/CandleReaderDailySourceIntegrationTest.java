package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * D7: proves the load-bearing source swap. {@link CandleReader#readDaily} serves the NATIVE {@code
 * candles}@{@code 1d} bar while {@link CandleReader#read}{@code (..,"1d",..)} still serves the
 * 1m-rolled {@code candles_1d} cagg — so a fresh-boot backtest 1d context/benchmark read lands on
 * the dense native daily bars, not the sparse cagg. Seeds DIVERGENT values into the two sources for
 * the same IST-day bucket (native close != the 1m-rolled cagg close) and asserts native wins on the
 * new path AND the cagg still answers the old path (the divergence is real, the swap matters).
 */
class CandleReaderDailySourceIntegrationTest extends BacktestIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  // Unique symbol: ITs share the singleton DB with no per-method cleanup.
  private static final String EXCHANGE = "NSE";
  private static final String SYMBOL = "D7 DAILY SRC";

  private static final BigDecimal NATIVE_CLOSE = new BigDecimal("500.00"); // Kite daily / bhavcopy
  private static final BigDecimal CAGG_CLOSE = new BigDecimal("999.00"); // last 1m close of the day

  @Test
  void readDailyServesNativeBarWhileReadServesTheRolledCagg() {
    JdbcTemplate jdbc =
        new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), dbUser(), dbPassword()));

    OffsetDateTime day = OffsetDateTime.of(2026, 2, 2, 0, 0, 0, 0, IST); // IST-day bucket start
    OffsetDateTime from = day;
    OffsetDateTime to = day.plusDays(1);

    // Native daily bar (interval='1d') — the source readDaily/regime/warm-up read.
    jdbc.update(
        "INSERT INTO marketdata.candles "
            + "(exchange, tradingsymbol, \"interval\", bucket, open, high, low, close, volume, source) "
            + "VALUES (?, ?, '1d', ?, ?, ?, ?, ?, 0, 'MOCK')",
        EXCHANGE, SYMBOL, from, NATIVE_CLOSE, NATIVE_CLOSE, NATIVE_CLOSE, NATIVE_CLOSE);

    // Two 1m bars on the SAME IST day — the candles_1d cagg rolls them to last(close)=CAGG_CLOSE.
    // interval='1d' is excluded from the cagg's source filter, so the native bar never feeds it.
    jdbc.update(
        "INSERT INTO marketdata.candles "
            + "(exchange, tradingsymbol, \"interval\", bucket, open, high, low, close, volume, source) "
            + "VALUES (?, ?, '1m', ?, 900, 999, 899, 900, 100, 'MOCK')",
        EXCHANGE, SYMBOL, day.plusHours(9).plusMinutes(15));
    jdbc.update(
        "INSERT INTO marketdata.candles "
            + "(exchange, tradingsymbol, \"interval\", bucket, open, high, low, close, volume, source) "
            + "VALUES (?, ?, '1m', ?, 900, 1000, 900, ?, 100, 'MOCK')",
        EXCHANGE, SYMBOL, day.plusHours(9).plusMinutes(16), CAGG_CLOSE);

    CandleReader reader = new CandleReader(jdbc);

    List<EngineCandle> native1d = reader.readDaily(EXCHANGE, SYMBOL, from, to);
    List<EngineCandle> cagg1d = reader.read(EXCHANGE, SYMBOL, "1d", from, to);

    // The two sources genuinely diverge for this bucket (else the assertion below is vacuous).
    assertThat(NATIVE_CLOSE).isNotEqualByComparingTo(CAGG_CLOSE);

    assertThat(native1d).hasSize(1);
    assertThat(native1d.get(0).close())
        .as("readDaily returns the NATIVE candles@1d bar")
        .isEqualByComparingTo(NATIVE_CLOSE);

    assertThat(cagg1d).hasSize(1);
    assertThat(cagg1d.get(0).close())
        .as("read(..,\"1d\",..) still returns the 1m-rolled candles_1d cagg bar")
        .isEqualByComparingTo(CAGG_CLOSE);
  }
}
