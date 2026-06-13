package in.arthayantra.backtest.indicators;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.indicators.EngineIndicator;
import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Phase 40B (A13): serves engine-computed ta4j indicator series for the charts page. The values
 * come from the SAME strategy-engine {@link IndicatorRegistry} the live/backtest engines use — never
 * a second TS/Java implementation of the parity-critical math (the S7 rule). A series request is a
 * bounded replay over a candle window with server-side warm-up over-fetch; results are cached
 * in-memory (Caffeine — single-instance backtest-service; the StrategyVersionClient precedent).
 * Served on the web threadpool, outside the backtest worker pool.
 */
@Service
public class IndicatorSeriesService {

  /** Render hint per indicator id: how the overlay draws. */
  private static final Map<String, String> RENDER =
      Map.of("MACD_HIST", "histogram", "VOLUME_RATIO", "histogram", "OI_CHANGE_PCT", "histogram");

  /** Indicators that overlay the price pane; everything else lands in a sub-pane (oscillators). */
  private static final java.util.Set<String> PRICE_PANE =
      java.util.Set.of(
          "EMA", "SMA", "VWAP", "SUPERTREND", "ORB_HIGH", "ORB_LOW", "PREV_DAY_HIGH",
          "PREV_DAY_LOW", "PREV_DAY_CLOSE", "DAY_HIGH", "DAY_LOW");

  /** UI defaults per (indicator, param). EMA/SMA have no usable engine default (0), so the UI picks. */
  private static final Map<String, Map<String, Object>> DEFAULTS = defaults();

  /** Calendar over-fetch before `from` per interval, so warm-up completes before the requested range. */
  private static final Map<String, Duration> LOOKBACK =
      Map.of(
          "1m", Duration.ofDays(5),
          "5m", Duration.ofDays(15),
          "15m", Duration.ofDays(30),
          "1h", Duration.ofDays(60),
          "1d", Duration.ofDays(400),
          "1w", Duration.ofDays(1100));

  /** Native daily warm-up depth — covers the longest indicator (200-day SMA territory) plus buffer. */
  private static final int DAILY_WARMUP_SESSIONS = 300;

