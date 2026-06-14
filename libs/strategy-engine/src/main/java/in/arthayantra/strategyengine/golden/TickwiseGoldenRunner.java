package in.arthayantra.strategyengine.golden;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.eval.ScoreBreakdown;
import in.arthayantra.strategyengine.eval.SeriesProvider;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * The LIVE half of the parity pair (C-2.19): 1m bars arrive ONE AT A TIME — exactly how the live
 * engine sees closed bars off the channel. Entries evaluate at primary bar close (a coarser
 * primary completes when the first bar of the NEXT bucket arrives — the completed-bucket rule
 * the cagg reads use too); the A9 semantics are covered: btst strategies evaluate once per day
 * at the pre-close clock against the deterministic pre-close DAILY view; exit_intrabar
 * strategies evaluate LEVEL exits on every closed 1m bar. The Stage D replay engine must
 * reproduce the emitted events byte-identically through the same serialization.
 */
public final class TickwiseGoldenRunner {

  private final StrategyDefinition definition;
  private final String exchange;
  private final String tradingsymbol;

  /** Bound to one strategy + the signal instrument. */
  public TickwiseGoldenRunner(
      StrategyDefinition definition, String exchange, String tradingsymbol) {
    this.definition = definition;
    this.exchange = exchange;
    this.tradingsymbol = tradingsymbol;
  }

  /** Feeds 1m candles tick-wise; context series advance in lock-step by timestamp. */
  public List<GoldenSignalsJson.SignalEvent> run(
      List<EngineCandle> primaryOneMinute, Map<SeriesKey, List<EngineCandle>> contextCandles) {
    return run(primaryOneMinute, contextCandles, null);
  }

