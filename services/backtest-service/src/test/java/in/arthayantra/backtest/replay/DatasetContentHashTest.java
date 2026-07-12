package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Audit P1-1 / R2: the {@link DatasetContentHash} recipe is deterministic and content-stable — a
 * value-identical window hashes IDENTICALLY (no {@code fetched_at} leg, unlike {@link DataHash}) while a
 * single changed bar flips the hash. Pure unit test (no DB).
 */
class DatasetContentHashTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 1, 5, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

  private static EngineCandle bar(int minute, String close) {
    return new EngineCandle(
        T0.plusMinutes(minute),
        new BigDecimal("100.00"),
        new BigDecimal("101.00"),
        new BigDecimal("99.00"),
        new BigDecimal(close),
        1000L,
        null);
  }

  private static List<DatasetContentHash.Series> series(List<EngineCandle> primary) {
    return List.of(DatasetContentHash.Series.withCandles("NSE", "NIFTY 50", "1m", primary));
  }

  @Test
  void identicalWindowsHashIdentically() {
    List<EngineCandle> a = List.of(bar(0, "100.50"), bar(1, "100.75"), bar(2, "101.25"));
    List<EngineCandle> b = List.of(bar(0, "100.50"), bar(1, "100.75"), bar(2, "101.25"));

    assertThat(DatasetContentHash.of(series(a))).isEqualTo(DatasetContentHash.of(series(b)));
    // and the run-level call is stable on repeat (no wall-clock / RNG anywhere in the recipe)
    assertThat(DatasetContentHash.of(series(a))).isEqualTo(DatasetContentHash.of(series(a)));
  }

  @Test
  void aChangedBarFlipsTheHash() {
    List<EngineCandle> original = List.of(bar(0, "100.50"), bar(1, "100.75"), bar(2, "101.25"));
    List<EngineCandle> changed = List.of(bar(0, "100.50"), bar(1, "100.76"), bar(2, "101.25"));

    assertThat(DatasetContentHash.of(series(changed)))
        .isNotEqualTo(DatasetContentHash.of(series(original)));
  }

  @Test
  void barCountAndSymbolAndOrderAllMatter() {
    List<EngineCandle> two = List.of(bar(0, "100.50"), bar(1, "100.75"));
    List<EngineCandle> three = List.of(bar(0, "100.50"), bar(1, "100.75"), bar(2, "101.25"));
    assertThat(DatasetContentHash.of(series(two)))
        .isNotEqualTo(DatasetContentHash.of(series(three)));

    // a different symbol on the same bars is a different dataset
    List<DatasetContentHash.Series> otherSym =
        List.of(DatasetContentHash.Series.withCandles("NSE", "BANKNIFTY", "1m", three));
    assertThat(DatasetContentHash.of(series(three))).isNotEqualTo(DatasetContentHash.of(otherSym));

    // a count-only option leg is distinct from a value-checksum leg with the same count
    DatasetContentHash.Series countLeg = DatasetContentHash.Series.countOnly("NFO", "X", "1m", 3);
    DatasetContentHash.Series valueLeg =
        DatasetContentHash.Series.withCandles("NFO", "X", "1m", three);
    assertThat(countLeg.valueChecksum()).isEqualTo("count");
    assertThat(valueLeg.valueChecksum()).isNotEqualTo("count");
  }

  @Test
  void oiParticipatesInTheChecksum() {
    List<EngineCandle> noOi = List.of(bar(0, "100.50"));
    List<EngineCandle> withOi =
        List.of(
            new EngineCandle(
                T0,
                new BigDecimal("100.00"),
                new BigDecimal("101.00"),
                new BigDecimal("99.00"),
                new BigDecimal("100.50"),
                1000L,
                new BigDecimal("5000")));
    assertThat(DatasetContentHash.checksumOf(withOi))
        .isNotEqualTo(DatasetContentHash.checksumOf(noOi));
  }
}
