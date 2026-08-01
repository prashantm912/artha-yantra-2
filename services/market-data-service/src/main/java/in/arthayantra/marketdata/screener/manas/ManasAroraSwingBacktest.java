package in.arthayantra.marketdata.screener.manas;

import static in.arthayantra.marketdata.screener.SwingFillPrice.AT_CLOSE;
import static in.arthayantra.marketdata.screener.SwingFillPrice.fillPrice;

import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpFootprint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The Manas Arora swing BACKTEST sim — a faithful, event-driven replay of the two Manas setups over
 * one equity's full daily history, forking the sibling {@code MinerviniSwingBacktest} structure but
 * encoding Manas's own entry gates, ATR-based exit doctrine, and pyramiding. Manas is a simplified,
 * India-adapted momentum method (long-only cash equities); this sim gives the trade-level stats
 * (win-rate / payoff / expectancy / hold-time) over ~11 years — what a buy-and-hold hit-rate harness
 * cannot show.
 *
 * <p>The sim seeds the geometry itself: it recomputes the {@link VcpDetector} vcp pivot and the
 * {@link ConsolidationBreakout} breakout pivot at a weekly cadence (the live-screen rhythm), applies
 * the §4.1 selection gates on the bar, and takes an entry only on a breakout across the pivot on
 * expanding volume (§3.2 / §3.3):
 *
 * <ul>
 *   <li><b>breakout</b> — price crosses above the nearest swing-high pivot of a 2–8-week range on
 *       expanding volume (§3.2).
 *   <li><b>vcp</b> — price crosses above the VCP pivot on expanding volume (§3.3).
 * </ul>
 *
 * <p>Exit (§3.5): an initial stop of {@code entry − 2×ATR(20)} capped at {@code stopCapPct}; once the
 * position is up {@code trailArmPct}, a canonical Chandelier trailing stop ({@code highest-high since
 * entry − 2×rolling-ATR}, floored at the cost basis / breakeven) ratchets up; a
 * too-fast move ({@code fastMovePct} in ≤{@code fastMoveBars} sessions) and a parabolic extension
 * ({@code parabolicPct} above the 10-day MA) both force a square-off. No hard time-in-trade — hold
 * while the trend and stop hold.
 *
 * <p>Pyramiding (§3.4, add-to-winner): when an open position is up {@code pyramidArmPct} and a fresh
 * valid entry (a new pivot crossover) fires, a second/third lot is ADDED, each modelled as its own
 * lot with its OWN 2×ATR stop. A trailing-stop hit closes ALL lots; an individual lot's initial-stop
 * hit closes only that lot. Total open risk is bounded to {@code maxOpenRiskPct} (a new lot is
 * refused when it would breach the budget). Unlike the paper ledger (which averages adds into one
 * position), each lot is a distinct {@link BtTrade} tagged with its pyramid level, so the backtest
 * measures TRUE pyramiding P&amp;L per lot.
 *
 * <p>Pure + deterministic (no IO, no clock): the caller supplies the bars, the two detectors, an
 * optional per-bar cross-sectional RS-rank series, and the {@link Variant}s. Returns {@link BtTrade}s
 * shaped compatibly with the Minervini sim so {@code SwingPortfolio} consumes an adapted view.
 */
public final class ManasAroraSwingBacktest {

  /** One closed backtest lot, tagged with the {@link Variant}, setup, and pyramid level. */
  public record BtTrade(
      String variant,
      String setup,
      String symbol,
      LocalDate entryDate,
      double entryPrice,
      LocalDate exitDate,
      double exitPrice,
      double pnlPct,
      int barsHeld,
      String exitReason,
      double rsRankAtEntry,
      double avgTurnoverAtEntry,
      int pyramidLevel) {}

