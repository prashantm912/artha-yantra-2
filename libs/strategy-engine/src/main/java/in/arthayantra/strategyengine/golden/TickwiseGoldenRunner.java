package in.arthayantra.strategyengine.golden;

import in.arthayantra.marketcalendar.MarketCalendar;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The LIVE half of the parity pair (C-2.19): 1m bars arrive ONE AT A TIME — exactly how the live
 * engine sees closed bars off the channel. Entries evaluate at primary bar close (a coarser
 * primary completes when the first bar of the NEXT bucket arrives — the completed-bucket rule
 * the cagg reads use too); the A9 semantics are covered: btst strategies evaluate once per day
 * at the pre-close clock against the deterministic pre-close DAILY view; exit_intrabar
 * strategies evaluate LEVEL exits on every closed 1m bar. The Stage D replay engine must
 * reproduce the emitted events byte-identically through the same serialization.
 *
 * <p>BTST exit caveat (P0-5, 2026-07-12): this runner IS the reference for btst exit semantics
 * (exit_rules evaluated at each subsequent pre-close daily bar, close→close fills) — the live
 * SignalEngine does not yet emit btst exits (its pre-close clock is entry-only); the live port is
 * a tracked follow-up (chip task_3e95fade). Until it lands, btst exits are sim-ahead-of-live by
 * design, not a parity break.
 */
public final class TickwiseGoldenRunner {

