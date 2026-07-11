package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D7: the context-read routing seam. A {@code 1d} context indicator must read the NATIVE daily
 * source ({@link CandleReader#readDaily}) — the dense {@code candles}@{@code 1d} the warm-up/regime
 * plane and chart use — while every other interval reads its continuous aggregate ({@link
 * CandleReader#read}). The 1m-rolled {@code candles_1d} cagg is sparse on a fresh boot; a 1d context
 * on the cagg would evaluate a thinner series than live/deep-sim reads. Proved with a stub reader
 * that records which path {@link BacktestRunner#readContext} took (no DB, no engine).
 */
class BacktestRunnerContextDailySourceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final OffsetDateTime FROM = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, IST);
  private static final OffsetDateTime TO = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, IST);

  @Test
  void dailyContextReadsNativeDailyNotTheRolledCagg() {
    RecordingReader reader = new RecordingReader();

    List<EngineCandle> out = BacktestRunner.readContext(reader, "NSE", "NIFTY 50", "1d", FROM, TO);

    assertThat(reader.dailyCalls).isEqualTo(1);
    assertThat(reader.readCalls).isZero();
    assertThat(reader.lastDailyArgs).containsExactly("NSE", "NIFTY 50");
    assertThat(out).isSameAs(reader.dailyBars);
  }

  @Test
  void intradayContextReadsTheContinuousAggregate() {
    RecordingReader reader = new RecordingReader();

    List<EngineCandle> out = BacktestRunner.readContext(reader, "NSE", "NIFTY 50", "5m", FROM, TO);

    assertThat(reader.readCalls).isEqualTo(1);
    assertThat(reader.dailyCalls).isZero();
    assertThat(reader.lastReadArgs).containsExactly("NSE", "NIFTY 50", "5m");
    assertThat(out).isSameAs(reader.caggBars);
  }

  /** A {@link CandleReader} that records which read path it was asked for and serves canned bars. */
  private static final class RecordingReader extends CandleReader {
    final List<EngineCandle> dailyBars = List.of(bar(BigDecimal.valueOf(100)));
    final List<EngineCandle> caggBars = List.of(bar(BigDecimal.valueOf(200)));
    int readCalls;
    int dailyCalls;
    List<String> lastReadArgs = List.of();
    List<String> lastDailyArgs = List.of();

    RecordingReader() {
      super(null);
    }

    @Override
    public List<EngineCandle> read(
        String exchange, String tradingsymbol, String interval, OffsetDateTime from, OffsetDateTime to) {
      readCalls++;
      lastReadArgs = List.of(exchange, tradingsymbol, interval);
      return caggBars;
    }

    @Override
    public List<EngineCandle> readDaily(
        String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
      dailyCalls++;
      lastDailyArgs = List.of(exchange, tradingsymbol);
      return dailyBars;
    }

    private static EngineCandle bar(BigDecimal close) {
      return new EngineCandle(FROM, close, close, close, close, 1_000, null);
    }
  }
}
