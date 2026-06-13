package in.arthayantra.backtest.montecarlo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * The §D.16 Monte Carlo run analytic: a <b>seeded bootstrap</b> of a run's persisted closed-trade
 * P&amp;Ls (trade-sequence resampling <b>with replacement</b>). It never reruns a replay — it
 * resamples already-persisted numbers only, so determinism is pinned by the {@code (pnls, mcSeed,
 * n)} triple exactly like the §D.6 replay triple: same inputs ⇒ byte-identical summary. All RNG is
 * a single {@link Random} seeded with {@code mcSeed}, drawn in a fixed order; outputs are rounded to
 * fixed scales so the JSON serializes to stable decimal strings.
 *
 * <p>Outputs: a downsampled 5/50/95 equity band (percentiles across resamples at each trade step),
 * the max-drawdown distribution (percentiles + mean of per-resample max drawdown), risk-of-ruin
 * (fraction of resamples whose equity path ever breaches a floor at {@link #RUIN_FLOOR_FRACTION} of
 * the starting equity), and percentile confidence intervals on CAGR and per-trade-annualized Sharpe.
 */
@Component
public final class MonteCarlo {

  /** Risk-of-ruin floor as a fraction of starting equity (capital halved = ruin). Documented
   * default; there is no per-request floor in the §D.5 endpoint contract. */
  public static final double RUIN_FLOOR_FRACTION = 0.5;

  private static final int BAND_POINTS = 200; // cap the persisted band length
  private static final int MONEY_SCALE = 2;
  private static final int RATIO_SCALE = 6;

  private final ObjectMapper objectMapper;

  /** Wires Jackson for the summary JSONB. */
  public MonteCarlo(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Runs the bootstrap.
   *
   * @param pnls per-trade net P&amp;L (₹) in trade order; must be non-empty
   * @param initialEquity the run's starting equity
   * @param years the run's calendar span in years (for the CAGR statistic); {@code ≤ 0} disables it
   * @param n number of resamples
   * @param mcSeed the recorded RNG seed
   * @param minTrades the {@code constraints.min_trades} threshold for the "insufficient sample" flag
   * @return the persisted-shape summary object
   */
  public ObjectNode run(
      List<BigDecimal> pnls,
      BigDecimal initialEquity,
      double years,
      int n,
      long mcSeed,
      int minTrades) {
    if (pnls.isEmpty()) {
      throw new IllegalArgumentException("Monte Carlo needs at least one closed trade");
    }
    int m = pnls.size();
    double initial = initialEquity.doubleValue();
    double[] pnl = new double[m];
    for (int i = 0; i < m; i++) {
      pnl[i] = pnls.get(i).doubleValue();
    }

    Random rng = new Random(mcSeed);
    double[][] paths = new double[n][m + 1]; // equity after 0..m trades
    double[] maxDd = new double[n];
    double[] finalCagr = new double[n];
    double[] sharpe = new double[n];
    int ruin = 0;
    double floor = initial * RUIN_FLOOR_FRACTION;
    double tradesPerYear = years > 0 ? m / years : 0;

    for (int r = 0; r < n; r++) {
      double equity = initial;
      double peak = initial;
      double maxDrawdown = 0;
      boolean hitFloor = equity <= floor;
      double[] tradeReturns = new double[m];
      paths[r][0] = initial;
      for (int k = 0; k < m; k++) {
        double draw = pnl[rng.nextInt(m)];
        double before = equity;
        equity += draw;
        tradeReturns[k] = before == 0 ? 0 : draw / before;
        paths[r][k + 1] = equity;
        if (equity > peak) {
          peak = equity;
        }
        if (peak > 0) {
          double dd = (peak - equity) / peak;
          if (dd > maxDrawdown) {
            maxDrawdown = dd;
          }
        }
        if (equity <= floor) {
          hitFloor = true;
        }
      }
      maxDd[r] = maxDrawdown;
      if (hitFloor) {
        ruin++;
      }
      finalCagr[r] =
          years > 0 && initial > 0 && equity > 0 ? Math.pow(equity / initial, 1.0 / years) - 1.0 : 0;
      sharpe[r] = tradesPerYear > 0 ? annualizedSharpe(tradeReturns, tradesPerYear) : 0;
    }

    ObjectNode out = objectMapper.createObjectNode();
    out.put("mcSeed", mcSeed);
    out.put("n", n);
    out.put("trades", m);
    out.put("insufficientSample", m < minTrades);
    out.set("equityBands", equityBands(paths, m));
    out.set("drawdownDistribution", percentileBlock(maxDd, RATIO_SCALE, true));
    out.put("riskOfRuin", scaled((double) ruin / n, RATIO_SCALE));
    out.set("ci", ci(finalCagr, sharpe));
    return out;
  }

  /** Equity 5/50/95 percentile bands across resamples at each (downsampled) trade step. */
  private ObjectNode equityBands(double[][] paths, int m) {
    int steps = m + 1;
    int stride = steps <= BAND_POINTS ? 1 : (int) Math.ceil((double) steps / BAND_POINTS);
    int n = paths.length;
    ArrayNode p5 = objectMapper.createArrayNode();
    ArrayNode p50 = objectMapper.createArrayNode();
    ArrayNode p95 = objectMapper.createArrayNode();
    ArrayNode stepIdx = objectMapper.createArrayNode();
    double[] column = new double[n];
    for (int k = 0; k < steps; k += stride) {
      for (int r = 0; r < n; r++) {
        column[r] = paths[r][k];
      }
      double[] sorted = column.clone();
      Arrays.sort(sorted);
      stepIdx.add(k);
      p5.add(scaled(percentile(sorted, 5), MONEY_SCALE));
      p50.add(scaled(percentile(sorted, 50), MONEY_SCALE));
      p95.add(scaled(percentile(sorted, 95), MONEY_SCALE));
    }
    // ensure the last step is always present (exact endpoint)
    if ((steps - 1) % stride != 0) {
      int k = steps - 1;
      for (int r = 0; r < n; r++) {
        column[r] = paths[r][k];
      }
      double[] sorted = column.clone();
      Arrays.sort(sorted);
      stepIdx.add(k);
      p5.add(scaled(percentile(sorted, 5), MONEY_SCALE));
      p50.add(scaled(percentile(sorted, 50), MONEY_SCALE));
      p95.add(scaled(percentile(sorted, 95), MONEY_SCALE));
    }
    ObjectNode bands = objectMapper.createObjectNode();
    bands.set("step", stepIdx);
    bands.set("p5", p5);
    bands.set("p50", p50);
    bands.set("p95", p95);
    return bands;
  }

  /** A p5/p50/p95 (+ mean) percentile block over a sample. */
  private ObjectNode percentileBlock(double[] sample, int scale, boolean includeMean) {
    double[] sorted = sample.clone();
    Arrays.sort(sorted);
    ObjectNode node = objectMapper.createObjectNode();
    node.put("p5", scaled(percentile(sorted, 5), scale));
    node.put("p50", scaled(percentile(sorted, 50), scale));
    node.put("p95", scaled(percentile(sorted, 95), scale));
    if (includeMean) {
      double sum = 0;
      for (double v : sorted) {
        sum += v;
      }
      node.put("mean", scaled(sum / sorted.length, scale));
    }
    return node;
  }

  private ObjectNode ci(double[] cagr, double[] sharpe) {
    ObjectNode node = objectMapper.createObjectNode();
    node.set("cagr", percentileBlock(cagr, RATIO_SCALE, false));
    node.set("sharpe", percentileBlock(sharpe, RATIO_SCALE, false));
    return node;
  }

  private static double annualizedSharpe(double[] returns, double tradesPerYear) {
    double mean = 0;
    for (double r : returns) {
      mean += r;
    }
    mean /= returns.length;
    double sq = 0;
    for (double r : returns) {
      sq += (r - mean) * (r - mean);
    }
    double std = Math.sqrt(sq / returns.length);
    if (std == 0) {
      return 0;
    }
    return mean / std * Math.sqrt(tradesPerYear);
  }

  /** Linear-interpolated percentile over a pre-sorted ascending array. */
  private static double percentile(double[] sorted, double pct) {
    if (sorted.length == 1) {
      return sorted[0];
    }
    double rank = pct / 100.0 * (sorted.length - 1);
    int lo = (int) Math.floor(rank);
    int hi = (int) Math.ceil(rank);
    if (lo == hi) {
      return sorted[lo];
    }
    double frac = rank - lo;
    return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
  }

  /** Fixed-scale plain-string form, so the JSONB serializes byte-identically (the §D.9 string
   * convention) regardless of the global BigDecimal serializer. */
  private static String scaled(double v, int scale) {
    if (Double.isNaN(v) || Double.isInfinite(v)) {
      return BigDecimal.ZERO.setScale(scale).toPlainString();
    }
    return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).toPlainString();
  }
}
