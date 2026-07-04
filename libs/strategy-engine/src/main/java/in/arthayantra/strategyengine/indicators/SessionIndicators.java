package in.arthayantra.strategyengine.indicators;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The session-level family (A7 [FP-18]) + volume/OI ratios + context-mechanism indicators
 * (A7 [FP-20]/[FP-14]). Session boundaries are IST trading sessions read off bar timestamps —
 * never calendar/UTC days. Warm-up rule: anything needing a prior session returns null on the
 * first covered day (documented — strategies referencing PREV_DAY_x / GAP_PCT need at least one
 * prior trading day of coverage).
 */
final class SessionIndicators {

  private SessionIndicators() {}

  /** Cumulative session VWAP: sum(typical*vol)/sum(vol) from session start. */
  static EngineIndicator sessionVwap(EngineSeries series) {
    return indicator(
        0,
        series,
        index -> {
          int start = series.sessionStart(index);
          BigDecimal pv = BigDecimal.ZERO;
          BigDecimal volume = BigDecimal.ZERO;
          for (int i = start; i <= index; i++) {
            EngineCandle c = series.candle(i);
            BigDecimal typical =
                c.high().add(c.low()).add(c.close()).divide(BigDecimal.valueOf(3), EngineMath.MC);
            BigDecimal vol = BigDecimal.valueOf(c.volume());
            pv = pv.add(typical.multiply(vol, EngineMath.MC));
            volume = volume.add(vol);
          }
          if (volume.signum() == 0) {
            EngineCandle c = series.candle(index);
            return c.high().add(c.low()).add(c.close())
                .divide(BigDecimal.valueOf(3), EngineMath.MC);
          }
          return pv.divide(volume, EngineMath.MC);
        });
  }

  /** Volume-weighted MA: sum(close*vol, n) / sum(vol, n) over the trailing n bars (rolling,
   * NOT session-cumulative — VWMA(20) is the last 20 bars regardless of session). */
  static EngineIndicator vwma(EngineSeries series, int period) {
    return indicator(
        period - 1, // unstable: needs `period` bars (index >= period-1)
        series,
        index -> {
          BigDecimal pv = BigDecimal.ZERO;
          BigDecimal vol = BigDecimal.ZERO;
          for (int i = index - period + 1; i <= index; i++) {
            EngineCandle c = series.candle(i);
            BigDecimal v = BigDecimal.valueOf(c.volume());
            pv = pv.add(c.close().multiply(v, EngineMath.MC));
            vol = vol.add(v);
          }
          return vol.signum() == 0 ? null : pv.divide(vol, EngineMath.MC);
        });
  }

  /** Opening-range high/low: defined once the first {@code windowMinutes} of the session end. */
  static EngineIndicator openingRange(EngineSeries series, int windowMinutes, boolean high) {
    return indicator(
        0,
        series,
        index -> {
          int start = series.sessionStart(index);
          Instant windowEnd =
              series.candle(start).bucketStart().toInstant().plusSeconds(windowMinutes * 60L);
          if (series.candle(index).bucketStart().toInstant().isBefore(windowEnd)) {
            return null; // still inside the opening range
          }
          BigDecimal extreme = null;
          for (int i = start; i <= index; i++) {
            EngineCandle c = series.candle(i);
            if (!c.bucketStart().toInstant().isBefore(windowEnd)) {
              break;
            }
            BigDecimal v = high ? c.high() : c.low();
            if (extreme == null || (high ? v.compareTo(extreme) > 0 : v.compareTo(extreme) < 0)) {
              extreme = v;
            }
          }
          return extreme;
        });
  }

  /** PREV_DAY_HIGH / PREV_DAY_LOW / PREV_DAY_CLOSE. */
  enum PrevDayField {
    HIGH,
    LOW,
    CLOSE
  }

