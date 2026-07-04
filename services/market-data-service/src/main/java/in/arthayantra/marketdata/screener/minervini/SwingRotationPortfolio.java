package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The RS-ROTATION portfolio (v7): the same {@code slots}-sleeve book as {@link SwingPortfolio}, but
 * instead of only holding a position to its natural exit (8%-stop / 50d-MA trail), it ROTATES — when a
 * new signal arrives and all slots are full, it evicts the weakest current holding (by its RS-rank AT
 * that date, not at entry) and buys the newcomer, provided the newcomer's RS beats the weakest by a
 * margin. "Sell the laggard, buy the leader." The evicted position exits early at that date's price.
 *
 * <p>A held position's CURRENT RS and its early-exit price come from a per-symbol {@link WeeklySeries}
 * (RS + close sampled weekly) — supplied by the caller, since the per-symbol backtest sim cannot see
 * the cross-symbol portfolio. Lookups are as-of (the most recent weekly point ≤ the date), so timing
 * is weekly-granular, matching the weekly RS cadence. Costs are netted identically to
 * {@link SwingPortfolio}. Deterministic; no IO.
 */
final class SwingRotationPortfolio {

  /** Per-symbol weekly (epoch-day, RS, close) series for as-of lookups. Arrays are date-ascending. */
  record WeeklySeries(long[] days, double[] rs, double[] close) {
    double rsAsOf(LocalDate d) {
      int i = idxAsOf(d.toEpochDay());
      return i < 0 ? Double.NaN : rs[i];
    }

    double closeAsOf(LocalDate d) {
      int i = idxAsOf(d.toEpochDay());
      return i < 0 ? Double.NaN : close[i];
    }

    private int idxAsOf(long epochDay) {
      int lo = 0;
      int hi = days.length - 1;
      int best = -1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        if (days[mid] <= epochDay) {
          best = mid;
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      return best;
    }
  }

  /** The rotation portfolio result + how many rotations (early laggard-for-leader swaps) fired. */
  record Outcome(SwingPortfolio.Result result, int rotations) {}

  private SwingRotationPortfolio() {}

