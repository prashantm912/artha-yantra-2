package in.arthayantra.strategyengine.eval;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.indicators.EngineIndicator;
import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All indicator instances of one (strategy, signal instrument) pair, alias-addressable, with
 * the multi-timeframe and A7 context-override resolution applied once at build time. Values are
 * read AT the primary bar: an additional-timeframe indicator reads its series' last COMPLETED
 * bar at the primary bar's close (a half-built 5m bucket never leaks into a 1m evaluation —
 * identical rule in live and replay).
 */
public final class IndicatorBank implements BarValues {

  /** One bound instance: the indicator + the series it evaluates on. */
  public record Bound(
      StrategyDefinition.IndicatorSpec spec, EngineIndicator indicator, EngineSeries series) {}

  private final EngineSeries primarySeries;
  private final String primaryTimeframe;
  private final Map<String, Bound> byAlias = new LinkedHashMap<>();
  private final EngineIndicator builtinVwap;

  private IndicatorBank(
      EngineSeries primarySeries, String primaryTimeframe, EngineIndicator builtinVwap) {
    this.primarySeries = primarySeries;
    this.primaryTimeframe = primaryTimeframe;
    this.builtinVwap = builtinVwap;
  }

  /**
   * Builds the bank for a signal instrument. {@code signal} is the instrument being evaluated;
   * series (own timeframes + context overrides) resolve through {@code provider}.
   */
  public static IndicatorBank build(
      StrategyDefinition definition,
      StrategyDefinition.InstrumentRef signal,
      SeriesProvider provider) {
    EngineSeries primary =
        provider.series(
            new SeriesKey(
                signal.exchange(), signal.tradingsymbol(), definition.primaryTimeframe()));
    if (primary == null) {
      throw new IllegalStateException(
          "no primary series for " + signal.exchange() + ":" + signal.tradingsymbol());
    }
    IndicatorBank bank =
        new IndicatorBank(
            primary,
            definition.primaryTimeframe(),
            IndicatorRegistry.create("VWAP", primary, null, Map.of()));
    for (StrategyDefinition.IndicatorSpec spec : definition.indicators()) {
      StrategyDefinition.InstrumentRef target =
          spec.instrument() != null ? spec.instrument() : signal;
      EngineSeries own =
          provider.series(
              new SeriesKey(target.exchange(), target.tradingsymbol(), spec.timeframe()));
      EngineSeries context = null;
      if (IndicatorRegistry.definition(spec.name()).requiresContext()) {
        // context-mechanism indicators evaluate ON the signal series AGAINST the override series
        context = own;
        own =
            provider.series(
                new SeriesKey(signal.exchange(), signal.tradingsymbol(), spec.timeframe()));
      }
      if (own == null) {
        throw new IllegalStateException("no series coverage for indicator " + spec.alias());
      }
      EngineIndicator indicator =
          IndicatorRegistry.create(spec.name(), own, context, spec.params());
      bank.byAlias.put(spec.alias(), new Bound(spec, indicator, own));
    }
    return bank;
  }

  /** The primary series. */
  public EngineSeries primarySeries() {
    return primarySeries;
  }

  /** Bound instance by alias. */
  public Bound bound(String alias) {
    Bound bound = byAlias.get(alias);
    if (bound == null) {
      throw new IllegalArgumentException("alias '" + alias + "' is not in the bank");
    }
    return bound;
  }

  /** All bound instances in declaration order. */
  public Map<String, Bound> all() {
    return byAlias;
  }

  /** Value of an alias AT a primary bar index (cross-timeframe mapped). */
  @Override
  public BigDecimal valueAt(String alias, int primaryIndex) {
    Bound bound = bound(alias);
    int index = mappedIndex(bound, primaryIndex);
    return index < 0 ? null : bound.indicator().valueAt(index);
  }

  /** Value of an alias at the bar BEFORE the mapped one (crossover previous-bar leg). */
  @Override
  public BigDecimal previousValueAt(String alias, int primaryIndex) {
    Bound bound = bound(alias);
    int index = mappedIndex(bound, primaryIndex);
    return index < 1 ? null : bound.indicator().valueAt(index - 1);
  }

  /** Built-in operand values (close, volume, vwap) at a primary index. */
  @Override
  public BigDecimal builtin(String name, int primaryIndex) {
    return switch (name) {
      case "close" -> primarySeries.candle(primaryIndex).close();
      case "volume" -> BigDecimal.valueOf(primarySeries.candle(primaryIndex).volume());
      case "vwap" -> builtinVwap.valueAt(primaryIndex);
      default -> null;
    };
  }

  private int mappedIndex(Bound bound, int primaryIndex) {
    if (bound.series() == primarySeries
        && bound.spec().timeframe().equals(primaryTimeframe)) {
      return primaryIndex;
    }
    // last COMPLETED bar of the other series at the primary bar's close
    Instant primaryEnd =
        primarySeries.candle(primaryIndex).bucketStart().toInstant()
            .plus(intervalOf(primaryTimeframe));
    Instant latestStart = primaryEnd.minus(intervalOf(bound.spec().timeframe()));
    return bound.series().indexAtOrBefore(latestStart);
  }

  private static Duration intervalOf(String interval) {
    return switch (interval) {
      case "1m" -> Duration.ofMinutes(1);
      case "3m" -> Duration.ofMinutes(3);
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      case "1d" -> Duration.ofDays(1);
      case "1w" -> Duration.ofDays(7);
      default -> throw new IllegalArgumentException("unsupported interval " + interval);
    };
  }
}