  /**
   * One backtest configuration. {@code useRealRs} gates on the caller-supplied per-bar RS-rank at
   * {@code rsMin} (else the RS gate is relaxed — fed a passing constant); {@code turnoverFloor} in
   * rupees/day gates on the 20-day average traded value (0 = off); {@code pyramiding} toggles the
   * add-to-winner behaviour (off = one lot per position, the A/B baseline).
   */
  public record Variant(
      String name,
      boolean useRealRs,
      double rsMin,
      double turnoverFloor,
      boolean pyramiding,
      String fillTiming) {

    public Variant(
        String name, boolean useRealRs, double rsMin, double turnoverFloor, boolean pyramiding) {
      this(name, useRealRs, rsMin, turnoverFloor, pyramiding, AT_CLOSE);
    }
  }

  /** The technical-only, single-lot baseline (the v1 numbers). */
  public static final Variant V1 = new Variant("v1-technical", false, 0, 0, false);

  // the live doctrine, mirrored as constants; the service overrides via @Value where it re-derives.
  private static final double ATR_MULT = 2.0; // §3.5 stop = entry − 2×ATR
  private static final int ATR_PERIOD = 20; // ATR(20)
  private static final double STOP_CAP_PCT = 0.10; // §3.5 initial stop never worse than ~10%
  private static final double TRAIL_ARM_PCT = 0.09; // arm the ATR trail once up ~9%
  private static final double FAST_MOVE_PCT = 0.35; // §3.5 too-fast move: +35% ...
  private static final int FAST_MOVE_BARS = 3; // ... within ≤3 sessions → square off
  private static final double PARABOLIC_PCT = 0.40; // §3.5 parabolic: 40% above the 10-day MA → exit
  private static final int PARABOLIC_MA = 10; // the 10-period MA parabolic reference
  private static final double PYRAMID_ARM_PCT = 0.06; // §3.4 add when the base lot is up ~6% ...
  private static final double MAX_OPEN_RISK_PCT = 0.06; // ... while total open risk stays ≤ 6%
  private static final int MAX_LOTS = 3; // §3.4 up to 3 lots per position

  private static final double VOL_MIN = 1.2; // expanding-volume gate (§4.7)
  private static final int WEEKLY = 5; // geometry recompute cadence (sessions)
  private static final int GEO_LOOKBACK = 400; // detector window
  private static final int MIN_BARS = 260; // 252 for the 52-week gate + slack
  private static final int TURNOVER_LOOKBACK = 20; // avg traded-value window for the liquidity floor

  /** The two Manas setups. */
  public static final List<String> SETUPS = List.of("breakout", "vcp");

  private final double atrMult;
  private final int atrPeriod;
  private final double stopCapPct;
  private final double trailArmPct;
  private final double fastMovePct;
  private final int fastMoveBars;
  private final double parabolicPct;
  private final int parabolicMa;
  private final double pyramidArmPct;
  private final double maxOpenRiskPct;
  private final int maxLots;
  private final double volMin;

  /** All-defaults constructor (the test path + the {@link #simulate} static convenience). */
  public ManasAroraSwingBacktest() {
    this(
        ATR_MULT, ATR_PERIOD, STOP_CAP_PCT, TRAIL_ARM_PCT, FAST_MOVE_PCT, FAST_MOVE_BARS,
        PARABOLIC_PCT, PARABOLIC_MA, PYRAMID_ARM_PCT, MAX_OPEN_RISK_PCT, MAX_LOTS, VOL_MIN);
  }

  /** Config-tunable constructor (the service wires the {@code artha.manas-arora.backtest.*} values). */
  public ManasAroraSwingBacktest(
      double atrMult,
      int atrPeriod,
      double stopCapPct,
      double trailArmPct,
      double fastMovePct,
      int fastMoveBars,
      double parabolicPct,
      int parabolicMa,
      double pyramidArmPct,
      double maxOpenRiskPct,
      int maxLots,
      double volMin) {
    this.atrMult = atrMult;
    this.atrPeriod = atrPeriod;
    this.stopCapPct = stopCapPct;
    this.trailArmPct = trailArmPct;
    this.fastMovePct = fastMovePct;
    this.fastMoveBars = fastMoveBars;
    this.parabolicPct = parabolicPct;
    this.parabolicMa = parabolicMa;
    this.pyramidArmPct = pyramidArmPct;
    this.maxOpenRiskPct = maxOpenRiskPct;
    this.maxLots = maxLots;
    this.volMin = volMin;
  }

