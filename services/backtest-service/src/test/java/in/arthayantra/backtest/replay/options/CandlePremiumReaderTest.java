package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NavigableMap;
import org.junit.jupiter.api.Test;

/**
 * Fixture test for {@link CandlePremiumReader}: the option's 1m closes become its premium series, and
 * {@link CandlePremiumReader#premiumAt} aligns to an underlying bar by carrying the last premium
 * forward over a minute the option didn't trade (and returns null before the first trade).
 */
class CandlePremiumReaderTest {

  private static final OptionContract CONTRACT =
      new OptionContract(
          "NFO", "NIFTY16JUN2625000CE", new BigDecimal("25000"),
          LocalDate.of(2026, 6, 16), "CE", 65);

  private static OffsetDateTime t(String iso) {
    return OffsetDateTime.parse(iso);
  }

  private static EngineCandle bar(String iso, String close) {
    return new EngineCandle(
        t(iso), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close),
        new BigDecimal(close), 100L);
  }

  @Test
  void mapsClosesToPremiumSeriesAndAlignsWithCarryForward() {
    CandleReader reader = mock(CandleReader.class);
    OffsetDateTime from = t("2026-06-16T09:15:00+05:30");
    OffsetDateTime to = t("2026-06-16T15:30:00+05:30");
    // 09:15, 09:16, then a GAP at 09:17 (no trade), 09:18 — three bars.
    when(reader.read(eq("NFO"), eq("NIFTY16JUN2625000CE"), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                bar("2026-06-16T09:15:00+05:30", "80.00"),
                bar("2026-06-16T09:16:00+05:30", "82.50"),
                bar("2026-06-16T09:18:00+05:30", "85.00")));

    CandlePremiumReader premiums = new CandlePremiumReader(reader);
    NavigableMap<OffsetDateTime, BigDecimal> series = premiums.premiumSeries(CONTRACT, from, to);

    assertThat(series).hasSize(3);
    // exact bar
    assertThat(CandlePremiumReader.premiumAt(series, t("2026-06-16T09:16:00+05:30")))
        .isEqualByComparingTo("82.50");
    // gap minute (09:17) → carries 09:16 forward
    assertThat(CandlePremiumReader.premiumAt(series, t("2026-06-16T09:17:00+05:30")))
        .isEqualByComparingTo("82.50");
    // before the first trade → null (cannot be priced)
    assertThat(CandlePremiumReader.premiumAt(series, t("2026-06-16T09:14:00+05:30"))).isNull();
  }

  @Test
  void uncoveredContractYieldsAnEmptySeries() {
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(any(), any(), any(), any(), any())).thenReturn(List.of());
    NavigableMap<OffsetDateTime, BigDecimal> series =
        new CandlePremiumReader(reader)
            .premiumSeries(CONTRACT, t("2026-06-16T09:15:00+05:30"), t("2026-06-16T15:30:00+05:30"));
    assertThat(series).isEmpty();
    assertThat(CandlePremiumReader.premiumAt(series, t("2026-06-16T10:00:00+05:30"))).isNull();
  }
}
