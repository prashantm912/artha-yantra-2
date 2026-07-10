package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Locks two behaviours of the live series store.
 *
 * <ol>
 *   <li>the resubscribe-gap dedup (audit resubscribe-gap-drops-1m-bars): the hot-swap overlaps the
 *       old and new Redis containers, so the same bar can be delivered twice — append must report
 *       the duplicate so the engine skips re-evaluation (a re-run could fire a phantom instant
 *       structural-stop exit);
 *   <li>the in-progress-bucket completeness filter (audit B1 / FID P0-1): REST reads that end
 *       mid-bucket return the still-forming coarse bucket as a partial — it must be dropped so the
 *       old freeze-the-partial-until-restart bug cannot recur.
 * </ol>
 */
class LiveSeriesStoreTest {

  private static final SeriesKey NIFTY_3M = new SeriesKey("NFO", "NIFTY26JULFUT", "3m");

  private static EngineCandle barAt(String iso) {
    return barAt(iso, 1000L);
  }

  private static EngineCandle barAt(String iso, long volume) {
    return new EngineCandle(
        OffsetDateTime.parse(iso), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100.5"), volume, null);
  }

  @Test
  void duplicateAndStaleAppendsReportFalseSoTheEngineSkipsReEvaluation() {
    LiveSeriesStore store = new LiveSeriesStore(null, Clock.systemUTC());
    SeriesKey key = new SeriesKey("NFO", "NIFTY26JULFUT", "1m");

    assertThat(store.append(key, barAt("2026-07-03T09:16:00+05:30"))).isTrue();
    // the resubscribe overlap window delivers the same bar from both containers
    assertThat(store.append(key, barAt("2026-07-03T09:16:00+05:30"))).isFalse();
    // an out-of-order replay is equally rejected
    assertThat(store.append(key, barAt("2026-07-03T09:15:00+05:30"))).isFalse();
    // the next real bar still lands
    assertThat(store.append(key, barAt("2026-07-03T09:17:00+05:30"))).isTrue();
  }

  @Test
  void refreshDropsTheInProgressBucketButKeepsCompletedOnes() {
    // clock 09:19 IST: 09:12 (ends 09:15) and 09:15 (ends 09:18) are complete; 09:18 (ends 09:21) is
    // still forming and must be excluded.
    MutableClock clock = new MutableClock(instant("2026-07-03T09:19:00+05:30"));
    FakeCandlesClient client = new FakeCandlesClient();
    client.enqueue(
        List.of(
            barAt("2026-07-03T09:12:00+05:30", 200), // completed
            barAt("2026-07-03T09:15:00+05:30", 300), // completed
            barAt("2026-07-03T09:18:00+05:30", 100))); // in-progress partial — must be dropped
    LiveSeriesStore store = new LiveSeriesStore(client, clock);

    store.refreshFromRest(NIFTY_3M);

    EngineSeries series = store.series(NIFTY_3M);
    assertThat(series.size()).isEqualTo(2);
    assertThat(series.candle(series.size() - 1).bucketStart().toInstant())
        .isEqualTo(instant("2026-07-03T09:15:00+05:30"));
  }

  @Test
  void theFrozenPartialRegressionIsGoneAcrossTwoBucketBoundaries() {
    MutableClock clock = new MutableClock(instant("2026-07-03T09:19:00+05:30"));
    FakeCandlesClient client = new FakeCandlesClient();
    LiveSeriesStore store = new LiveSeriesStore(client, clock);

    // boundary 1 (09:19): completed 09:15 + partial 09:18 → the partial must NOT accrue.
    client.enqueue(
        List.of(
            barAt("2026-07-03T09:15:00+05:30", 300),
            barAt("2026-07-03T09:18:00+05:30", 100)));
    store.refreshFromRest(NIFTY_3M);
    assertThat(store.series(NIFTY_3M).candle(store.series(NIFTY_3M).size() - 1).bucketStart().toInstant())
        .isEqualTo(instant("2026-07-03T09:15:00+05:30"));

    // boundary 2 (09:22): 09:18 is now COMPLETE with its FULL volume (300); 09:21 is the new partial.
    // Pre-fix, the frozen partial-09:18 (100) blocked this completed 09:18 as a duplicate bucket and
    // the series stayed frozen at 100. Post-fix, the completed 09:18 lands with full volume.
    clock.set(instant("2026-07-03T09:22:00+05:30"));
    client.enqueue(
        List.of(
            barAt("2026-07-03T09:15:00+05:30", 300), // re-fetched — dropped as a duplicate
            barAt("2026-07-03T09:18:00+05:30", 300), // now completed, full volume
            barAt("2026-07-03T09:21:00+05:30", 100))); // in-progress partial
    store.refreshFromRest(NIFTY_3M);

    EngineSeries series = store.series(NIFTY_3M);
    EngineCandle last = series.candle(series.size() - 1);
    assertThat(last.bucketStart().toInstant()).isEqualTo(instant("2026-07-03T09:18:00+05:30"));
    assertThat(last.volume()).isEqualTo(300L); // FULL bucket, not the 100 the old code froze
  }

  @Test
  void dailyRefreshStillAppendsTodaysInProgressRowUnfiltered() {
    // Today's 1d bucket (00:00 IST) ends tomorrow, so it is "in progress" at midday — but 1d is NOT
    // filtered (its btst pre-close / daily-context handling is FID P1-9), so it must still append.
    MutableClock clock = new MutableClock(instant("2026-07-03T12:00:00+05:30"));
    FakeCandlesClient client = new FakeCandlesClient();
    client.enqueue(
        List.of(
            barAt("2026-07-02T00:00:00+05:30", 1000), // yesterday (completed)
            barAt("2026-07-03T00:00:00+05:30", 500))); // today (in progress) — must still append
    LiveSeriesStore store = new LiveSeriesStore(client, clock);
    SeriesKey dailyKey = new SeriesKey("NSE", "NIFTY 50", "1d");

    store.refreshFromRest(dailyKey);

    EngineSeries series = store.series(dailyKey);
    assertThat(series.size()).isEqualTo(2);
    assertThat(series.candle(1).bucketStart().toInstant())
        .isEqualTo(instant("2026-07-03T00:00:00+05:30"));
  }

  @Test
  void anUnfilteredIntervalFailsOpenAndKeepsTheInProgressBucket() {
    // 1w hits the filter's fail-open default (as any interval the store does not filter would): an
    // in-progress weekly bucket still appends — the old behaviour is preserved for unfiltered TFs.
    MutableClock clock = new MutableClock(instant("2026-07-03T12:00:00+05:30"));
    FakeCandlesClient client = new FakeCandlesClient();
    // weekly bucket starting Mon 2026-06-29 ends 2026-07-06 — still forming on 07-03.
    client.enqueue(List.of(barAt("2026-06-29T00:00:00+05:30", 5000)));
    LiveSeriesStore store = new LiveSeriesStore(client, clock);
    SeriesKey weeklyKey = new SeriesKey("NSE", "NIFTY 50", "1w");

    store.refreshFromRest(weeklyKey);

    assertThat(store.series(weeklyKey).size()).isEqualTo(1);
  }

  private static Instant instant(String iso) {
    return OffsetDateTime.parse(iso).toInstant();
  }

  /** A {@link MarketDataCandlesClient} whose {@code fetch} replays enqueued responses in order. */
  private static final class FakeCandlesClient extends MarketDataCandlesClient {
    private final Deque<List<EngineCandle>> responses = new ArrayDeque<>();

    FakeCandlesClient() {
      super(RestClient.builder(), new ObjectMapper(), "http://market-data:8081");
    }

    void enqueue(List<EngineCandle> response) {
      responses.add(response);
    }

    @Override
    public List<EngineCandle> fetch(
        String exchange, String tradingsymbol, String interval,
        OffsetDateTime from, OffsetDateTime to) {
      return responses.isEmpty() ? List.of() : responses.removeFirst();
    }
  }

  /** A settable-instant clock so a single store can span two bucket boundaries in one test. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public long millis() {
      return instant.toEpochMilli();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