  /** Backward-compatible single-variant (v1) convenience — used by the unit test. */
  public List<BtTrade> simulate(
      String symbol,
      List<DailyBar> bars,
      VcpDetector vcpDetector,
      ConsolidationBreakout breakoutDetector,
      LocalDate from) {
    return simulate(symbol, bars, vcpDetector, breakoutDetector, from, null, List.of(V1));
  }

  /**
   * Simulates every {@code variant} of both setups over {@code bars} (oldest→newest) for {@code
   * symbol}, from the first bar on or after {@code from}. Each (variant, setup) runs an independent
   * position book with pyramiding; open-at-end lots are dropped (not counted). {@code rsRank} is the
   * per-bar cross-sectional RS percentile the caller computed (may be null). Returns every closed lot.
   */
  public List<BtTrade> simulate(
      String symbol,
      List<DailyBar> bars,
      VcpDetector vcpDetector,
      ConsolidationBreakout breakoutDetector,
      LocalDate from,
      double[] rsRank,
      List<Variant> variants) {
    int n = bars.size();
    if (n < MIN_BARS) {
      return List.of();
    }
    double[] close = new double[n];
    double[] high = new double[n];
    double[] low = new double[n];
    double[] volume = new double[n];
    for (int i = 0; i < n; i++) {
      DailyBar b = bars.get(i);
      close[i] = b.close();
      high[i] = b.high();
      low[i] = b.low();
      volume[i] = b.volume();
    }
    double[] sma50 = sma(close, 50);
    double[] sma200 = sma(close, 200);
    double[] sma10 = sma(close, parabolicMa);
    double[] high52wPrior = rollingMaxPrior(high, 252); // prior-252 high, EXCLUDING the current bar
    double[] high52wIncl = rollingMax(high, 252); // inclusive 52-week high for the universe/gate-6 math
    double[] low52w = rollingMin(low, 252);
    double[] recentHigh = rollingMax(high, 126); // gate 6: a new 52w high within ~6 months
    double[] volRatio50 = volumeRatio(volume, 50);
    double[] turnover20 = avgTurnover(close, volume, TURNOVER_LOOKBACK);
    double[] atr = atr(high, low, close, atrPeriod);
    double[] rs = rsRank != null ? rsRank : new double[n];

    // weekly geometry: the vcp pivot + the breakout pivot, valid until the next weekly recompute.
    double[] vcpPivot = new double[n];
    boolean[] isVcp = new boolean[n];
    double[] breakoutPivot = new double[n];
    double curVcpPivot = 0;
    boolean curVcp = false;
    double curBreakoutPivot = 0;
    for (int i = 0; i < n; i++) {
      if (i >= MIN_BARS && (i % WEEKLY == 0 || i == MIN_BARS)) {
        int start = Math.max(0, i - GEO_LOOKBACK + 1);
        List<DailyBar> window = bars.subList(start, i + 1);
        VcpFootprint f = vcpDetector.detect(window);
        curVcp = f.vcp();
        curVcpPivot = f.pivot();
        curBreakoutPivot = breakoutDetector.detect(window).pivot(); // 0 when no valid base
      }
      vcpPivot[i] = curVcpPivot;
      isVcp[i] = curVcp;
      breakoutPivot[i] = curBreakoutPivot;
    }

    List<BtTrade> trades = new ArrayList<>();
    for (Variant v : variants) {
      for (String setup : SETUPS) {
        simulateSetup(
            v, setup, symbol, bars, from, close, high, sma50, sma200, sma10, high52wIncl, low52w,
            recentHigh, volRatio50, turnover20, atr, rs, vcpPivot, isVcp, breakoutPivot, trades);
      }
    }
    return trades;
  }

