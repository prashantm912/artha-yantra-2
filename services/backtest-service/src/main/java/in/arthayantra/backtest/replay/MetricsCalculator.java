package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The §D.9 metric catalog, computed once per run. Money math is NUMERIC/BigDecimal; ratio metrics
 * (Sharpe, Sortino) use deterministic double statistics rounded to a fixed scale so the golden
 * decimal strings are stable. Sharpe uses the 6.5% Indian T-bill default risk-free rate and the
 * √(periods/yr) scaling (252 daily, 252×bars/day for intraday).
 */
@Component
public final class MetricsCalculator {

  private static final MathContext MC = new MathContext(32, RoundingMode.HALF_UP);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final double RISK_FREE = 0.065;
  private static final int SCALE = 6;
  private final ObjectMapper objectMapper;

  /** Wires Jackson for the metrics JSONB. */
  public MetricsCalculator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** The headline columns + the full catalog JSONB. */
  public record Metrics(
      BigDecimal totalReturn,
      BigDecimal sharpe,
      BigDecimal sortino,
      BigDecimal maxDrawdown,
      BigDecimal winRate,
      BigDecimal profitFactor,
      int tradeCount,
      ObjectNode full) {}

  /** Computes the catalog from closed trades + the mark-to-market equity curve. */
  public Metrics compute(
      List<Trade> trades,
      List<EquityPoint> equity,
      BigDecimal initialEquity,
      BigDecimal finalEquity,
      String interval,
      long totalBars,
      long barsInPosition) {

    BigDecimal totalReturn = pct(finalEquity.subtract(initialEquity), initialEquity);
    double[] periodic = periodicReturns(equity);
    double periodsPerYear = periodsPerYear(interval);

    double sharpe = sharpe(periodic, periodsPerYear);
    double sortino = sortino(periodic, periodsPerYear);
    BigDecimal maxDd = maxDrawdownPct(equity);
    int maxDdDuration = maxDrawdownDuration(equity);

    List<Trade> closed = trades.stream().filter(t -> t.exitTs() != null).toList();
    int tradeCount = closed.size();
    long winners = closed.stream().filter(t -> t.pnl().signum() > 0).count();
    BigDecimal winRate = tradeCount == 0 ? BigDecimal.ZERO : pct(BigDecimal.valueOf(winners), BigDecimal.valueOf(tradeCount));
    BigDecimal grossProfit = sumPnl(closed, true);
    BigDecimal grossLoss = sumPnl(closed, false).abs();
    BigDecimal profitFactor =
        grossLoss.signum() == 0 ? BigDecimal.ZERO : grossProfit.divide(grossLoss, SCALE, RoundingMode.HALF_UP);
    BigDecimal expectancy =
        tradeCount == 0 ? BigDecimal.ZERO : totalPnl(closed).divide(BigDecimal.valueOf(tradeCount), 2, RoundingMode.HALF_UP);
    BigDecimal cagr = cagr(initialEquity, finalEquity, equity);
    BigDecimal exposure = totalBars == 0 ? BigDecimal.ZERO : pct(BigDecimal.valueOf(barsInPosition), BigDecimal.valueOf(totalBars));

    // §6.2 evolution-engine metric adds (E1). All three are derived deterministically from the same
    // replay inputs already in hand (no wall-clock, no random) and are NOT golden-serialized
    // (GoldenSignalsJson.write emits signal vectors only), so replay parity is unaffected.
    // recoveryFactor = totalReturn / maxDD; both are percentages, so the ratio is dimensionless.
    // It is UNDEFINED when there is no drawdown (maxDD == 0) — persisted as JSON null (never a
    // sentinel number), and the key is still always present so the metrics catalog stays consistent.
    BigDecimal recoveryFactor =
        maxDd.signum() == 0 ? null : totalReturn.divide(maxDd, SCALE, RoundingMode.HALF_UP);
    BigDecimal tradeFrequency = tradeFrequency(tradeCount, totalBars, interval);
    BigDecimal turnover = turnover(trades, initialEquity);

    ObjectNode full = objectMapper.createObjectNode();
    full.put("totalReturn", totalReturn.toPlainString());
    full.put("cagr", cagr.toPlainString());
    full.put("sharpe", scaled(sharpe));
    full.put("sortino", scaled(sortino));
    full.put("maxDrawdown", maxDd.toPlainString());
    full.put("maxDrawdownDurationBars", maxDdDuration);
    full.put("winRate", winRate.toPlainString());
    full.put("profitFactor", profitFactor.toPlainString());
    full.put("expectancy", expectancy.toPlainString());
    full.put("averageTrade", expectancy.toPlainString());
    full.put("exposure", exposure.toPlainString());
    full.put("tradeCount", tradeCount);
    if (recoveryFactor == null) {
      full.putNull("recoveryFactor"); // undefined when there is no drawdown (maxDD == 0)
    } else {
      full.put("recoveryFactor", recoveryFactor.toPlainString());
    }
    full.put("tradeFrequency", tradeFrequency.toPlainString());
    full.put("turnover", turnover.toPlainString());

    return new Metrics(
        totalReturn,
        bd(sharpe),
        bd(sortino),
        maxDd,
        winRate,
        profitFactor,
        tradeCount,
        full);
  }

  private static double[] periodicReturns(List<EquityPoint> equity) {
    if (equity.size() < 2) {
      return new double[0];
    }
    double[] out = new double[equity.size() - 1];
    for (int i = 1; i < equity.size(); i++) {
      double prev = equity.get(i - 1).equity().doubleValue();
      double cur = equity.get(i).equity().doubleValue();
      out[i - 1] = prev == 0 ? 0 : (cur - prev) / prev;
    }
    return out;
  }