  static EngineIndicator previousDay(EngineSeries series, PrevDayField field) {
    return indicator(
        0,
        series,
        index -> {
          int prevEnd = series.previousSessionEnd(index);
          if (prevEnd < 0) {
            return null; // no prior session in coverage (documented warm-up rule)
          }
          int prevStart = series.sessionStart(prevEnd);
          return switch (field) {
            case CLOSE -> series.candle(prevEnd).close();
            case HIGH -> {
              BigDecimal max = series.candle(prevStart).high();
              for (int i = prevStart + 1; i <= prevEnd; i++) {
                if (series.candle(i).high().compareTo(max) > 0) {
                  max = series.candle(i).high();
                }
              }
              yield max;
            }
            case LOW -> {
              BigDecimal min = series.candle(prevStart).low();
              for (int i = prevStart + 1; i <= prevEnd; i++) {
                if (series.candle(i).low().compareTo(min) < 0) {
                  min = series.candle(i).low();
                }
              }
              yield min;
            }
          };
        });
  }

  /** Running session extreme up to and including the current bar. */
  static EngineIndicator dayExtreme(EngineSeries series, boolean high) {
    return indicator(
        0,
        series,
        index -> {
          int start = series.sessionStart(index);
          BigDecimal extreme = high ? series.candle(start).high() : series.candle(start).low();
          for (int i = start + 1; i <= index; i++) {
            BigDecimal v = high ? series.candle(i).high() : series.candle(i).low();
            if (high ? v.compareTo(extreme) > 0 : v.compareTo(extreme) < 0) {
              extreme = v;
            }
          }
          return extreme;
        });
  }

  /** Gap percent: (session open - previous session close) / previous close * 100. */
  static EngineIndicator gapPct(EngineSeries series) {
    return indicator(
        0,
        series,
        index -> {
          int prevEnd = series.previousSessionEnd(index);
          if (prevEnd < 0) {
            return null;
          }
          BigDecimal prevClose = series.candle(prevEnd).close();
          if (prevClose.signum() == 0) {
            return null;
          }
          BigDecimal open = series.candle(series.sessionStart(index)).open();
          return open.subtract(prevClose)
              .divide(prevClose, EngineMath.MC)
              .multiply(EngineMath.HUNDRED, EngineMath.MC);
        });
  }

  /** Current volume vs the mean of the PRIOR {@code lookback} bars' volumes. */
  static EngineIndicator volumeRatio(EngineSeries series, int lookback) {
    return indicator(
        lookback,
        series,
        index -> {
          BigDecimal sum = BigDecimal.ZERO;
          for (int i = index - lookback; i < index; i++) {
            sum = sum.add(BigDecimal.valueOf(series.candle(i).volume()));
          }
          if (sum.signum() == 0) {
            return null;
          }
          BigDecimal mean = sum.divide(BigDecimal.valueOf(lookback), EngineMath.MC);
          return BigDecimal.valueOf(series.candle(index).volume()).divide(mean, EngineMath.MC);
        });
  }

  /** Open-interest change percent over {@code lookback} bars (null without OI). */
  static EngineIndicator oiChangePct(EngineSeries series, int lookback) {
    return indicator(
        lookback,
        series,
        index -> {
          BigDecimal now = series.oiAt(index);
          BigDecimal base = series.oiAt(index - lookback);
          if (now == null || base == null || base.signum() == 0) {
            return null;
          }
          return now.subtract(base)
              .divide(base, EngineMath.MC)
              .multiply(EngineMath.HUNDRED, EngineMath.MC);
        });
  }

  /**
   * Relative strength vs a context index (A7 [FP-20]): own lookback return minus the index's
   * lookback return, in percentage points. Context bars align by bucket time (at-or-before).
   */
  static EngineIndicator rsVsIndex(EngineSeries series, EngineSeries context, int lookback) {
    return indicator(
        lookback,
        series,
        index -> {
          BigDecimal own = lookbackReturnPct(series, index, lookback);
          int ctxIndex = context.indexAtOrBefore(series.candle(index).bucketStart().toInstant());
          if (own == null || ctxIndex < lookback) {
            return null;
          }
          BigDecimal ctx = lookbackReturnPct(context, ctxIndex, lookback);
          return ctx == null ? null : own.subtract(ctx);
        });
  }

