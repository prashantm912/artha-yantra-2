package in.arthayantra.marketdata.mockfeed;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.candles.TradingBuckets;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase-11 mock history: deterministic synthesis + the planted-split scenario (16A seam). */
class MockHistoricalCandleGatewayTest {

  private final TradingBuckets buckets = new TradingBuckets(MarketCalendar.nse());

  private static final Instant FROM = OffsetDateTime.parse("2026-02-03T09:15:00+05:30").toInstant();
  private static final Instant TO = OffsetDateTime.parse("2026-02-03T10:15:00+05:30").toInstant();

  private MockHistoricalCandleGateway gateway(boolean caActive) {
    return new MockHistoricalCandleGateway(
        buckets, 42, "MOCK003", new BigDecimal("0.5"), LocalDate.parse("2026-06-01"), caActive);
  }

  @Test
  void sameRequestYieldsIdenticalCandles() {
    InstrumentKey key = new InstrumentKey("NSE", "MOCK001");

    List<HistoricalCandleGateway.Candle> first = gateway(false).fetch(key, "1m", FROM, TO);
    List<HistoricalCandleGateway.Candle> second = gateway(false).fetch(key, "1m", FROM, TO);

    assertThat(first).hasSize(60).isEqualTo(second);
    assertThat(first.get(0).high()).isGreaterThanOrEqualTo(first.get(0).low());
    assertThat(first)
        .allSatisfy(c -> assertThat(c.high()).isGreaterThanOrEqualTo(c.open().max(c.close())));
  }

  @Test
  void overlappingFetchesAgreeOnSharedBuckets() {
    InstrumentKey key = new InstrumentKey("NSE", "MOCK002");
    Instant midpoint = OffsetDateTime.parse("2026-02-03T09:45:00+05:30").toInstant();

    List<HistoricalCandleGateway.Candle> whole = gateway(false).fetch(key, "1m", FROM, TO);
    List<HistoricalCandleGateway.Candle> tail = gateway(false).fetch(key, "1m", midpoint, TO);

    assertThat(tail).isEqualTo(whole.subList(30, 60)); // idempotent overlap — B-6 upsert safety
  }

  @Test
  void plantedSplitBackAdjustsPreBoundaryHistoryOnly() {
    InstrumentKey key = new InstrumentKey("NSE", "MOCK003");

    List<HistoricalCandleGateway.Candle> normal = gateway(false).fetch(key, "1m", FROM, TO);
    List<HistoricalCandleGateway.Candle> adjusted = gateway(true).fetch(key, "1m", FROM, TO);

    // Feb 3 is before the 2026-06-01 boundary — the whole window is back-adjusted by 0.5
    for (int i = 0; i < normal.size(); i++) {
      assertThat(adjusted.get(i).open())
          .isEqualByComparingTo(normal.get(i).open().multiply(new BigDecimal("0.5")));
    }
    // other symbols are untouched
    InstrumentKey other = new InstrumentKey("NSE", "MOCK001");
    assertThat(gateway(true).fetch(other, "1m", FROM, TO))
        .isEqualTo(gateway(false).fetch(other, "1m", FROM, TO));
  }

  @Test
  void futuresCarryOpenInterestEquitiesDoNot() {
    List<HistoricalCandleGateway.Candle> fut =
        gateway(false).fetch(new InstrumentKey("NFO", "MOCKFUT"), "1m", FROM, TO);
    List<HistoricalCandleGateway.Candle> eq =
        gateway(false).fetch(new InstrumentKey("NSE", "MOCK001"), "1m", FROM, TO);

    assertThat(fut).allSatisfy(c -> assertThat(c.oi()).isNotNull());
    assertThat(eq).allSatisfy(c -> assertThat(c.oi()).isNull());
  }
}