  /** One open pyramid lot: its entry index/price and its own ATR-based initial stop. */
  private static final class Lot {
    final int entryIdx;
    final double entryPrice;
    final double initialStop;
    final int level;

    Lot(int entryIdx, double entryPrice, double initialStop, int level) {
      this.entryIdx = entryIdx;
      this.entryPrice = entryPrice;
      this.initialStop = initialStop;
      this.level = level;
    }
  }

  private void simulateSetup(
      Variant v, String setup, String symbol, List<DailyBar> bars, LocalDate from, double[] close,
      double[] high,
      double[] sma50, double[] sma200, double[] sma10, double[] high52wIncl, double[] low52w,
      double[] recentHigh, double[] volRatio50, double[] turnover20, double[] atr, double[] rsRank,
      double[] vcpPivot, boolean[] isVcp, double[] breakoutPivot, List<BtTrade> out) {
    int n = close.length;
    List<Lot> lots = new ArrayList<>();
    double basisCost = 0; // first signal-bar close — the unchanged stop/trail level anchor
    boolean trailArmed = false;
    double trailStop = 0;
    double runHigh = 0; // H4: running highest HIGH since entry — the Chandelier trail anchor
    for (int i = MIN_BARS; i < n; i++) {
      LocalDate date = bars.get(i).date();
      if (lots.isEmpty()) {
        if (date.isBefore(from)) {
          continue;
        }
        if (entryFires(v, setup, i, close, sma50, sma200, high52wIncl, low52w, recentHigh,
            volRatio50, turnover20, rsRank, vcpPivot, isVcp, breakoutPivot)) {
          double fill = fillPrice(bars, i, v.fillTiming());
          if (Double.isNaN(fill)) {
            continue; // next_open signal on the final bar cannot fill
          }
          // The initial-stop level stays on the signal close; only the execution price moves.
          double stop = initialStop(close[i], atr[i]);
          lots.add(new Lot(i, fill, stop, 1));
          basisCost = close[i];
          trailArmed = false;
          trailStop = 0;
          runHigh = high[i];
        }
        continue;
      }

      // H4 §3.5B canonical Chandelier trail, evaluated SAME-BAR (the EOD batch has the full bar):
      // anchor = highest HIGH since entry, distance = atrMult × ROLLING ATR(i), floored at breakeven
      // (cost basis) once armed. Armed once the high is up trailArmPct off the cost basis. Computed
      // BEFORE the exits so today's close is checked against today's ratcheted level (matches the live
      // ExitEvaluator, which reads peak/close on the same bar).
      runHigh = Math.max(runHigh, high[i]);
      if (!trailArmed && runHigh >= basisCost * (1.0 + trailArmPct)) {
        trailArmed = true;
        trailStop = basisCost; // breakeven floor
      }
      if (trailArmed && !Double.isNaN(atr[i])) {
        trailStop = Math.max(trailStop, runHigh - atrMult * atr[i]);
      }

      // 1) whole-position square-offs (trailing stop / fast-move / parabolic) close EVERY lot.
      String positionExit = positionExit(i, close, sma10, trailArmed, trailStop);
      if (positionExit != null) {
        double exitPrice = fillPrice(bars, i, v.fillTiming());
        if (Double.isNaN(exitPrice)) {
          continue; // unfilled final-bar exit leaves open-at-end lots, which are dropped
        }
        for (Lot lot : lots) {
          out.add(closeLot(v, setup, symbol, bars, lot, i, exitPrice, positionExit, rsRank, turnover20));
        }
        lots.clear();
        trailArmed = false;
        continue;
      }

      // 2) per-lot initial-stop hits close only the breached lot(s).
      List<Lot> survivors = new ArrayList<>();
      for (Lot lot : lots) {
        if (stopBreached(close[i], lot.initialStop)) {
          double exitPrice = fillPrice(bars, i, v.fillTiming());
          if (Double.isNaN(exitPrice)) {
            survivors.add(lot); // final-bar stop cannot execute at a nonexistent next open
          } else {
            out.add(
                closeLot(
                    v, setup, symbol, bars, lot, i, exitPrice, "STOP_LOSS", rsRank, turnover20));
          }
        } else {
          survivors.add(lot);
        }
      }
      if (survivors.size() != lots.size()) {
        lots = survivors;
        if (lots.isEmpty()) {
          trailArmed = false;
          continue;
        }
      }

      // 4) pyramiding (§3.4): a fresh valid entry adds a lot while up pyramidArmPct and risk is bounded.
      if (v.pyramiding()
          && lots.size() < maxLots
          && close[i] >= basisCost * (1.0 + pyramidArmPct)
          && entryFires(v, setup, i, close, sma50, sma200, high52wIncl, low52w, recentHigh,
              volRatio50, turnover20, rsRank, vcpPivot, isVcp, breakoutPivot)) {
        double fill = fillPrice(bars, i, v.fillTiming());
        // The new lot's stop level stays on the signal close; only the execution price moves.
        double stop = initialStop(close[i], atr[i]);
        if (!Double.isNaN(fill) && withinRiskBudget(lots, fill, stop, trailArmed, trailStop)) {
          lots.add(new Lot(i, fill, stop, lots.size() + 1));
        }
      }
    }
    // open-at-end lots are intentionally dropped (unrealized — not a closed trade).
  }

