package in.arthayantra.backtest.regime;

import in.arthayantra.backtest.regime.RegimeAttribution.RegimeStat;
import in.arthayantra.backtest.regime.RegimeAttribution.RegimeTradeTag;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attributes one fold's OOS window + trades to regimes (guard 6, §D.4): the regime mix over the OOS
 * test trading days, the per-regime OOS Sharpe + expectancy over entry-tagged trades, and each
 * closed trade's entry-day tag. Pure + deterministic — a trading-day walk via {@link MarketCalendar}
 * and BigDecimal expectancy / double-statistic Sharpe rounded to a fixed scale.
 */
public final class FoldRegimeAttributor {

  private static final MathContext MC = new MathContext(32, RoundingMode.HALF_UP);
  private static final int SCALE = 6;
  private static final double RISK_FREE = 0.065;
  private static final double TRADING_DAYS_PER_YEAR = 252.0;
  private static final MarketCalendar CALENDAR = MarketCalendar.nse();

  private FoldRegimeAttributor() {}

  /**
   * Computes the attribution for one fold.
   *
   * @param oosTrades the fold's OOS replay trades (closed + open-at-end) in seq order
   * @param testFrom the OOS window start (inclusive)
   * @param testTo the OOS window end (exclusive)
   * @param initialEquity the fold's starting equity (per-trade return denominator)
   * @param labels the benchmark entry-day → label map (T−1 computed)
   * @return the attribution; mix covers every OOS trading day, tags cover closed trades only
   */
  public static RegimeAttribution attribute(
      List<Trade> oosTrades,
      OffsetDateTime testFrom,
      OffsetDateTime testTo,
      BigDecimal initialEquity,
      Map<LocalDate, RegimeLabel> labels) {

    // 1) regime mix over EVERY OOS trading day (not just days that traded) — the robustness denom.
    List<LocalDate> oosDays = tradingDays(testFrom.toLocalDate(), testTo.toLocalDate());
    Map<RegimeLabel, Integer> mix = RegimeLabeler.mix(oosDays, labels);

    // 2) entry-day tag per CLOSED trade + group P&L by entry-day regime.
    List<RegimeTradeTag> tags = new ArrayList<>();
    Map<RegimeLabel, List<BigDecimal>> pnlByRegime = new EnumMap<>(RegimeLabel.class);
    for (Trade t : oosTrades) {
      if (t.exitTs() == null) {
        continue; // only closed trades carry a realized P&L / expectancy
      }
      RegimeLabel label = labels.get(t.entryTs().toLocalDate());
      tags.add(new RegimeTradeTag(t.seq(), label));
      if (label != null) {
        pnlByRegime.computeIfAbsent(label, k -> new ArrayList<>()).add(t.pnl());
      }
    }

    // 3) per-regime OOS aggregates, ordered by RegimeLabel.
    Map<RegimeLabel, RegimeStat> perRegime = new LinkedHashMap<>();
    for (RegimeLabel label : RegimeLabel.values()) {
      List<BigDecimal> pnls = pnlByRegime.get(label);
      if (pnls != null && !pnls.isEmpty()) {
        perRegime.put(label, stat(pnls, initialEquity));
      }
    }
    return new RegimeAttribution(mix, perRegime, tags);
  }

  /** Per-regime Sharpe (per-trade returns vs initial equity, √252-annualized) + ₹ expectancy. */
  private static RegimeStat stat(List<BigDecimal> pnls, BigDecimal initialEquity) {
    int n = pnls.size();
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal p : pnls) {
      sum = sum.add(p);
    }
    BigDecimal expectancy = sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

    BigDecimal sharpe = null;
    if (n >= 2 && initialEquity.signum() > 0) {
      double[] rets = new double[n];
      double inv = 1.0 / initialEquity.doubleValue();
      for (int i = 0; i < n; i++) {
        rets[i] = pnls.get(i).doubleValue() * inv;
      }
      double mean = mean(rets);
      double std = std(rets, mean);
      if (std > 0) {
        double rfPer = RISK_FREE / TRADING_DAYS_PER_YEAR;
        double s = (mean - rfPer) / std * Math.sqrt(TRADING_DAYS_PER_YEAR);
        sharpe = bd(s);
      } else {
        sharpe = bd(0);
      }
    }
    return new RegimeStat(sharpe, expectancy, n);
  }

  private static List<LocalDate> tradingDays(LocalDate from, LocalDate toExclusive) {
    List<LocalDate> out = new ArrayList<>();
    LocalDate d = from;
    while (d.isBefore(toExclusive)) {
      if (CALENDAR.isTradingDay(d)) {
        out.add(d);
      }
      d = d.plusDays(1);
    }
    return out;
  }

  private static double mean(double[] xs) {
    double sum = 0;
    for (double x : xs) {
      sum += x;
    }
    return sum / xs.length;
  }

  private static double std(double[] xs, double mean) {
    double sq = 0;
    for (double x : xs) {
      sq += (x - mean) * (x - mean);
    }
    return Math.sqrt(sq / (xs.length - 1));
  }

  private static BigDecimal bd(double v) {
    if (Double.isNaN(v) || Double.isInfinite(v)) {
      return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }
    return new BigDecimal(v, MC).setScale(SCALE, RoundingMode.HALF_UP);
  }
}