  /**
   * Simulates the rotating book over {@code trades} (the RS-gated signals), using {@code weekly} for
   * held-position current-RS + early-exit-price lookups. {@code marginPct} is the RS-percentile edge a
   * newcomer must have over the weakest holding to evict it (churn guard). {@code costs} nets each
   * closed trade; null = gross.
   */
  static Outcome simulate(
      List<BtTrade> trades, Map<String, WeeklySeries> weekly, int slots,
      SwingPortfolio.Costs costs, double marginPct) {
    int n = trades.size();
    if (n == 0 || slots < 1) {
      return new Outcome(
          new SwingPortfolio.Result(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of()), 0);
    }
    double orderValue = costs == null ? 0 : costs.capital() / slots;

    // events: type 0 = NATURAL EXIT, 1 = ENTRY; sort by (date, exit-before-entry).
    long[] events = new long[2 * n];
    int p = 0;
    for (int i = 0; i < n; i++) {
      events[p++] = pack(trades.get(i).exitDate(), 0, i);
      events[p++] = pack(trades.get(i).entryDate(), 1, i);
    }
    Arrays.sort(events);

    double[] sleeve = new double[slots];
    Arrays.fill(sleeve, 1.0 / slots);
    int[] freeStack = new int[slots];
    for (int k = 0; k < slots; k++) {
      freeStack[k] = k;
    }
    int freeTop = slots;
    int[] sigInSleeve = new int[slots];
    Arrays.fill(sigInSleeve, -1);
    byte[] state = new byte[n]; // 0 unseen, 1 held, 2 done (closed / skipped)
    int taken = 0;
    int skipped = 0;
    int rotations = 0;
    int busy = 0;

    List<LocalDate> curveDate = new ArrayList<>();
    List<Double> curveEq = new ArrayList<>();
    List<Integer> exitYears = new ArrayList<>();
    LocalDate first = LocalDate.ofEpochDay(unDate(events[0]));
    curveDate.add(first);
    curveEq.add(1.0);
    double expoAccum = 0;
    long expoDays = 0;
    LocalDate prev = null;

    for (long ev : events) {
      LocalDate date = LocalDate.ofEpochDay(unDate(ev));
      int type = unType(ev);
      int i = unIdx(ev);
      if (prev != null) {
        long d = ChronoUnit.DAYS.between(prev, date);
        expoAccum += (double) busy * d;
        expoDays += d;
      }
      prev = date;

      if (type == 0) { // NATURAL EXIT — only if still held (not rotated out / skipped)
        if (state[i] == 1) {
          int slot = slotOf(sigInSleeve, i);
          double gross = trades.get(i).pnlPct() / 100.0;
          closeSlot(sleeve, slot, gross, trades.get(i).avgTurnoverAtEntry(), costs, orderValue);
          sigInSleeve[slot] = -1;
          freeStack[freeTop++] = slot;
          state[i] = 2;
          busy--;
          exitYears.add(date.getYear());
          curveDate.add(date);
          curveEq.add(sum(sleeve));
        }
      } else { // ENTRY
        BtTrade s = trades.get(i);
        if (freeTop > 0) {
          int slot = freeStack[--freeTop];
          sigInSleeve[slot] = i;
          state[i] = 1;
          taken++;
          busy++;
        } else {
          // all slots full — consider rotating out the weakest holding by CURRENT RS
          int weakSlot = -1;
          double weakRs = Double.POSITIVE_INFINITY;
          for (int k = 0; k < slots; k++) {
            int hs = sigInSleeve[k];
            if (hs < 0) {
              continue;
            }
            double rs = currentRs(weekly, trades.get(hs).symbol(), date);
            if (!Double.isNaN(rs) && rs < weakRs) {
              weakRs = rs;
              weakSlot = k;
            }
          }
          double newRs = s.rsRankAtEntry();
          if (weakSlot >= 0 && !Double.isNaN(newRs) && newRs > weakRs + marginPct) {
            int hs = sigInSleeve[weakSlot];
            BtTrade held = trades.get(hs);
            double rotPrice = closeAsOf(weekly, held.symbol(), date);
            if (Double.isNaN(rotPrice) || rotPrice <= 0) {
              rotPrice = held.entryPrice();
            }
            double gross = (rotPrice - held.entryPrice()) / held.entryPrice();
            closeSlot(sleeve, weakSlot, gross, held.avgTurnoverAtEntry(), costs, orderValue);
            state[hs] = 2;
            exitYears.add(date.getYear());
            curveDate.add(date);
            curveEq.add(sum(sleeve));
            rotations++;
            // the newcomer takes the freed slot (net busy unchanged)
            sigInSleeve[weakSlot] = i;
            state[i] = 1;
            taken++;
          } else {
            skipped++;
            state[i] = 2;
          }
        }
      }
    }
    double avgExpo = expoDays > 0 ? 100.0 * expoAccum / expoDays / slots : 0.0;
    return new Outcome(
        SwingPortfolio.finalize(curveDate, curveEq, taken, skipped, avgExpo, exitYears), rotations);
  }

  private static double currentRs(Map<String, WeeklySeries> weekly, String symbol, LocalDate d) {
    WeeklySeries w = weekly.get(symbol);
    return w == null ? Double.NaN : w.rsAsOf(d);
  }

  private static double closeAsOf(Map<String, WeeklySeries> weekly, String symbol, LocalDate d) {
    WeeklySeries w = weekly.get(symbol);
    return w == null ? Double.NaN : w.closeAsOf(d);
  }

  /** Compounds {@code sleeve[slot]} by the net (post-cost) return of a {@code grossFrac} trade. */
  private static void closeSlot(
      double[] sleeve, int slot, double grossFrac, double adv, SwingPortfolio.Costs costs,
      double orderValue) {
    double net = grossFrac;
    if (costs != null) {
      double participation = (adv > 0 && !Double.isNaN(adv)) ? orderValue / adv : 1.0;
      double impactPerSide =
          Math.min(costs.impactCapPct() / 100.0, costs.impactCoeff() * participation);
      double roundTrip = costs.fixedPct() / 100.0 + 2.0 * (costs.spreadPct() / 100.0 + impactPerSide);
      net = Math.max(-1.0, net - roundTrip);
    }
    sleeve[slot] *= 1.0 + net;
  }

  private static int slotOf(int[] sigInSleeve, int sig) {
    for (int k = 0; k < sigInSleeve.length; k++) {
      if (sigInSleeve[k] == sig) {
        return k;
      }
    }
    return -1;
  }

  private static double sum(double[] a) {
    double s = 0;
    for (double v : a) {
      s += v;
    }
    return s;
  }

  private static final long DATE_OFFSET = 1_000_000L;

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
}
