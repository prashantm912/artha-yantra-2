package in.arthayantra.strategysignal.scalper;

import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import java.math.BigDecimal;

/**
 * E4 §3.11/§4.6 LONG-straddle LOW-IV gate (tag {@code low-iv-straddle}). A long straddle buys BOTH legs,
 * so it wants LOW implied volatility — cheap premium with most of the move still ahead; a high-IV long
 * loses both legs on an IV crush ("IV&gt;40, stay away as a buyer"). The neutral straddle path
 * (#11) takes no directional confluence, so this is its only IV refinement.
 *
 * <p>Pure + side-agnostic: reads the macro 6-strike CE/PE IV averages and stands the long straddle
 * aside when EITHER side is rich (at/above the both-high floor). A null average never blocks (the gate
 * is a refinement, not a hard data dependency) — exactly the degrade discipline of the scorer's IV reads.
 */
public final class StraddleIvGate {

  private StraddleIvGate() {}

  /**
   * Whether the macro IV is too rich for a LONG straddle — EITHER side's 6-strike IV average at/above
   * {@code props.ivBothHighFloor()} (the 40/40 level). A null macro / null average returns {@code false}
   * (the read is unavailable, so the straddle is not stood aside on missing data).
   */
  public static boolean tooRichForLong(Macro m, ScalperOiProps props) {
    if (m == null) {
      return false;
    }
    BigDecimal floor = props.ivBothHighFloor();
    return (m.ceIvAvg6() != null && m.ceIvAvg6().compareTo(floor) >= 0)
        || (m.peIvAvg6() != null && m.peIvAvg6().compareTo(floor) >= 0);
  }
}