  private final CandleReader candleReader;
  private final Cache<String, List<NamedSeries>> cache =
      Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(5)).build();

  /** Wires the candle reader (the marketdata read path). */
  public IndicatorSeriesService(CandleReader candleReader) {
    this.candleReader = candleReader;
  }

  /** One named output series. */
  public record NamedSeries(String name, List<Point> points) {}

  /** One series point: IST bucket-start ISO time + decimal-string value. */
  public record Point(String time, String value) {}

  /** Registry-list entry (drives `GET /api/v1/indicators`). */
  public record IndicatorMeta(
      String id,
      String label,
      List<ParamMeta> params,
      List<String> outputs,
      String render,
      String pane,
      boolean requiresContext) {}

  /** One parameter with its UI default (null when the engine has no usable default). */
  public record ParamMeta(String name, Object defaultValue) {}

  /** The full registry list — driven by {@link IndicatorRegistry}, no per-indicator endpoint code. */
  public List<IndicatorMeta> registry() {
    List<IndicatorMeta> out = new ArrayList<>();
    for (String id : IndicatorRegistry.knownNames()) {
      IndicatorRegistry.Definition def = IndicatorRegistry.definition(id);
      Map<String, Object> defs = DEFAULTS.getOrDefault(id, Map.of());
      List<ParamMeta> params =
          def.params().stream().sorted().map(p -> new ParamMeta(p, defs.get(p))).toList();
      out.add(
          new IndicatorMeta(
              id,
              def.description(),
              params,
              List.of(id), // single-output today; multi-output (e.g. Bollinger) returns several
              RENDER.getOrDefault(id, "line"),
              PRICE_PANE.contains(id) ? "price" : "sub",
              def.requiresContext()));
    }
    return out;
  }

  /**
   * The engine value series for an indicator over a candle window — the per-bar {@link
   * EngineIndicator#valueAt} outputs (null while warming up). This IS the engine call (S7): no
   * reimplementation. Used directly by the parity test.
   */
  public List<BigDecimal> valuesOf(List<EngineCandle> candles, String name, Map<String, Object> params) {
    if (candles.isEmpty()) {
      return List.of();
    }
    EngineSeries series = EngineSeries.of(new SeriesKey("X", "X", "1d"), candles);
    EngineIndicator indicator;
    try {
      indicator = IndicatorRegistry.create(name, series, null, merge(name, params));
    } catch (IllegalArgumentException e) {
      // unknown indicator, bad param, or a context-required indicator with no context series
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, e.getMessage());
    }
    List<BigDecimal> out = new ArrayList<>(candles.size());
    for (int i = 0; i < candles.size(); i++) {
      out.add(indicator.valueAt(i));
    }
    return out;
  }

  /** The cache key — distinct per (indicator, params, symbol, interval, range). */
  public String cacheKey(
      String name,
      String exchange,
      String tradingsymbol,
      String interval,
      OffsetDateTime from,
      OffsetDateTime to,
      Map<String, Object> params) {
    return String.join(
        "|",
        name,
        exchange,
        tradingsymbol,
        interval,
        from.toString(),
        to.toString(),
        new java.util.TreeMap<>(merge(name, params)).toString());
  }

  /**
   * Resolve the series for a chart overlay: warm-up over-fetch, compute via the engine, return only
   * the converged in-range points. 422 {@code DATA_GAP} when the candle cache has no coverage.
   */
  public List<NamedSeries> series(
      String name,
      String exchange,
      String tradingsymbol,
      String interval,
      OffsetDateTime from,
      OffsetDateTime to,
      Map<String, Object> params) {
    String key = cacheKey(name, exchange, tradingsymbol, interval, from, to, params);
    List<NamedSeries> cached = cache.getIfPresent(key);
    if (cached != null) {
      return cached;
    }

    // The charts datafeed renders NATIVE daily bars (Kite's daily API → marketdata.candles at
    // interval='1d'), not the 1m-rolled candles_1d cagg that read() serves. Read the same native
    // source for a 1d overlay so its values land on the candles the chart draws — and the cagg is
    // sparse until 1m history accrues. Intraday intervals stay on the caggs (the chart serves those).
    List<EngineCandle> candles;
    if ("1d".equals(interval)) {
      candles = candleReader.readDailyWithWarmup(exchange, tradingsymbol, from, to, DAILY_WARMUP_SESSIONS);
    } else {
      OffsetDateTime fetchFrom = from.minus(LOOKBACK.getOrDefault(interval, Duration.ofDays(400)));
      candles = candleReader.read(exchange, tradingsymbol, interval, fetchFrom, to);
    }
    boolean anyInRange = candles.stream().anyMatch(c -> inRange(c.bucketStart(), from, to));
    if (!anyInRange) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_GAP,
          "no candle coverage for " + exchange + ":" + tradingsymbol + " " + interval,
          Map.of("exchange", exchange, "tradingsymbol", tradingsymbol, "interval", interval));
    }

    List<BigDecimal> values = valuesOf(candles, name, params);
    List<Point> points = new ArrayList<>();
    for (int i = 0; i < candles.size(); i++) {
      EngineCandle c = candles.get(i);
      BigDecimal v = values.get(i);
      if (v != null && inRange(c.bucketStart(), from, to)) {
        points.add(new Point(c.bucketStart().toString(), v.toPlainString()));
      }
    }
    List<NamedSeries> result = List.of(new NamedSeries(name, points));
    cache.put(key, result);
    return result;
  }

  private static boolean inRange(OffsetDateTime t, OffsetDateTime from, OffsetDateTime to) {
    return !t.isBefore(from) && t.isBefore(to);
  }

  /** Catalog defaults overlaid by the caller's params (caller wins). */
  private static Map<String, Object> merge(String name, Map<String, Object> params) {
    Map<String, Object> merged = new LinkedHashMap<>(DEFAULTS.getOrDefault(name, Map.of()));
    if (params != null) {
      merged.putAll(params);
    }
    return merged;
  }

  private static Map<String, Map<String, Object>> defaults() {
    Map<String, Map<String, Object>> d = new LinkedHashMap<>();
    d.put("EMA", Map.of("period", 20));
    d.put("SMA", Map.of("period", 20));
    d.put("RSI", Map.of("period", 14));
    d.put("ADX", Map.of("period", 14));
    d.put("ATR", Map.of("period", 14));
    d.put("MACD_HIST", Map.of("fast", 12, "slow", 26, "signal", 9));
    d.put("SUPERTREND", Map.of("period", 10, "multiplier", 3));
    d.put("VOLUME_RATIO", Map.of("lookback", 20));
    d.put("OI_CHANGE_PCT", Map.of("lookback", 1));
    d.put("ORB_HIGH", Map.of("window_minutes", 15));
    d.put("ORB_LOW", Map.of("window_minutes", 15));
    d.put("RS_VS_INDEX", Map.of("lookback", 63));
    return d;
  }
}