  /** The raw context-series close (A7 [FP-14] — INDIA VIX level via the override mechanism). */
  static EngineIndicator contextLevel(EngineSeries series, EngineSeries context) {
    return indicator(
        0,
        series,
        index -> {
          int ctxIndex = context.indexAtOrBefore(series.candle(index).bucketStart().toInstant());
          return ctxIndex < 0 ? null : context.candle(ctxIndex).close();
        });
  }

  /**
   * Highest high over the {@code period} bars ENDING AT THE PRIOR bar (excludes the current bar) —
   * the resistance a new-high breakout must clear (Minervini MV-6.6 primary-base). {@code close >
   * WEEK52_HIGH} is a fresh high; on a 1d primary period=252 ≈ a 52-week high. Null until {@code
   * period} prior bars exist.
   */
  static EngineIndicator week52High(EngineSeries series, int period) {
    return indicator(
        period,
        series,
        index -> {
          BigDecimal max = null;
          for (int i = index - period; i <= index - 1; i++) {
            BigDecimal h = series.candle(i).high();
            if (max == null || h.compareTo(max) > 0) {
              max = h;
            }
          }
          return max;
        });
  }

  /** Lowest low over the {@code period} bars ending at the prior bar (excludes the current bar). */
  static EngineIndicator week52Low(EngineSeries series, int period) {
    return indicator(
        period,
        series,
        index -> {
          BigDecimal min = null;
          for (int i = index - period; i <= index - 1; i++) {
            BigDecimal l = series.candle(i).low();
            if (min == null || l.compareTo(min) < 0) {
              min = l;
            }
          }
          return min;
        });
  }

  /**
   * Futures basis as a percent of futures: (spot - futures) / futures * 100, evaluated on the
   * spot/index signal series against the front-month futures context series (time-aligned
   * at-or-before). Sign convention per the plan §7.7 reference formula (positive = spot above
   * futures / backwardation); the exact Siva sign + threshold gates are encoded in the S12
   * strategy YAML, not here (VERIFY against C:\Trading\ArthaYantra\StockMarketStrategyTraining
   * during S12 — a flip is a one-line change + vector regen).
   */
  static EngineIndicator basisPct(EngineSeries spot, EngineSeries futures) {
    return indicator(
        0,
        spot,
        index -> {
          int fi = futures.indexAtOrBefore(spot.candle(index).bucketStart().toInstant());
          if (fi < 0) {
            return null;
          }
          BigDecimal f = futures.candle(fi).close();
          BigDecimal s = spot.candle(index).close();
          if (f.signum() == 0) {
            return null;
          }
          return s.subtract(f).divide(f, EngineMath.MC).multiply(EngineMath.HUNDRED, EngineMath.MC);
        });
  }

  private static BigDecimal lookbackReturnPct(EngineSeries series, int index, int lookback) {
    if (index < lookback) {
      return null;
    }
    BigDecimal base = series.candle(index - lookback).close();
    if (base.signum() == 0) {
      return null;
    }
    return series.candle(index).close()
        .subtract(base)
        .divide(base, EngineMath.MC)
        .multiply(EngineMath.HUNDRED, EngineMath.MC);
  }

  private interface RawValue {
    BigDecimal at(int index);
  }

  private static EngineIndicator indicator(int unstable, EngineSeries series, RawValue raw) {
    return new EngineIndicator() {
      @Override
      public BigDecimal valueAt(int index) {
        if (index < unstable || index >= series.size()) {
          return null;
        }
        BigDecimal value = raw.at(index);
        return value == null ? null : EngineMath.round(value);
      }

      @Override
      public int unstableBars() {
        return unstable;
      }
    };
  }
}