  private static double sharpe(double[] returns, double periodsPerYear) {
    if (returns.length < 2) {
      return 0;
    }
    double rfPeriodic = RISK_FREE / periodsPerYear;
    double mean = mean(returns);
    double std = std(returns, mean);
    if (std == 0) {
      return 0;
    }
    return (mean - rfPeriodic) / std * Math.sqrt(periodsPerYear);
  }

  private static double sortino(double[] returns, double periodsPerYear) {
    if (returns.length < 2) {
      return 0;
    }
    double rfPeriodic = RISK_FREE / periodsPerYear;
    double mean = mean(returns);
    double downsideSq = 0;
    int n = 0;
    for (double r : returns) {
      if (r < 0) {
        downsideSq += r * r;
        n++;
      }
    }
    if (n == 0) {
      return 0;
    }
    double downsideDev = Math.sqrt(downsideSq / n);
    if (downsideDev == 0) {
      return 0;
    }
    return (mean - rfPeriodic) / downsideDev * Math.sqrt(periodsPerYear);
  }

  private static BigDecimal maxDrawdownPct(List<EquityPoint> equity) {
    BigDecimal peak = null;
    BigDecimal maxDd = BigDecimal.ZERO;
    for (EquityPoint p : equity) {
      if (peak == null || p.equity().compareTo(peak) > 0) {
        peak = p.equity();
      }
      if (peak.signum() > 0) {
        BigDecimal dd = pct(peak.subtract(p.equity()), peak);
        if (dd.compareTo(maxDd) > 0) {
          maxDd = dd;
        }
      }
    }
    return maxDd;
  }

  private static int maxDrawdownDuration(List<EquityPoint> equity) {
    BigDecimal peak = null;
    int peakIndex = 0;
    int maxDuration = 0;
    for (int i = 0; i < equity.size(); i++) {
      BigDecimal eq = equity.get(i).equity();
      if (peak == null || eq.compareTo(peak) >= 0) {
        peak = eq;
        peakIndex = i;
      } else {
        maxDuration = Math.max(maxDuration, i - peakIndex);
      }
    }
    return maxDuration;
  }

  private BigDecimal cagr(BigDecimal initial, BigDecimal finalEquity, List<EquityPoint> equity) {
    if (equity.size() < 2 || initial.signum() <= 0 || finalEquity.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    Duration span =
        Duration.between(equity.get(0).ts(), equity.get(equity.size() - 1).ts());
    double years = span.toSeconds() / (365.25 * 24 * 3600);
    if (years <= 0) {
      return BigDecimal.ZERO;
    }
    double ratio = finalEquity.doubleValue() / initial.doubleValue();
    double cagr = Math.pow(ratio, 1.0 / years) - 1.0;
    return bd(cagr * 100);
  }

  private static BigDecimal sumPnl(List<Trade> trades, boolean positive) {
    return trades.stream()
        .map(Trade::pnl)
        .filter(p -> positive ? p.signum() > 0 : p.signum() < 0)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal totalPnl(List<Trade> trades) {
    return trades.stream().map(Trade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
    if (denominator.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return numerator.multiply(HUNDRED, MC).divide(denominator, SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Trade frequency in trades per trading SESSION: {@code tradeCount / sessions}, where {@code
   * sessions = totalBars / barsPerSession} and {@code barsPerSession = periodsPerYear(interval) /
   * 252} (the same per-interval constant Sharpe scaling uses — bars exist only within sessions, so
   * the primary-bar count divided by bars-per-session is the session count). A {@code 1d} run reads
   * trades/day (barsPerSession == 1); a {@code 1m} run reads trades/session (375 1m bars == one
   * session). Deterministic (pure function of tradeCount, totalBars, interval); {@code 0} on an
   * empty window (no bars).
   */
  private static BigDecimal tradeFrequency(int tradeCount, long totalBars, String interval) {
    if (totalBars == 0) {
      return BigDecimal.ZERO.setScale(SCALE);
    }
    double sessions = totalBars / (periodsPerYear(interval) / 252.0);
    if (sessions <= 0) {
      return BigDecimal.ZERO.setScale(SCALE);
    }
    return bd(tradeCount / sessions);
  }

  /**
   * Turnover as a multiple of the starting account: {@code Σ|fill value| / initialEquity}, fill
   * value = {@code price × qty} summed across every entry fill and every exit fill (an open-at-end
   * trade, exitPrice == null, contributes its entry leg only). A plain ratio, NOT a percentage.
   * Deterministic (pure function of the trade fills + initial equity); {@code 0} when equity is 0.
   */
  private static BigDecimal turnover(List<Trade> trades, BigDecimal initialEquity) {
    if (initialEquity.signum() == 0) {
      return BigDecimal.ZERO.setScale(SCALE);
    }
    BigDecimal fills = BigDecimal.ZERO;
    for (Trade t : trades) {
      BigDecimal qty = BigDecimal.valueOf(t.qty());
      fills = fills.add(t.entryPrice().multiply(qty).abs());
      if (t.exitPrice() != null) {
        fills = fills.add(t.exitPrice().multiply(qty).abs());
      }
    }
    return fills.divide(initialEquity, SCALE, RoundingMode.HALF_UP);
  }

  private static double periodsPerYear(String interval) {
    return switch (interval) {
      case "1m" -> 252.0 * 375;
      case "5m" -> 252.0 * 75;
      case "15m" -> 252.0 * 25;
      case "1h" -> 252.0 * 375 / 60;
      case "1d" -> 252.0;
      case "1w" -> 52.0;
      default -> 252.0;
    };
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
      return BigDecimal.ZERO.setScale(SCALE);
    }
    return BigDecimal.valueOf(v).setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static String scaled(double v) {
    return bd(v).toPlainString();
  }
}