  /**
   * The initial protective stop for a lot: {@code entry − atrMult×ATR}, never worse than
   * stopCapPct. Package-private (M7, #128) so the swing exit-equivalence characterization
   * fixture ({@code ManasSwingExitEquivalenceTest}) can drive this SAME production formula
   * directly.
   */
  double initialStop(double entry, double atr) {
    double atrStop = Double.isNaN(atr) ? entry * (1.0 - stopCapPct) : entry - atrMult * atr;
    double cap = entry * (1.0 - stopCapPct);
    return Math.max(atrStop, cap); // the tighter (higher) of the ATR stop and the cap
  }

  /**
   * The per-lot initial-stop HIT check (simulateSetup's per-lot loop): a close AT OR BELOW the
   * stop level breaches it. Extracted to a package-private predicate (M7, #128) so the swing
   * exit-equivalence characterization fixture can assert this EXACT comparison — not just the
   * {@link #initialStop} LEVEL — against the live {@code ExitEvaluator}'s own hit/no-hit decision.
   */
  static boolean stopBreached(double close, double stopLevel) {
    return close <= stopLevel;
  }

  /**
   * Whether adding a lot at {@code entry} (stop {@code newStop}) keeps the position's total OPEN risk
   * ≤ {@code maxOpenRiskPct} of the aggregate cost. Each lot's risk is its distance to its effective
   * stop (the trailing stop when armed and higher than the lot's own initial stop), floored at 0 for
   * lots already above water.
   */
  private boolean withinRiskBudget(
      List<Lot> lots, double entry, double newStop, boolean trailArmed, double trailStop) {
    double riskRupees = 0;
    double costRupees = 0;
    for (Lot lot : lots) {
      double stop = trailArmed ? Math.max(lot.initialStop, trailStop) : lot.initialStop;
      riskRupees += Math.max(0, lot.entryPrice - stop);
      costRupees += lot.entryPrice;
    }
    riskRupees += Math.max(0, entry - newStop);
    costRupees += entry;
    return costRupees <= 0 || riskRupees / costRupees <= maxOpenRiskPct;
  }