  /**
   * As {@link #run(List, Map)} but reports the 0-based primary-bar index to {@code onBar} each tick
   * (D17b progress; nullable). A pure side-channel — it never touches the emitted events, so the
   * golden vectors stay byte-identical (the no-arg overload passes {@code null}).
   */
  public List<GoldenSignalsJson.SignalEvent> run(
      List<EngineCandle> primaryOneMinute,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      IntConsumer onBar) {
    boolean btst = "btst".equals(definition.session().style());
    boolean coarsePrimary = !definition.primaryTimeframe().equals("1m") && !btst;
    EngineSeries live1m = new EngineSeries(new SeriesKey(exchange, tradingsymbol, "1m"));
    EngineSeries primary =
        definition.primaryTimeframe().equals("1m")
            ? live1m
            : new EngineSeries(
                new SeriesKey(exchange, tradingsymbol, definition.primaryTimeframe()));
    Map<SeriesKey, EngineSeries> contexts = new LinkedHashMap<>();
    Map<SeriesKey, Integer> contextCursor = new LinkedHashMap<>();
    contextCandles.forEach(
        (key, candles) -> {
          contexts.put(key, new EngineSeries(key));
          contextCursor.put(key, 0);
        });

    SeriesProvider provider =
        key -> {
          if (key.tradingsymbol().equals(tradingsymbol) && key.exchange().equals(exchange)) {
            if (key.interval().equals(definition.primaryTimeframe())) {
              return primary;
            }
            if (key.interval().equals("1m")) {
              return live1m;
            }
          }
          for (Map.Entry<SeriesKey, EngineSeries> e : contexts.entrySet()) {
            if (e.getKey().exchange().equals(key.exchange())
                && e.getKey().tradingsymbol().equals(key.tradingsymbol())) {
              return e.getValue();
            }
          }
          return null;
        };

    // Build the indicator bank ONCE: the series resolved through `provider` (primary/live1m/
    // contexts) are stable instances that grow as bars append, so the bound ta4j indicators see
    // each appended bar with a WARM cache. Rebuilding the bank per bar gave every tick a COLD
    // ta4j cache, re-prefilling the whole history from scratch — O(n^2) over the run (D17). The
    // emitted events are unchanged: indicators are pure functions of (series, index).
    IndicatorBank bank = bank(provider);

    List<GoldenSignalsJson.SignalEvent> events = new ArrayList<>();
    OpenPosition open = null;
    LocalDate currentDay = null;
    List<EngineCandle> dayBuffer = new ArrayList<>();
    List<EngineCandle> bucketBuffer = new ArrayList<>();
    Instant currentBucketFloor = null;
    boolean preCloseDone = false;
    LocalTime preCloseAt = LocalTime.parse(definition.session().preCloseAt());
    Duration primaryDuration =
        coarsePrimary ? intervalDuration(definition.primaryTimeframe()) : Duration.ofMinutes(1);

    int barIndex = 0;
    for (EngineCandle bar : primaryOneMinute) {
      if (onBar != null) {
        onBar.accept(barIndex);
      }
      barIndex++;
      advanceContexts(contexts, contextCursor, contextCandles, bar);

      LocalDate barDay = EngineSeries.sessionDate(bar);
      if (!barDay.equals(currentDay)) {
        currentDay = barDay;
        dayBuffer.clear();
        preCloseDone = false;
      }

      if (coarsePrimary) {
        Instant floor = bucketFloor(bar.bucketStart(), primaryDuration);
        if (currentBucketFloor != null && !floor.equals(currentBucketFloor)
            && !bucketBuffer.isEmpty()) {
          primary.append(aggregate(bucketBuffer, currentBucketFloor));
          bucketBuffer.clear();
          int primaryIndex = primary.size() - 1;
          if (open != null) {
            Optional<ExitEvaluator.ExitDecision> exit =
                ExitEvaluator.evaluate(
                    definition, bank,
                    new ExitEvaluator.Position(
                        open.direction(), open.entryPrice(), open.entryPrimaryIndex()),
                    primaryIndex);
            if (exit.isPresent()) {
              events.add(exitEvent(bar.bucketStart(), open));
              open = null;
            }
          }
          if (open == null) {
            Optional<EntryEvaluator.Evaluation> evaluation =
                EntryEvaluator.evaluate(definition, bank, primaryIndex);
            if (evaluation.isPresent() && evaluation.get().entry()) {
              events.add(entryEvent(bar.bucketStart(), evaluation.get()));
              open = openPosition(primary, primaryIndex, live1m.size(), evaluation.get());
            }
          }
        }
        currentBucketFloor = floor;
      }

      live1m.append(bar);
      dayBuffer.add(bar);
      if (coarsePrimary) {
        bucketBuffer.add(bar);
        // A9 [FP-5]: level exits on every closed 1m bar while a position is open
        if (open != null && definition.session().exitIntrabar()) {
          Optional<ExitEvaluator.ExitDecision> exit =
              ExitEvaluator.evaluateIntrabarLevels(
                  definition, primary, open.entryPrimaryIndex(), live1m,
                  open.direction(), open.entryPrice(), open.entryOneMinuteIndex(),
                  live1m.size() - 1);
          if (exit.isPresent()) {
            events.add(exitEvent(bar.bucketStart(), open));
            open = null;
          }
        }
        continue;
      }

      if (btst) {
        // A9 [FP-6]: evaluate once per day at the pre-close clock on the assembled daily view
        LocalTime barClose = bar.bucketStart().toLocalTime().plusMinutes(1);
        if (!preCloseDone && !barClose.isBefore(preCloseAt)) {
          preCloseDone = true;
          primary.append(preCloseDailyBar(dayBuffer));
          Optional<EntryEvaluator.Evaluation> evaluation =
              EntryEvaluator.evaluate(definition, bank, primary.size() - 1);
          if (evaluation.isPresent() && evaluation.get().entry()) {
            events.add(entryEvent(bar.bucketStart(), evaluation.get()));
          }
        }
        continue;
      }

      // 1m primary: exits resolve before a new entry on the same bar
      int index = primary.size() - 1;
      if (open != null) {
        Optional<ExitEvaluator.ExitDecision> exit =
            ExitEvaluator.evaluate(
                definition, bank,
                new ExitEvaluator.Position(
                    open.direction(), open.entryPrice(), open.entryPrimaryIndex()),
                index);
        if (exit.isPresent()) {
          events.add(exitEvent(bar.bucketStart(), open));
          open = null;
        }
      }
      if (open == null) {
        Optional<EntryEvaluator.Evaluation> evaluation =
            EntryEvaluator.evaluate(definition, bank, index);
        if (evaluation.isPresent() && evaluation.get().entry()) {
          events.add(entryEvent(bar.bucketStart(), evaluation.get()));
          open = openPosition(primary, index, live1m.size() - 1, evaluation.get());
        }
      }
    }
    return events;
  }

