package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;

/**
 * The Siva §0B universal pre-flight gates as pure functions (master plan §12.1). Each returns a
 * {@link GateOutcome} so the reason can ride the signal side-channel. These are the OI/macro and
 * time/volume rails the YAML gate grammar cannot express; the chart-only alignment also lives here so
 * a scorer can read a uniform verdict. All thresholds are the verified §0B values (see
 * {@code docs/strategy-sources.md}); tuning rides DB rows, not these constants.
 */
public final class ScalperGates {

  private ScalperGates() {}

  static final LocalTime NO_TRADE_BEFORE = LocalTime.of(9, 45);
  static final LocalTime MIDDAY_BLOCK_FROM = LocalTime.of(11, 0);
  static final LocalTime MIDDAY_BLOCK_TO = LocalTime.of(13, 0);
  static final LocalTime NO_FRESH_ENTRY_AFTER = LocalTime.of(15, 30);

  // Volume-candle floors (§0B): NIFTY 125k; BANKNIFTY / SENSEX / other F&O indices 50k.
  private static final BigDecimal NIFTY_VOL = new BigDecimal("125000");
  private static final BigDecimal INDEX_VOL = new BigDecimal("50000");
  private static final Map<String, BigDecimal> VOL_FLOOR = Map.of("NIFTY 50", NIFTY_VOL);

  /** ≥09:45 (ideal 09:15–10:00), block the 11:00–13:00 sideways window, no fresh entry after 15:30. */
  public static GateOutcome timeWindow(LocalTime ist) {
    if (ist.isBefore(NO_TRADE_BEFORE)) {
      return GateOutcome.fail(null, "before 09:45 open-noise window");
    }
    if (!ist.isBefore(MIDDAY_BLOCK_FROM) && ist.isBefore(MIDDAY_BLOCK_TO)) {
      return GateOutcome.fail(null, "11:00-13:00 sideways block");
    }
    if (!ist.isBefore(NO_FRESH_ENTRY_AFTER)) {
      return GateOutcome.fail(null, "no fresh entry after 15:30");
    }
    return GateOutcome.pass(null, "within scalp window");
  }

  /**
   * #9 (section 3.9) Morning Trade window-aware overload: FAIL before {@code from} and at/after {@code
   * to}, using the strategy's own opening-tick bounds instead of the default 09:45 floor. The default's
   * 11:00-13:00 midday block is intentionally NOT applied here — an opening-tick window (e.g.
   * 09:15-09:30) never reaches it, so the extra clause would be dead. Only the opening-tick path uses
   * this; the 4 core strategies keep the no-arg {@link #timeWindow(LocalTime)} unchanged.
   */
  public static GateOutcome timeWindow(LocalTime ist, LocalTime from, LocalTime to) {
    if (ist.isBefore(from)) {
      return GateOutcome.fail(null, "before " + from + " opening-tick window");
    }
    if (!ist.isBefore(to)) {
      return GateOutcome.fail(null, "at/after " + to + " opening-tick window");
    }
    return GateOutcome.pass(null, "within opening-tick window");
  }

  /** Bar volume ≥ the underlying's floor (NIFTY 125k / other indices 50k). */
  public static GateOutcome volume(String underlying, BigDecimal volume) {
    BigDecimal floor = VOL_FLOOR.getOrDefault(underlying, INDEX_VOL);
    boolean ok = volume != null && volume.compareTo(floor) >= 0;
    return new GateOutcome(ok, volume, (ok ? "volume >= " : "volume < ") + floor.toPlainString());
  }

  /**
   * RSI(3m,14): 40–60 is NO-TRADE; CE trades 60–80, PE trades 20–40 (exhaustion caps at 80/20).
   * These follow §4.2 "Indicator Set &amp; Exact Settings" (the doc's designated single source of
   * thresholds: no-trade 40–60, CE &gt;60, PE &lt;40). §3.10/§6.10 render the band as "buy 50–75 /
   * no-trade 40–50", but a 50–60 CE floor would collide with the 40–60 no-trade zone, so §4.2 governs.
   */
  public static GateOutcome rsiBand(BigDecimal rsi, OptionType side) {
    if (rsi == null) {
      return GateOutcome.fail(null, "rsi unavailable");
    }
    double v = rsi.doubleValue();
    boolean ok = side == OptionType.CE ? (v > 60.0 && v < 80.0) : (v > 20.0 && v < 40.0);
    String want = side == OptionType.CE ? "CE wants 60-80" : "PE wants 20-40";
    return new GateOutcome(ok, rsi, ok ? want + " ok" : want + " (40-60 no-trade / exhaustion)");
  }

