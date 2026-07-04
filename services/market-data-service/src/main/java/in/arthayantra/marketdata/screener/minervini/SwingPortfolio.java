package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A minimal, deterministic PORTFOLIO simulator over a variant's trade list — the bridge from
 * per-trade stats (win-rate / payoff / expectancy) to the returns an owner actually feels: annual and
 * monthly returns, CAGR, max drawdown, Sharpe.
 *
 * <p>Model: {@code slots} equal-weight compounding sleeves (Minervini runs concentrated, ~4-8 names),
 * each starting at {@code 1/slots} of a normalised 1.0 equity. Trades are processed chronologically;
 * an ENTRY takes the next free sleeve (or is SKIPPED when all slots are full — a real capacity limit,
 * counted), and the sleeve compounds by the trade's return on EXIT. On a shared date, EXITs are
 * processed before ENTRYs so freed capital is reusable same-day. The equity curve steps at each exit
 * (realised P&amp;L only — no intraday mark-to-market, standard for a trade-list backtest), and monthly
 * / annual returns + drawdown are sampled off that curve. Pure: no IO, no clock.
 */
final class SwingPortfolio {

  /** One calendar year's realised return + the trades that closed in it. */
  record Year(int year, double returnPct, int trades) {}

  /** The portfolio-level result for one variant. */
  record Result(
      double totalReturnPct,
      double cagrPct,
      double maxDrawdownPct,
      double sharpe,
      int tradesTaken,
      int tradesSkipped,
      double avgExposurePct,
      int months,
      double positiveMonthsPct,
      double bestMonthPct,
      double worstMonthPct,
      double avgMonthPct,
      List<Year> annual) {}

  private SwingPortfolio() {}

