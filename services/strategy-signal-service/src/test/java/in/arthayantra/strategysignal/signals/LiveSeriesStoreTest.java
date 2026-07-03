package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * Audit fix resubscribe-gap-drops-1m-bars: the hot-swap now overlaps the old and new Redis
 * containers, so the same bar can be delivered twice — append must report the duplicate so the
 * engine skips re-evaluation (a re-run could fire a phantom instant structural-stop exit).
 */
class LiveSeriesStoreTest {

  private static EngineCandle barAt(String iso) {
    return new EngineCandle(
        OffsetDateTime.parse(iso), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100.5"), 1000L, null);
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
}