  private void advanceContexts(
      Map<SeriesKey, EngineSeries> contexts,
      Map<SeriesKey, Integer> cursors,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      EngineCandle bar) {
    for (Map.Entry<SeriesKey, EngineSeries> e : contexts.entrySet()) {
      List<EngineCandle> source = contextCandles.get(e.getKey());
      int cursor = cursors.get(e.getKey());
      while (cursor < source.size()
          && !source.get(cursor).bucketStart().toInstant()
              .isAfter(bar.bucketStart().toInstant())) {
        e.getValue().append(source.get(cursor));
        cursor++;
      }
      cursors.put(e.getKey(), cursor);
    }
  }

  private IndicatorBank bank(SeriesProvider provider) {
    return IndicatorBank.build(
        definition, new StrategyDefinition.InstrumentRef(exchange, tradingsymbol), provider);
  }

  private GoldenSignalsJson.SignalEvent entryEvent(
      OffsetDateTime at, EntryEvaluator.Evaluation evaluation) {
    String direction =
        definition.direction() == StrategyDefinition.Direction.SHORT ? "SHORT" : "LONG";
    return new GoldenSignalsJson.SignalEvent(
        at.toString(), exchange, tradingsymbol, direction, evaluation.breakdown());
  }

  private GoldenSignalsJson.SignalEvent exitEvent(OffsetDateTime at, OpenPosition open) {
    return new GoldenSignalsJson.SignalEvent(
        at.toString(), exchange, tradingsymbol, "EXIT", open.entryBreakdown());
  }

  private OpenPosition openPosition(
      EngineSeries primary, int primaryIndex, int oneMinuteIndex,
      EntryEvaluator.Evaluation evaluation) {
    return new OpenPosition(
        definition.direction() == StrategyDefinition.Direction.SHORT
            ? ExitEvaluator.Direction.SHORT
            : ExitEvaluator.Direction.LONG,
        primary.candle(primaryIndex).close(),
        primaryIndex,
        oneMinuteIndex,
        evaluation.breakdown());
  }

  /** The deterministic pre-close bar view (A9): OHLC of the session's 1m bars so far. */
  public static EngineCandle preCloseDailyBar(List<EngineCandle> dayBars) {
    EngineCandle first = dayBars.get(0);
    BigDecimal high = first.high();
    BigDecimal low = first.low();
    long volume = 0;
    for (EngineCandle bar : dayBars) {
      if (bar.high().compareTo(high) > 0) {
        high = bar.high();
      }
      if (bar.low().compareTo(low) < 0) {
        low = bar.low();
      }
      volume += bar.volume();
    }
    EngineCandle last = dayBars.get(dayBars.size() - 1);
    return new EngineCandle(
        first.bucketStart().toLocalDate().atStartOfDay().atOffset(EngineSeries.IST),
        first.open(), high, low, last.close(), volume);
  }

  /** Roll-up of one completed coarse bucket (first/max/min/last/sum — the cagg shape). */
  static EngineCandle aggregate(List<EngineCandle> bars, Instant bucketFloor) {
    EngineCandle first = bars.get(0);
    BigDecimal high = first.high();
    BigDecimal low = first.low();
    long volume = 0;
    for (EngineCandle bar : bars) {
      if (bar.high().compareTo(high) > 0) {
        high = bar.high();
      }
      if (bar.low().compareTo(low) < 0) {
        low = bar.low();
      }
      volume += bar.volume();
    }
    EngineCandle last = bars.get(bars.size() - 1);
    return new EngineCandle(
        OffsetDateTime.ofInstant(bucketFloor, EngineSeries.IST),
        first.open(), high, low, last.close(), volume);
  }

  private static Instant bucketFloor(OffsetDateTime bucketStart, Duration interval) {
    long seconds = interval.toSeconds();
    long epoch = bucketStart.toEpochSecond();
    return Instant.ofEpochSecond(epoch - Math.floorMod(epoch, seconds));
  }

  private static Duration intervalDuration(String interval) {
    return switch (interval) {
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      default -> throw new IllegalArgumentException(
          "tick-wise golden runner rolls up 5m/15m/1h primaries; got " + interval);
    };
  }

  private record OpenPosition(
      ExitEvaluator.Direction direction,
      BigDecimal entryPrice,
      int entryPrimaryIndex,
      int entryOneMinuteIndex,
      ScoreBreakdown entryBreakdown) {}
}