  /** Assembles a closed {@link BtTrade} for one lot. */
  private static BtTrade closeLot(
      Variant v, String setup, String symbol, List<DailyBar> bars, Lot lot, int exitIdx,
      double exitPrice, String reason, double[] rsRank, double[] turnover20) {
    return new BtTrade(
        v.name(), setup, symbol, bars.get(lot.entryIdx).date(), lot.entryPrice,
        bars.get(exitIdx).date(), exitPrice,
        (exitPrice - lot.entryPrice) / lot.entryPrice * 100.0, exitIdx - lot.entryIdx, reason,
        rsRank[lot.entryIdx], turnover20[lot.entryIdx], lot.level);
  }

  /**
   * The whole-position exit doctrine on bar {@code i} (§3.5), in order: the armed ATR trailing stop, a
   * too-fast move (a fastMovePct spike in ≤fastMoveBars sessions), then a parabolic extension
   * (parabolicPct above the 10-day MA). Returns the reason, or null to hold.
   */
  // Package-private (M7, #128): the swing exit-equivalence characterization fixture
  // (ManasSwingExitEquivalenceTest) drives this SAME production formula directly to
  // characterize the M6 entry-bar divergence (the formula agrees; only WHEN it is called
  // during simulateSetup's per-bar loop differs).
  String positionExit(
      int i, double[] close, double[] sma10, boolean trailArmed, double trailStop) {
    if (trailArmed && close[i] <= trailStop) {
      return "TRAILING_STOP";
    }
    if (i >= fastMoveBars && close[i] >= close[i - fastMoveBars] * (1.0 + fastMovePct)) {
      return "FAST_MOVE";
    }
    if (!Double.isNaN(sma10[i]) && sma10[i] > 0 && close[i] >= sma10[i] * (1.0 + parabolicPct)) {
      return "PARABOLIC";
    }
    return null;
  }

  /** The setup-specific entry gate on bar {@code i}: the §4.1 selection gates + the pivot crossover. */
  private boolean entryFires(
      Variant v, String setup, int i, double[] close, double[] sma50, double[] sma200,
      double[] high52wIncl, double[] low52w, double[] recentHigh, double[] volRatio50,
      double[] turnover20, double[] rsRank, double[] vcpPivot, boolean[] isVcp,
      double[] breakoutPivot) {
    if (!selectionGates(v, i, close, sma50, sma200, high52wIncl, low52w, recentHigh, rsRank)) {
      return false;
    }
    if (v.turnoverFloor() > 0
        && (Double.isNaN(turnover20[i]) || turnover20[i] < v.turnoverFloor())) {
      return false;
    }
    if (volRatio50[i] <= volMin) {
      return false; // §4.7 expanding-volume breakout
    }
    return switch (setup) {
      case "breakout" -> crossover(close, breakoutPivot, i);
      case "vcp" -> isVcp[i] && crossover(close, vcpPivot, i);
      default -> false;
    };
  }

  /**
   * The Manas §4.1 selection gates on bar {@code i} + the §1.2 universe (within 25% of the 52-week
   * high) + the ≥100%-above-52w-low structure. v1 relaxes the cross-sectional RS gate (fed a passing
   * constant); v2 feeds the caller-computed per-bar RS percentile at {@code rsMin} (a NaN percentile
   * — pre-warmup or no universe distribution — fails, so an unconfirmed-RS bar takes no trade).
   */
  private boolean selectionGates(
      Variant v, int i, double[] close, double[] sma50, double[] sma200, double[] high52wIncl,
      double[] low52w, double[] recentHigh, double[] rsRank) {
    if (v.useRealRs()) {
      double rs = rsRank[i];
      if (Double.isNaN(rs) || rs < v.rsMin()) {
        return false;
      }
    }
    boolean[] g =
        ManasGates.gates(
            bd(close[i]), bd(sma50[i]), bd(sma200[i]),
            i >= 63 ? bd(sma200[i - 63]) : null, // 200-day rising over ~3 months
            bd(high52wIncl[i]), bd(low52w[i]), bd(recentHigh[i]),
            new BigDecimal("30"), // §4.1 gate 1: price ≥ ₹30
            new BigDecimal("100")); // §4.1 gate 5: ≥100% above the 52-week low
    if (ManasGates.passed(g) != 6) {
      return false;
    }
    return ManasGates.withinHigh(bd(close[i]), bd(high52wIncl[i]), new BigDecimal("25"));
  }