  /** Optional rejected-entry diagnostics side-channel; never serialized into the event stream. */
  @FunctionalInterface
  public interface DecisionListener {
    /** Receives one classification for one primary decision bar. */
    void onDecision(
        LocalDate sessionDate,
        OffsetDateTime bucketStart,
        String reason,
        ScoreBreakdown breakdown);
  }

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
    return run(primaryOneMinute, contextCandles, onBar, false);
  }

  /**
   * As {@link #run(List, Map, IntConsumer)} but with the backtest-only {@code relaxSession} lever
   * (false on the live + golden paths): when true the §session ENTRY window / {@code square_off} /
   * expiry-day entry restriction is DISABLED so a backtest fires its signal-driven entries across the
   * FULL series — a functional-smoke lever for a strategy whose intraday clock would otherwise thin the
   * trade list. It deliberately does NOT touch the signal gate or indicator warmup (relaxing those
   * would MANUFACTURE fake trades), so a sparse armed-scalper backtest remains a signal/data-fidelity
   * artifact (judge on live). Default {@code false} keeps every golden fixture byte-identical.
   */
  public List<GoldenSignalsJson.SignalEvent> run(
      List<EngineCandle> primaryOneMinute,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      IntConsumer onBar,
      boolean relaxSession) {
    return run(primaryOneMinute, contextCandles, onBar, relaxSession, null);
  }

  /** As above, with an optional decision-diagnostics side-channel. */
  public List<GoldenSignalsJson.SignalEvent> run(
      List<EngineCandle> primaryOneMinute,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      IntConsumer onBar,
      boolean relaxSession,
      DecisionListener decisionListener) {
    return run(primaryOneMinute, contextCandles, onBar, relaxSession, decisionListener, null);
  }

  /**
   * As above, with a {@code warmupUntil} boundary (AY-SL-03): bars whose decision timestamp is BEFORE
   * {@code warmupUntil} warm the primary/context/indicator state normally but emit NO signal and NEVER
   * open a tracked position — so a fold replayed from the run's data start reaches the fold boundary
   * with WARM indicators yet a clean, position-free state, and every emitted event (entry, exit,
   * square_off, decision-diagnostic) belongs to the in-window portion only. {@code null} (the live +
   * golden + whole-run path) disables suppression → every bar emits, byte-identical to before.
   *
   * <p>The gate is the emit timestamp itself ({@code bar.bucketStart()} — the same instant every path
   * stamps its event with), so it is uniform across the 1m / coarse-primary / btst decision points and
   * deterministic (no wall-clock, no randomness). Suppressing the entry evaluation keeps {@code open}
   * null throughout warmup, so no warmup position can leak across the boundary.
   */
  public List<GoldenSignalsJson.SignalEvent> run(
      List<EngineCandle> primaryOneMinute,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      IntConsumer onBar,
      boolean relaxSession,
      DecisionListener decisionListener,
      OffsetDateTime warmupUntil) {
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
    // Session enforcement (mirrors the live SignalEngine): entries blocked outside the IST window /
    // at-or-after square_off / outside the tighter expiry-day window; an open position is force-closed
    // at square_off. Inert unless the strategy declares those fields → golden fixtures stay byte-identical.
    SessionGate gate = new SessionGate(definition.session(), relaxSession);

    int barIndex = 0;
    for (EngineCandle bar : primaryOneMinute) {
      if (onBar != null) {
        onBar.accept(barIndex);
      }
      barIndex++;
      // AY-SL-03 warmup: bars before the boundary warm state (series appends, context advance,
      // indicator caches) but emit nothing and open no position — only `emit` bars decide/emit.
      boolean emit = warmupUntil == null || !bar.bucketStart().isBefore(warmupUntil);
      advanceContexts(contexts, contextCursor, contextCandles, bar);

      LocalDate barDay = EngineSeries.sessionDate(bar);
      LocalTime barTime = bar.bucketStart().toLocalTime();
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
          if (emit) {
            if (open != null && gate.pastSquareOff(barTime)) {
              events.add(exitEvent(bar.bucketStart(), open, "square_off"));
              open = null;
            }
            if (open != null) {
              Optional<ExitEvaluator.ExitDecision> exit =
                  ExitEvaluator.evaluate(
                      definition, bank,
                      new ExitEvaluator.Position(
                          open.direction(), open.entryPrice(), open.entryPrimaryIndex()),
                      primaryIndex, open.firedTiers());
              if (exit.isPresent()) {
                open = applyExit(open, exit.get(), bar.bucketStart(), events);
              }
            }
            if (open == null) {
              Optional<EntryEvaluator.Evaluation> evaluation =
                  EntryEvaluator.evaluate(definition, bank, primaryIndex);
              boolean entryAllowed =
                  evaluation.isPresent()
                      && evaluation.get().entry()
                      && gate.entryAllowed(barTime, barDay);
              if (decisionListener != null) {
                emitDecision(
                    decisionListener,
                    primary.candle(primaryIndex),
                    bar.bucketStart(),
                    evaluation,
                    entryAllowed);
              }
              if (entryAllowed) {
                events.add(
                    entryEvent(
                        bar.bucketStart(), evaluation.get(),
                        entryLevels(bank, primary, primaryIndex)));
                open = openPosition(primary, primaryIndex, live1m.size(), evaluation.get());
              }
            } else if (decisionListener != null) {
              decisionListener.onDecision(
                  EngineSeries.sessionDate(primary.candle(primaryIndex)),
                  bar.bucketStart(),
                  "position_open",
                  null);
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
            open = applyExit(open, exit.get(), bar.bucketStart(), events);
          }
        }
        continue;
      }

      if (btst) {
        // A9 [FP-6] / P0-5: evaluate once per day at the pre-close clock on the assembled daily view.
        // The pre-close bar OPENS a tracked position (at_close fill — held across the overnight gap that
        // is the whole point of BTST) and the strategy's exit_rules are evaluated at each SUBSEQUENT
        // pre-close bar, so the carry EXITS next-session (e.g. time_stop max_holding_days:1 fires one
        // trading day later) instead of degenerating to a single end-of-data force-close (audit B1).
        // Same exit-before-entry ordering as the coarse-primary path; re-entry allowed once flat.
        LocalTime barClose = bar.bucketStart().toLocalTime().plusMinutes(1);
        if (!preCloseDone && !barClose.isBefore(preCloseAt)) {
          preCloseDone = true;
          primary.append(preCloseDailyBar(dayBuffer));
          int index = primary.size() - 1;
          if (emit) {
            if (open != null) {
              Optional<ExitEvaluator.ExitDecision> exit =
                  ExitEvaluator.evaluate(
                      definition, bank,
                      new ExitEvaluator.Position(
                          open.direction(), open.entryPrice(), open.entryPrimaryIndex()),
                      index, open.firedTiers());
              if (exit.isPresent()) {
                open = applyExit(open, exit.get(), bar.bucketStart(), events);
              }
            }
            if (open == null) {
              Optional<EntryEvaluator.Evaluation> evaluation =
                  EntryEvaluator.evaluate(definition, bank, index);
              boolean entryAllowed =
                  evaluation.isPresent()
                      && evaluation.get().entry()
                      && gate.entryAllowed(barTime, barDay);
              if (decisionListener != null) {
                emitDecision(
                    decisionListener,
                    primary.candle(index),
                    bar.bucketStart(),
                    evaluation,
                    entryAllowed);
              }
              if (entryAllowed) {
                events.add(
                    entryEvent(
                        bar.bucketStart(), evaluation.get(), entryLevels(bank, primary, index)));
                open = openPosition(primary, index, live1m.size() - 1, evaluation.get());
              }
            } else if (decisionListener != null) {
              decisionListener.onDecision(
                  EngineSeries.sessionDate(primary.candle(index)),
                  bar.bucketStart(),
                  "position_open",
                  null);
            }
          }
        }
        continue;
      }

      // 1m primary: exits resolve before a new entry on the same bar
      int index = primary.size() - 1;
      if (!emit) {
        continue;
      }
      if (open != null && gate.pastSquareOff(barTime)) {
        events.add(exitEvent(bar.bucketStart(), open, "square_off"));
        open = null;
      }
      if (open != null) {
        Optional<ExitEvaluator.ExitDecision> exit =
            ExitEvaluator.evaluate(
                definition, bank,
                new ExitEvaluator.Position(
                    open.direction(), open.entryPrice(), open.entryPrimaryIndex()),
                index, open.firedTiers());
        if (exit.isPresent()) {
          open = applyExit(open, exit.get(), bar.bucketStart(), events);
        }
      }
      if (open == null) {
        Optional<EntryEvaluator.Evaluation> evaluation =
            EntryEvaluator.evaluate(definition, bank, index);
        boolean entryAllowed =
            evaluation.isPresent()
                && evaluation.get().entry()
                && gate.entryAllowed(barTime, barDay);
        if (decisionListener != null) {
          emitDecision(
              decisionListener, primary.candle(index), bar.bucketStart(), evaluation, entryAllowed);
        }
        if (entryAllowed) {
          events.add(
              entryEvent(bar.bucketStart(), evaluation.get(), entryLevels(bank, primary, index)));
          open = openPosition(primary, index, live1m.size() - 1, evaluation.get());
        }
      } else if (decisionListener != null) {
        decisionListener.onDecision(
            EngineSeries.sessionDate(primary.candle(index)),
            bar.bucketStart(),
            "position_open",
            null);
      }
    }
    return events;
  }

  private static void emitDecision(
      DecisionListener listener,
      EngineCandle decisionBar,
      OffsetDateTime bucketStart,
      Optional<EntryEvaluator.Evaluation> evaluation,
      boolean entryAllowed) {
    if (evaluation.isEmpty()) {
      listener.onDecision(
          EngineSeries.sessionDate(decisionBar), bucketStart, "not_evaluable", null);
      return;
    }
    EntryEvaluator.Evaluation value = evaluation.get();
    String reason;
    if (value.entry()) {
      reason = entryAllowed ? "entered" : "session_window";
    } else {
      reason = value.breakdown().gate().passed() ? "composite_below_threshold" : "gate_fail";
    }
    listener.onDecision(
        EngineSeries.sessionDate(decisionBar), bucketStart, reason, value.breakdown());
  }

  private void advanceContexts(
      Map<SeriesKey, EngineSeries> contexts,
      Map<SeriesKey, Integer> cursors,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      EngineCandle bar) {
    // P1-9 (audit B7): a context bar is visible only once its bucket END has passed the current 1m
    // bar's CLOSE — the completed-bucket contract the live engine adopted for coarse buckets (#683/B1).
    // A 1d context bar is stamped at the session's 00:00 IST START, so under the old start-gate it went
    // visible from that day's FIRST bar and a coarse/1m primary could read TODAY's still-forming daily
    // close intraday (the look-ahead). End-gating makes it visible only from the NEXT session. The 1m
    // bar's close is bucketStart+1m (the tick-wise stream is always 1m); for a 1m context this reduces
    // EXACTLY to the old `start <= barStart` rule, so same-timeframe/1m contexts (every committed golden)
    // stay byte-identical. Instant-keyed per #214 (never OffsetDateTime equality across +05:30 / +00).
    Instant barClose = bar.bucketStart().toInstant().plus(Duration.ofMinutes(1));
    for (Map.Entry<SeriesKey, EngineSeries> e : contexts.entrySet()) {
      List<EngineCandle> source = contextCandles.get(e.getKey());
      String interval = e.getKey().interval();
      int cursor = cursors.get(e.getKey());
      while (cursor < source.size()
          && contextBarComplete(source.get(cursor).bucketStart().toInstant(), interval, barClose)) {
        e.getValue().append(source.get(cursor));
        cursor++;
      }
      cursors.put(e.getKey(), cursor);
    }
  }

  /**
   * Completed-bucket visibility (P1-9): a context bar {@code [start, start+interval)} is readable only
   * once its END has passed the current 1m bar's close — so an in-progress bucket (a still-forming 1d
   * bar) is invisible until the session that closes it. For a 1m context this is identical to the old
   * {@code start <= barStart} rule (end = start+1m, close = barStart+1m), keeping same-timeframe/1m
   * contexts byte-identical. Package-visible so the predicate is unit-tested directly.
   */
  static boolean contextBarComplete(Instant ctxBarStart, String interval, Instant currentBarClose) {
    return !ctxBarStart.plus(contextBucketDuration(interval)).isAfter(currentBarClose);
  }

  /**
   * Context bucket width for {@link #contextBarComplete}. Covers every interval a context series can
   * carry — a CROSS-INSTRUMENT context may be 1m (and 1w exists), unlike a rolled-up primary. Kept
   * SEPARATE from {@link #intervalDuration}, which validates the roll-up PRIMARY and deliberately
   * rejects 1m/1w (a 1w roll-up would mis-align to a Thursday epoch week; 1m is the non-coarse path).
   */
  private static Duration contextBucketDuration(String interval) {
    return switch (interval) {
      case "1m" -> Duration.ofMinutes(1);
      case "3m" -> Duration.ofMinutes(3);
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      case "1d" -> Duration.ofDays(1);
      case "1w" -> Duration.ofDays(7);
      default -> throw new IllegalArgumentException("unsupported context interval " + interval);
    };
  }

  private IndicatorBank bank(SeriesProvider provider) {
    return IndicatorBank.build(
        definition, new StrategyDefinition.InstrumentRef(exchange, tradingsymbol), provider);
  }

  private GoldenSignalsJson.SignalEvent entryEvent(
      OffsetDateTime at, EntryEvaluator.Evaluation evaluation, ExitEvaluator.EntryLevels levels) {
    String direction =
        definition.direction() == StrategyDefinition.Direction.SHORT ? "SHORT" : "LONG";
    return new GoldenSignalsJson.SignalEvent(
        at.toString(), exchange, tradingsymbol, direction, evaluation.breakdown(),
        levels.stopLoss(), levels.takeProfit());
  }

  private GoldenSignalsJson.SignalEvent exitEvent(
      OffsetDateTime at, OpenPosition open, String exitType) {
    return exitEvent(at, open, open.remainingFraction(), exitType);
  }

  private GoldenSignalsJson.SignalEvent exitEvent(
      OffsetDateTime at, OpenPosition open, BigDecimal qtyFraction, String exitType) {
    return new GoldenSignalsJson.SignalEvent(
        at.toString(), exchange, tradingsymbol, "EXIT", open.entryBreakdown(), null, null,
        qtyFraction, exitType);
  }

  /** Entry-time protective levels at {@code primaryIndex} (both {@code null} when no such rule). */
  private ExitEvaluator.EntryLevels entryLevels(
      IndicatorBank bank, EngineSeries primary, int primaryIndex) {
    ExitEvaluator.Direction direction =
        definition.direction() == StrategyDefinition.Direction.SHORT
            ? ExitEvaluator.Direction.SHORT
            : ExitEvaluator.Direction.LONG;
    return ExitEvaluator.entryLevels(
        definition,
        bank,
        new ExitEvaluator.Position(direction, primary.candle(primaryIndex).close(), primaryIndex));
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
        evaluation.breakdown(),
        BigDecimal.ONE,
        Set.of());
  }

  /**
   * Applies an exit decision to the open position, emitting an EXIT event for the fraction closed.
   * A full-close decision (any protective/time/signal exit, {@code tier < 0}) closes the whole
   * REMAINING fraction and returns {@code null}. A scaled tier closes its {@code qty_pct} of the
   * original (capped at the remainder), records the tier so it never re-fires, and returns the
   * reduced position — {@code null} once the remainder is exhausted. Parity: an un-tiered strategy
   * only ever sees a full close with {@code remainingFraction == 1}, so the emitted event stream is
   * byte-identical to before.
   */
  private OpenPosition applyExit(
      OpenPosition open, ExitEvaluator.ExitDecision decision, OffsetDateTime at,
      List<GoldenSignalsJson.SignalEvent> events) {
    boolean fullClose = decision.tier() < 0;
    BigDecimal legFraction =
        fullClose ? open.remainingFraction() : decision.qtyFraction().min(open.remainingFraction());
    events.add(exitEvent(at, open, legFraction, decision.type()));
    BigDecimal remaining = open.remainingFraction().subtract(legFraction);
    if (fullClose || remaining.signum() <= 0) {
      return null;
    }
    Set<Integer> fired = new HashSet<>(open.firedTiers());
    fired.add(decision.tier());
    return new OpenPosition(
        open.direction(), open.entryPrice(), open.entryPrimaryIndex(),
        open.entryOneMinuteIndex(), open.entryBreakdown(), remaining, fired);
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
      case "3m" -> Duration.ofMinutes(3); // the Siva scalper execution clock (candles_3m); 09:15 IST aligns
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      case "1d" -> Duration.ofDays(1); // Minervini swing primary (MV-6.2); epoch-floor aligns to the IST day
      default -> throw new IllegalArgumentException(
          "tick-wise golden runner rolls up 3m/5m/15m/1h/1d primaries; got " + interval);
    };
  }

  private record OpenPosition(
      ExitEvaluator.Direction direction,
      BigDecimal entryPrice,
      int entryPrimaryIndex,
      int entryOneMinuteIndex,
      ScoreBreakdown entryBreakdown,
      BigDecimal remainingFraction,
      Set<Integer> firedTiers) {}

  /**
   * The IST session-time gate (parity-inert unless declared). Entries are allowed only inside the
   * window and before {@code square_off}; on an index-expiry day the optional tighter expiry window
   * applies (or all entries are blocked when {@code expiry_day.allowed=false}). The NSE calendar is
   * loaded ONLY when an {@code expiry_day} block is present, so the common path takes no calendar.
   */
  static final class SessionGate {
    private final LocalTime windowFrom;
    private final LocalTime windowTo;
    private final LocalTime squareOff;
    private final LocalTime expiryFrom;
    private final LocalTime expiryTo;
    private final Boolean expiryDayAllowed;
    private final MarketCalendar calendar; // null unless an expiry_day block was declared
    // Backtest-only: when true the entry clock rail is OFF (entries fire all session) and the square_off
    // force-close is disabled (positions exit purely on their own exit_rules). false on live + goldens.
    private final boolean relax;
    // MV-6.2: swing style holds multi-day — the intraday square_off never force-closes it (a daily
    // primary already never reaches square_off at bucket close, but this makes the semantics explicit
    // and correct on ANY primary a swing strategy might use). No-op for the intraday/btst goldens.
    private final boolean swing;

    SessionGate(StrategyDefinition.Session s, boolean relax) {
      this.windowFrom = parse(s.windowFrom());
      this.windowTo = parse(s.windowTo());
      this.squareOff = parse(s.squareOff());
      this.expiryFrom = parse(s.expiryWindowFrom());
      this.expiryTo = parse(s.expiryWindowTo());
      this.expiryDayAllowed = s.expiryDayAllowed();
      boolean expiryDeclared =
          s.expiryDayAllowed() != null || expiryFrom != null || expiryTo != null;
      this.calendar = expiryDeclared ? MarketCalendar.nse() : null;
      this.relax = relax;
      this.swing = "swing".equals(s.style());
    }

    private static LocalTime parse(String v) {
      return v == null ? null : LocalTime.parse(v);
    }

    boolean entryAllowed(LocalTime t, LocalDate day) {
      if (relax) {
        return true; // backtest relax-session: the intraday clock never blocks an entry
      }
      boolean expiryDay = calendar != null && calendar.isWeeklyIndexExpiryDay(day);
      if (expiryDay && Boolean.FALSE.equals(expiryDayAllowed)) {
        return false; // strategy opts out of expiry-day entries entirely
      }
      LocalTime from = expiryDay && expiryFrom != null ? expiryFrom : windowFrom;
      LocalTime to = expiryDay && expiryTo != null ? expiryTo : windowTo;
      if (from != null && t.isBefore(from)) {
        return false;
      }
      if (to != null && !t.isBefore(to)) {
        return false;
      }
      return squareOff == null || t.isBefore(squareOff);
    }

    boolean pastSquareOff(LocalTime t) {
      return !relax && !swing && squareOff != null && !t.isBefore(squareOff);
    }
  }
}
