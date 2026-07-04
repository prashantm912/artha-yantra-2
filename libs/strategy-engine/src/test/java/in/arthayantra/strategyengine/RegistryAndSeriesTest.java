package in.arthayantra.strategyengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Registry contract (Q2 server-side check source), series geometry. */
class RegistryAndSeriesTest {

  @Test
  void registryKnowsTheFullSchemaV1Vocabulary() {
    assertThat(IndicatorRegistry.knownNames())
        .containsExactly(
            "EMA", "SMA", "RSI", "VWAP", "ADX", "MACD_HIST", "SUPERTREND", "SUPERTREND_LINE",
            "VOLUME_RATIO",
            "OI_CHANGE_PCT", "ATR", "VWMA", "PSAR", "ORB_HIGH", "ORB_LOW", "PREV_DAY_HIGH",
            "PREV_DAY_LOW", "PREV_DAY_CLOSE", "DAY_HIGH", "DAY_LOW", "GAP_PCT", "RS_VS_INDEX",
            "VIX_LEVEL", "BASIS_PCT", "ADVANCE_DECLINE_RATIO", "VCP_PIVOT");
    assertThat(IndicatorRegistry.exists("EMA")).isTrue();
    assertThat(IndicatorRegistry.exists("PE_RATIO"))
        .as("fundamentals arrive later as NEW names — unknown today")
        .isFalse();
  }

  @Test
  void unknownParamsAreRejectedNotIgnored() {
    EngineSeries series = VectorFixtures.primarySeries();

    assertThatThrownBy(
            () -> IndicatorRegistry.create("EMA", series, null, Map.of("perid", 9)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("perid");
    assertThatThrownBy(() -> IndicatorRegistry.create("EMA", series, null, Map.of()))
        .as("EMA period is required")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void contextMechanismIndicatorsDemandTheOverride() {
    EngineSeries series = VectorFixtures.primarySeries();

    assertThatThrownBy(() -> IndicatorRegistry.create("VIX_LEVEL", series, null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("context override");
    assertThat(IndicatorRegistry.definition("RS_VS_INDEX").requiresContext()).isTrue();
    assertThat(IndicatorRegistry.definition("EMA").requiresContext()).isFalse();
  }

  @Test
  void seriesEnforcesIncreasingBucketOrder() {
    EngineSeries series = new EngineSeries(new SeriesKey("NSE", "ORDER", "1m"));
    series.append(candle("2026-02-03T09:15:00+05:30", "100"));

    assertThatThrownBy(() -> series.append(candle("2026-02-03T09:15:00+05:30", "101")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sessionGeometryTracksIstDays() {
    EngineSeries series = new EngineSeries(new SeriesKey("NSE", "GEO", "1m"));
    series.append(candle("2026-02-03T15:28:00+05:30", "100"));
    series.append(candle("2026-02-03T15:29:00+05:30", "101"));
    series.append(candle("2026-02-04T09:15:00+05:30", "102"));
    series.append(candle("2026-02-04T09:16:00+05:30", "103"));

    assertThat(series.sessionStart(1)).isEqualTo(0);
    assertThat(series.sessionStart(2)).isEqualTo(2);
    assertThat(series.sessionStart(3)).isEqualTo(2);
    assertThat(series.previousSessionEnd(3)).isEqualTo(1);
    assertThat(series.previousSessionEnd(0)).isEqualTo(-1);
    assertThat(series.indexAtOrBefore(Instant.parse("2026-02-04T03:45:30Z"))).isEqualTo(2);
    assertThat(series.indexAtOrBefore(Instant.parse("2026-02-01T00:00:00Z"))).isEqualTo(-1);
  }

  private static EngineCandle candle(String bucket, String price) {
    BigDecimal p = new BigDecimal(price);
    return new EngineCandle(OffsetDateTime.parse(bucket), p, p, p, p, 10);
  }
}