  /** crossover(close, level) at i: prev close ≤ level AND current close > level (level fixed for the week). */
  private static boolean crossover(double[] close, double[] level, int i) {
    return i > 0 && level[i] > 0 && close[i - 1] <= level[i] && close[i] > level[i];
  }

  private static BigDecimal bd(double v) {
    return Double.isNaN(v) ? null : BigDecimal.valueOf(v);
  }

  // ---- rolling series helpers -------------------------------------------------------------------

  private static double[] sma(double[] v, int period) {
    double[] out = new double[v.length];
    double sum = 0;
    for (int i = 0; i < v.length; i++) {
      sum += v[i];
      if (i >= period) {
        sum -= v[i - period];
      }
      out[i] = i >= period - 1 ? sum / period : Double.NaN;
    }
    return out;
  }

  /** The classic Wilder-smoothed ATR over {@code period}; NaN before the first full window. */
  private static double[] atr(double[] high, double[] low, double[] close, int period) {
    int n = high.length;
    double[] out = new double[n];
    double[] tr = new double[n];
    for (int i = 0; i < n; i++) {
      if (i == 0) {
        tr[i] = high[i] - low[i];
      } else {
        double a = high[i] - low[i];
        double b = Math.abs(high[i] - close[i - 1]);
        double c = Math.abs(low[i] - close[i - 1]);
        tr[i] = Math.max(a, Math.max(b, c));
      }
      out[i] = Double.NaN;
    }
    if (n <= period) {
      return out;
    }
    double sum = 0;
    for (int i = 1; i <= period; i++) {
      sum += tr[i];
    }
    double prev = sum / period;
    out[period] = prev;
    for (int i = period + 1; i < n; i++) {
      prev = (prev * (period - 1) + tr[i]) / period;
      out[i] = prev;
    }
    return out;
  }

  private static double[] rollingMaxPrior(double[] v, int period) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < period) {
        out[i] = Double.NaN;
        continue;
      }
      double m = Double.NEGATIVE_INFINITY;
      for (int j = i - period; j < i; j++) {
        m = Math.max(m, v[j]);
      }
      out[i] = m;
    }
    return out;
  }

  private static double[] rollingMax(double[] v, int period) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < period - 1) {
        out[i] = Double.NaN;
        continue;
      }
      double m = Double.NEGATIVE_INFINITY;
      for (int j = i - period + 1; j <= i; j++) {
        m = Math.max(m, v[j]);
      }
      out[i] = m;
    }
    return out;
  }

  private static double[] rollingMin(double[] v, int period) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < period - 1) {
        out[i] = Double.NaN;
        continue;
      }
      double m = Double.POSITIVE_INFINITY;
      for (int j = i - period + 1; j <= i; j++) {
        m = Math.min(m, v[j]);
      }
      out[i] = m;
    }
    return out;
  }

  private static double[] volumeRatio(double[] v, int lookback) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < lookback) {
        out[i] = 0;
        continue;
      }
      double sum = 0;
      for (int j = i - lookback; j < i; j++) {
        sum += v[j];
      }
      double avg = sum / lookback;
      out[i] = avg > 0 ? v[i] / avg : 0;
    }
    return out;
  }

  /** Trailing {@code lookback}-day average traded value (close·volume) — the liquidity/tradability floor. */
  private static double[] avgTurnover(double[] close, double[] volume, int lookback) {
    double[] out = new double[close.length];
    for (int i = 0; i < close.length; i++) {
      if (i < lookback - 1) {
        out[i] = Double.NaN;
        continue;
      }
      double sum = 0;
      for (int j = i - lookback + 1; j <= i; j++) {
        sum += close[j] * volume[j];
      }
      out[i] = sum / lookback;
    }
    return out;
  }
}