  private static final Result EMPTY =
      new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());

  /**
   * Simulates the {@code slots}-sleeve portfolio over {@code trades} (any order). When {@code
   * rsPriority}, a day's entries compete for scarce slots by RS-rank (the strongest names get the
   * slots — how you would actually choose); otherwise slots fill first-come (FIFO). Returns the stats.
   */
  static Result simulate(List<BtTrade> trades, int slots, boolean rsPriority) {
    if (trades.isEmpty() || slots < 1) {
      return EMPTY;
    }
    int n = trades.size();
    LocalDate[] entry = new LocalDate[n];
    LocalDate[] exit = new LocalDate[n];
    double[] pnlFrac = new double[n];
    for (int i = 0; i < n; i++) {
      BtTrade t = trades.get(i);
      entry[i] = t.entryDate();
      exit[i] = t.exitDate();
      pnlFrac[i] = t.pnlPct() / 100.0;
    }

    // event stream: (date, type, tradeIdx); type 0 = EXIT, 1 = ENTRY (EXIT first on a shared date).
    long[] events = new long[2 * n];
    int e = 0;
    for (int i = 0; i < n; i++) {
      events[e++] = pack(exit[i], 0, i);
      events[e++] = pack(entry[i], 1, i);
    }
    Arrays.sort(events);

    // RS-priority: reorder each maximal run of same-day ENTRY events (contiguous after the sort, since
    // EXITs sort ahead of ENTRYs on a date) so the strongest names claim the scarce slots first.
    if (rsPriority) {
      int i = 0;
      while (i < events.length) {
        if (unType(events[i]) != 1) {
          i++;
          continue;
        }
        long day = events[i] >>> 24;
        int j = i;
        while (j < events.length && unType(events[j]) == 1 && (events[j] >>> 24) == day) {
          j++;
        }
        Long[] run = new Long[j - i];
        for (int k = 0; k < run.length; k++) {
          run[k] = events[i + k];
        }
        Arrays.sort(run, (a, b) -> Double.compare(rsOf(trades, b), rsOf(trades, a))); // desc
        for (int k = 0; k < run.length; k++) {
          events[i + k] = run[k];
        }
        i = j;
      }
    }

    double[] sleeve = new double[slots];
    Arrays.fill(sleeve, 1.0 / slots);
    int[] freeStack = new int[slots];
    for (int k = 0; k < slots; k++) {
      freeStack[k] = k;
    }
    int freeTop = slots; // freeStack[0..freeTop) are free
    int[] sleeveOf = new int[n];
    Arrays.fill(sleeveOf, -2); // -2 unseen, -1 closed, -3 skipped, ≥0 open in that sleeve
    int taken = 0;
    int skipped = 0;
    int busy = 0;

    List<LocalDate> curveDate = new ArrayList<>();
    List<Double> curveEq = new ArrayList<>();
    LocalDate first = LocalDate.ofEpochDay(unDate(events[0]));
    curveDate.add(first);
    curveEq.add(1.0);

    double expoAccum = 0; // Σ busy·days
    long expoDays = 0;
    LocalDate prev = null;
    for (long ev : events) {
      LocalDate date = LocalDate.ofEpochDay(unDate(ev));
      int type = unType(ev);
      int idx = unIdx(ev);
      if (prev != null) {
        long d = ChronoUnit.DAYS.between(prev, date);
        expoAccum += (double) busy * d;
        expoDays += d;
      }
      prev = date;
      if (type == 0) { // EXIT
        if (sleeveOf[idx] >= 0) {
          int k = sleeveOf[idx];
          sleeve[k] *= 1.0 + pnlFrac[idx];
          freeStack[freeTop++] = k;
          sleeveOf[idx] = -1;
          busy--;
          curveDate.add(date);
          curveEq.add(sum(sleeve));
        }
      } else { // ENTRY
        if (freeTop == 0) {
          skipped++;
          sleeveOf[idx] = -3; // skipped — distinct from -1 (closed) so the annual count excludes it
        } else {
          int k = freeStack[--freeTop];
          sleeveOf[idx] = k;
          taken++;
          busy++;
        }
      }
    }

    double finalEq = sum(sleeve);
    LocalDate last = curveDate.get(curveDate.size() - 1);
    double years = Math.max(1e-9, ChronoUnit.DAYS.between(first, last) / 365.25);
    double totalRet = (finalEq - 1.0) * 100.0;
    double cagr = (Math.pow(finalEq, 1.0 / years) - 1.0) * 100.0;
    double maxDd = maxDrawdown(curveEq) * 100.0;
    double avgExpo = expoDays > 0 ? 100.0 * expoAccum / expoDays / slots : 0.0;

    // monthly returns off the realised-equity step curve
    List<Double> monthly = monthlyReturns(curveDate, curveEq, first, last);
    int months = monthly.size();
    double best = 0;
    double worst = 0;
    double sum = 0;
    int positive = 0;
    if (!monthly.isEmpty()) {
      best = Double.NEGATIVE_INFINITY;
      worst = Double.POSITIVE_INFINITY;
      for (double r : monthly) {
        sum += r;
        best = Math.max(best, r);
        worst = Math.min(worst, r);
        if (r > 0) {
          positive++;
        }
      }
    }
    double avgMonth = months > 0 ? sum / months : 0;
    double sharpe = sharpe(monthly, avgMonth);
    double posPct = months > 0 ? 100.0 * positive / months : 0;

    List<Year> annual = annualReturns(curveDate, curveEq, exit, sleeveOf, first, last);

    return new Result(
        totalRet, cagr, maxDd, sharpe, taken, skipped, avgExpo, months, posPct,
        best == Double.NEGATIVE_INFINITY ? 0 : best * 100.0,
        worst == Double.POSITIVE_INFINITY ? 0 : worst * 100.0,
        avgMonth * 100.0, annual);
  }

  private static List<Double> monthlyReturns(
      List<LocalDate> curveDate, List<Double> curveEq, LocalDate first, LocalDate last) {
    List<Double> out = new ArrayList<>();
    YearMonth ym = YearMonth.from(first);
    YearMonth end = YearMonth.from(last);
    double prevEq = 1.0;
    while (!ym.isAfter(end)) {
      double eq = equityAsOf(curveDate, curveEq, ym.atEndOfMonth());
      out.add(eq / prevEq - 1.0);
      prevEq = eq;
      ym = ym.plusMonths(1);
    }
    return out;
  }

  private static List<Year> annualReturns(
      List<LocalDate> curveDate, List<Double> curveEq, LocalDate[] exit, int[] sleeveOf,
      LocalDate first, LocalDate last) {
    List<Year> out = new ArrayList<>();
    double prevEq = 1.0;
    for (int y = first.getYear(); y <= last.getYear(); y++) {
      double eq = equityAsOf(curveDate, curveEq, LocalDate.of(y, 12, 31));
      int trades = 0;
      for (int i = 0; i < exit.length; i++) {
        // count only TAKEN trades that closed in year y (sleeveOf==-1); skipped trades are -3.
        if (exit[i].getYear() == y && sleeveOf[i] == -1) {
          trades++;
        }
      }
      out.add(new Year(y, (eq / prevEq - 1.0) * 100.0, trades));
      prevEq = eq;
    }
    return out;
  }

  /** The realised equity as of {@code date} (last curve point with date ≤ {@code date}); 1.0 before the start. */
  private static double equityAsOf(List<LocalDate> curveDate, List<Double> curveEq, LocalDate date) {
    int lo = 0;
    int hi = curveDate.size() - 1;
    int best = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (!curveDate.get(mid).isAfter(date)) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best < 0 ? 1.0 : curveEq.get(best);
  }

  private static double maxDrawdown(List<Double> eq) {
    double peak = Double.NEGATIVE_INFINITY;
    double maxDd = 0;
    for (double v : eq) {
      peak = Math.max(peak, v);
      if (peak > 0) {
        maxDd = Math.max(maxDd, (peak - v) / peak);
      }
    }
    return maxDd;
  }

  /** Annualised Sharpe from the monthly series (risk-free = 0): {@code mean/stdev · √12}. */
  private static double sharpe(List<Double> monthly, double mean) {
    if (monthly.size() < 2) {
      return 0;
    }
    double var = 0;
    for (double r : monthly) {
      double d = r - mean;
      var += d * d;
    }
    var /= (monthly.size() - 1);
    double sd = Math.sqrt(var);
    return sd == 0 ? 0 : mean / sd * Math.sqrt(12.0);
  }

  private static double sum(double[] a) {
    double s = 0;
    for (double v : a) {
      s += v;
    }
    return s;
  }

  // event packing: bits [63..24]=epochDay+offset, [23..20]=type, [19..0]=idx (n < ~1M). Sorts by date then type.
  private static final long DATE_OFFSET = 1_000_000L; // keep epochDay (can be negative pre-1970) positive

  private static long pack(LocalDate d, int type, int idx) {
    long day = d.toEpochDay() + DATE_OFFSET;
    return (day << 24) | ((long) type << 20) | idx;
  }

  private static long unDate(long ev) {
    return (ev >>> 24) - DATE_OFFSET;
  }

  private static int unType(long ev) {
    return (int) ((ev >>> 20) & 0xF);
  }

  private static int unIdx(long ev) {
    return (int) (ev & 0xFFFFF);
  }

  /** The entry RS-rank of the trade behind {@code ev}; a NaN (unknown) sorts last. */
  private static double rsOf(List<BtTrade> trades, long ev) {
    double r = trades.get(unIdx(ev)).rsRankAtEntry();
    return Double.isNaN(r) ? Double.NEGATIVE_INFINITY : r;
  }
}
