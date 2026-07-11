package in.arthayantra.backtest.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.EquityPoint;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D7: benchmark analytics read the NATIVE daily source ({@link CandleReader#readDaily}) — the dense
 * {@code candles}@{@code 1d} that guard 6 already pre-flights — not the sparse 1m-rolled {@code
 * candles_1d} cagg ({@link CandleReader#read}). Proved with a stub reader that fails the run if the
 * cagg path is taken.
 */
class BenchmarkAnalyzerDailySourceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Test
  void analyzeReadsNativeDailyNotTheRolledCagg() {
    OffsetDateTime d1 = OffsetDateTime.of(2026, 2, 2, 15, 30, 0, 0, IST);
    OffsetDateTime d2 = d1.plusDays(1);
    List<EquityPoint> equity =
        List.of(
            new EquityPoint(d1, new BigDecimal("1000000")),
            new EquityPoint(d2, new BigDecimal("1010000")));
    List<EngineCandle> dailyBars =
        List.of(dayBar(d1, "20000"), dayBar(d2, "20100")); // native candles@1d

    RecordingReader reader = new RecordingReader(dailyBars);
    BenchmarkAnalyzer analyzer = new BenchmarkAnalyzer(reader);

    BenchmarkAnalytics result =
        analyzer.analyze(
            new ObjectMapper().createObjectNode(), // empty config -> default NSE:NIFTY 50 benchmark
            equity,
            d1,
            d2.plusDays(1),
            new BigDecimal("1000000"),
            new BigDecimal("0.10"));

    assertThat(reader.dailyCalls).isEqualTo(1);
    assertThat(reader.readCalls).as("must not touch the sparse candles_1d cagg").isZero();
    assertThat(result.present()).isTrue();
  }

  private static EngineCandle dayBar(OffsetDateTime ts, String close) {
    BigDecimal c = new BigDecimal(close);
    return new EngineCandle(ts, c, c, c, c, 0, null);
  }

  /** A {@link CandleReader} serving canned native daily bars; records if the cagg path is taken. */
  private static final class RecordingReader extends CandleReader {
    private final List<EngineCandle> daily;
    int readCalls;
    int dailyCalls;

    RecordingReader(List<EngineCandle> daily) {
      super(null);
      this.daily = daily;
    }

    @Override
    public List<EngineCandle> read(
        String exchange, String tradingsymbol, String interval, OffsetDateTime from, OffsetDateTime to) {
      readCalls++;
      return List.of();
    }

    @Override
    public List<EngineCandle> readDaily(
        String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
      dailyCalls++;
      return daily;
    }
  }
}