  /** Bull (CE): PSAR, VWMA, ST and VWAP all below price; bear (PE): all above. */
  public static GateOutcome indicatorAlignment(Chart c, OptionType side) {
    boolean ok;
    if (side == OptionType.CE) {
      ok =
          gt(c.close(), c.vwap())
              && gt(c.close(), c.vwma20())
              && gt(c.close(), c.psar())
              && c.supertrendDir() > 0;
    } else {
      ok =
          gt(c.vwap(), c.close())
              && gt(c.vwma20(), c.close())
              && gt(c.psar(), c.close())
              && c.supertrendDir() < 0;
    }
    return new GateOutcome(ok, c.close(), ok ? "indicators aligned" : "indicators not aligned");
  }

  /** Futures OI quadrant: CE needs LB/SC (bullish), PE needs SB/LU (bearish). */
  public static GateOutcome oiQuadrant(Oi oi, OptionType side) {
    boolean ok = side == OptionType.CE ? oi.futures().bullish() : oi.futures().bearish();
    return new GateOutcome(
        ok, oi.sentimentPct(), "futures " + oi.futures() + (ok ? " supports " : " opposes ") + side);
  }

  /** Adv > 32 favours CE; Dec > 32 favours PE. */
  public static GateOutcome breadth(Macro m, OptionType side) {
    int count = side == OptionType.CE ? m.advances() : m.declines();
    boolean ok = count > 32;
    String label = side == OptionType.CE ? "advances " : "declines ";
    return new GateOutcome(ok, BigDecimal.valueOf(count), label + count + (ok ? " > 32" : " <= 32"));
  }

  /** VIX falling favours CE; rising favours PE (unknown direction never blocks). */
  public static GateOutcome vix(Macro m, OptionType side) {
    if (m.vixRising() == null) {
      return GateOutcome.pass(m.vixLevel(), "vix direction unknown");
    }
    boolean ok = side == OptionType.CE ? !m.vixRising() : m.vixRising();
    String dir = m.vixRising() ? "rising" : "falling";
    return new GateOutcome(ok, m.vixLevel(), "vix " + dir + (ok ? " supports " : " opposes ") + side);
  }

  /**
   * #5 (T2.1): the trending-OI call-put delta-imbalance HARD pre-gate. PASS when the imbalance %
   * (|peDelta-ceDelta|/max(|peDelta|,|ceDelta|)*100) is at/above {@code floorPct}; FAIL when it is
   * present and below. A {@code null} imbalance (data unavailable or the flat-OI caveat the producer
   * documents) DEGRADES to PASS — it never blocks, so a missing derivation can't gate out an entry.
   */
  public static GateOutcome callPutDeltaFilter(Oi oi, BigDecimal floorPct) {
    BigDecimal imbalance = oi.callPutDeltaImbalancePct();
    if (imbalance == null) {
      return GateOutcome.pass(null, "call-put dOI imbalance unavailable (degrade -> pass)");
    }
    boolean ok = imbalance.compareTo(floorPct) >= 0;
    return new GateOutcome(
        ok,
        imbalance,
        "call-put dOI imbalance " + imbalance.toPlainString() + (ok ? " >= " : " < ") + floorPct.toPlainString());
  }

  /** Futures basis: future > spot (premium) is bullish → CE; future < spot (discount) bearish → PE. */
  public static GateOutcome futuresBasis(Oi oi, OptionType side) {
    BigDecimal basis = oi.futuresBasis();
    if (basis == null) {
      return GateOutcome.pass(null, "basis unavailable");
    }
    boolean ok = side == OptionType.CE ? basis.signum() > 0 : basis.signum() < 0;
    return new GateOutcome(ok, basis, "basis " + basis.toPlainString() + (ok ? " supports " : " opposes ") + side);
  }

  private static boolean gt(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) > 0;
  }
}
