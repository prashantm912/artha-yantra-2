package in.arthayantra.backtest.indicators;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.indicators.EngineIndicator;
import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 40B: the indicator-series service computes via the SAME {@link IndicatorRegistry} the
 * live/backtest engines use — proven here by byte-equality against a direct registry call (no second
 * implementation of the parity-critical math, the S7 rule). Plus registry shape + cache-key
 * distinctness. The DB-backed warm-up/series path is exercised on the running mock stack.
 */
class IndicatorSeriesServiceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private final IndicatorSeriesService service = new IndicatorSeriesService(null);

  /** A deterministic ramp of 30 daily candles (close 100..129). */
  private static List<EngineCandle> ramp() {
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      BigDecimal close = BigDecimal.valueOf(100 + i);
      OffsetDateTime t = OffsetDateTime.of(2026, 2, 1, 9, 15, 0, 0, IST).plusDays(i);
      BigDecimal hi = close.add(BigDecimal.ONE);
      BigDecimal lo = close.subtract(BigDecimal.ONE);
      candles.add(new EngineCandle(t, close, hi, lo, close, 1_000, null));
    }
    return candles;
  }

  @Test
  void valuesOfEqualTheEngineRegistryExactly() {
    List<EngineCandle> candles = ramp();
    Map<String, Object> params = Map.of("period", 3);

    List<BigDecimal> viaService = service.valuesOf(candles, "EMA", params);

    EngineSeries series = EngineSeries.of(new SeriesKey("X", "X", "1d"), candles);
    EngineIndicator direct = IndicatorRegistry.create("EMA", series, null, params);
    assertThat(viaService).hasSize(candles.size());
    for (int i = 0; i < candles.size(); i++) {
      BigDecimal expected = direct.valueAt(i);
      if (expected == null) {
        assertThat(viaService.get(i)).isNull();
      } else {
        assertThat(viaService.get(i)).isEqualByComparingTo(expected);
      }
    }
    // converged at the end (an EMA over a rising ramp is positive and below the latest close)
    assertThat(viaService.get(candles.size() - 1)).isNotNull().isPositive();
  }

  @Test
  void defaultsLetParamlessIndicatorsCompute() {
    // RSI's engine default period is 14, so no explicit param is needed.
    List<BigDecimal> rsi = service.valuesOf(ramp(), "RSI", Map.of());
    assertThat(rsi.get(rsi.size() - 1)).isNotNull();
  }

  @Test
  void registryIsRegistryDrivenWithRenderAndPaneHints() {
    List<IndicatorSeriesService.IndicatorMeta> registry = service.registry();
    assertThat(registry).hasSameSizeAs(IndicatorRegistry.knownNames());

    IndicatorSeriesService.IndicatorMeta ema =
        registry.stream().filter(m -> m.id().equals("EMA")).findFirst().orElseThrow();
    assertThat(ema.pane()).isEqualTo("price");
    assertThat(ema.render()).isEqualTo("line");
    assertThat(ema.outputs()).containsExactly("EMA");
    assertThat(ema.params()).anyMatch(p -> p.name().equals("period") && p.defaultValue().equals(20));

    IndicatorSeriesService.IndicatorMeta macd =
        registry.stream().filter(m -> m.id().equals("MACD_HIST")).findFirst().orElseThrow();
    assertThat(macd.render()).isEqualTo("histogram");
    assertThat(macd.pane()).isEqualTo("sub");

    IndicatorSeriesService.IndicatorMeta rs =
        registry.stream().filter(m -> m.id().equals("RS_VS_INDEX")).findFirst().orElseThrow();
    assertThat(rs.requiresContext()).isTrue();
  }

  @Test
  void cacheKeyIsDistinctPerIndicatorParamsSymbolIntervalRange() {
    OffsetDateTime from = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, IST);
    OffsetDateTime to = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, IST);
    String base = service.cacheKey("EMA", "NSE", "RELIANCE", "1d", from, to, Map.of("period", 9));

    assertThat(base).isEqualTo(service.cacheKey("EMA", "NSE", "RELIANCE", "1d", from, to, Map.of("period", 9)));
    assertThat(base).isNotEqualTo(service.cacheKey("EMA", "NSE", "RELIANCE", "1d", from, to, Map.of("period", 21)));
    assertThat(base).isNotEqualTo(service.cacheKey("SMA", "NSE", "RELIANCE", "1d", from, to, Map.of("period", 9)));
    assertThat(base).isNotEqualTo(service.cacheKey("EMA", "NSE", "INFY", "1d", from, to, Map.of("period", 9)));
    assertThat(base).isNotEqualTo(service.cacheKey("EMA", "NSE", "RELIANCE", "5m", from, to, Map.of("period", 9)));
    OffsetDateTime to2 = to.plusDays(1);
    assertThat(base).isNotEqualTo(service.cacheKey("EMA", "NSE", "RELIANCE", "1d", from, to2, Map.of("period", 9)));
  }
}
