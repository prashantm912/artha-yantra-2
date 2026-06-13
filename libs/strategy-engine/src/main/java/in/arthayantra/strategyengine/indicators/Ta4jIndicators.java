package in.arthayantra.strategyengine.indicators;

import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.num.Num;

/** ta4j 0.22 wrappers — Num (DecimalNum-32) in, boundary-rounded BigDecimal out. */
final class Ta4jIndicators {

  private Ta4jIndicators() {}

  static EngineIndicator ema(EngineSeries series, int period) {
    // ta4j 0.22: EMA is NaN-gated below barCount and seeds at index == barCount
    return wrap(new EMAIndicator(new ClosePriceIndicator(series.barSeries()), period), period);
  }

  static EngineIndicator sma(EngineSeries series, int period) {
    return wrap(new SMAIndicator(new ClosePriceIndicator(series.barSeries()), period), period - 1);
  }

  static EngineIndicator rsi(EngineSeries series, int period) {
    return wrap(new RSIIndicator(new ClosePriceIndicator(series.barSeries()), period), period);
  }

  static EngineIndicator atr(EngineSeries series, int period) {
    return wrap(new ATRIndicator(series.barSeries(), period), period);
  }

  static EngineIndicator adx(EngineSeries series, int period) {
    return wrap(new ADXIndicator(series.barSeries(), period), 2 * period);
  }

  static EngineIndicator macdHistogram(EngineSeries series, int fast, int slow, int signal) {
    MACDIndicator macd =
        new MACDIndicator(new ClosePriceIndicator(series.barSeries()), fast, slow);
    return wrap(macd.getHistogram(signal), slow + signal - 2);
  }

  /** Supertrend DIRECTION: +1 uptrend / -1 downtrend (normalize{direction} maps to 1/0). */
  static EngineIndicator supertrendDirection(
      EngineSeries series, int period, BigDecimal multiplier) {
    SuperTrendIndicator supertrend =
        new SuperTrendIndicator(series.barSeries(), period, multiplier.doubleValue());
    int unstable = Math.max(period, supertrend.getCountOfUnstableBars());
    return new EngineIndicator() {
      @Override
      public BigDecimal valueAt(int index) {
        if (index < unstable || index >= series.size()) {
          return null;
        }
        return EngineMath.round(
            supertrend.isUpTrend(index) ? BigDecimal.ONE : BigDecimal.ONE.negate());
      }

      @Override
      public int unstableBars() {
        return unstable;
      }
    };
  }

  private static EngineIndicator wrap(Indicator<Num> indicator, int unstable) {
    int effectiveUnstable = Math.max(unstable, 0);
    return new EngineIndicator() {
      @Override
      public BigDecimal valueAt(int index) {
        if (index < effectiveUnstable || index >= indicator.getBarSeries().getBarCount()) {
          return null;
        }
        Num value = indicator.getValue(index);
        if (value == null || value.isNaN()) {
          return null;
        }
        return EngineMath.round(new BigDecimal(value.getDelegate().toString()));
      }

      @Override
      public int unstableBars() {
        return effectiveUnstable;
      }
    };
  }
}
