package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The Connect-the-Dots master confluence scorer (master plan §12.3, Siva strategy #10) — the
 * architectural heart the index-option core composes from. Scores, for a given side, the 5 chart dots
 * plus the OI/macro dots over a {@link ScalperGateContext} into a single confluence aggregate, reusing
 * {@link ScalperGates} so each rule is single-sourced.
 *
 * <p>Pure + deterministic — no clock, no network; the engine's chart {@code EntryEvaluator} runs first
 * and this confirms (the typed scorer that runs alongside the chart-only YAML grammar). The result
 * rides the signal side-channel, never the frozen {@code ScoreBreakdown} (§12.9).
 *
 * <p><b>VWAP is decisive [S22/S24]</b>: it carries the highest weight AND is a hard gate — a signal is
 * never bullish/bearish when price is on the wrong side of VWAP, regardless of the aggregate. The
 * 60-minute bias must also agree with the 3-minute side. Weights here are the v1 defaults; threshold
 * (and later the weights) are intended to move to DB params, not the Java.
 */
public final class ConnectTheDotsScorer {

  private ConnectTheDotsScorer() {}

  // v1 dot weights — VWAP decisive, futures-OI quadrant next, IV-rank a soft booster.
  private static final double W_VWAP = 2.5;
  private static final double W_OI = 1.5;
  private static final double W_IV = 0.8;
  private static final double W = 1.0;
  private static final BigDecimal IV_RANK_LOW = new BigDecimal("50");

  /** One confluence dot's contribution. */
  public record DotScore(String dot, double weight, boolean supports, String reason) {}

  /** The aggregate confluence verdict for a side. */
  public record Confluence(
      BigDecimal aggregate, OptionType side, boolean bullish, boolean bearish,
      boolean vwapAligned, boolean biasAligned, List<DotScore> dots) {}

  /**
   * Score the confluence for {@code side}.
   *
   * @param ctx the per-bar snapshot
   * @param side CE on a long bias, PE on a short bias
   * @param bias60mDir the 60-minute bias direction: +1 bull, -1 bear, 0 unknown (never blocks)
   * @param threshold the aggregate a valid signal must reach (0..1)
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold) {
    Chart c = ctx.chart();
    Oi oi = ctx.oi();
    Macro m = ctx.macro();
    boolean ce = side == OptionType.CE;

    boolean vwapSide = ce ? gt(c.close(), c.vwap()) : gt(c.vwap(), c.close());

    List<DotScore> dots = new ArrayList<>();
    add(dots, "vwap", W_VWAP, vwapSide, "price vs VWAP (decisive)");
    add(dots, "supertrend", W, ce ? c.supertrendDir() > 0 : c.supertrendDir() < 0, "supertrend direction");
    add(dots, "vwma", W, ce ? gt(c.close(), c.vwma20()) : gt(c.vwma20(), c.close()), "price vs VWMA20");
    add(dots, "psar", W, ce ? gt(c.close(), c.psar()) : gt(c.psar(), c.close()), "price vs PSAR");
    add(dots, "rsi", W, ScalperGates.rsiBand(c.rsi14(), side).pass(), "RSI band");
    add(dots, "volume", W, ScalperGates.volume(ctx.underlying(), c.volume()).pass(), "volume floor");
    add(dots, "futures_oi", W_OI, ScalperGates.oiQuadrant(oi, side).pass(), "futures OI quadrant");
    add(dots, "underlying_oi", W, ce ? oi.underlying().bullish() : oi.underlying().bearish(), "underlying OI quadrant");
    add(dots, "trending_cross", W, sideSigned(oi.trendingPeMinusCePct(), ce), "trending OI cross (PE-CE)");
    add(dots, "sentiment", W, sideSigned(oi.sentimentPct(), ce), "active-strike sentiment");
    add(dots, "breadth", W, ScalperGates.breadth(m, side).pass(), "advances/declines > 32");
    add(dots, "vix", W, ScalperGates.vix(m, side).pass(), "VIX direction");
    add(dots, "basis", W, ScalperGates.futuresBasis(oi, side).pass(), "futures basis");
    add(dots, "iv_rank", W_IV, m.ivRank() != null && m.ivRank().compareTo(IV_RANK_LOW) < 0, "IV rank low (cheap premium)");

    double num = 0;
    double den = 0;
    for (DotScore d : dots) {
      den += d.weight();
      if (d.supports()) {
        num += d.weight();
      }
    }
    BigDecimal aggregate =
        den == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(num / den).setScale(4, RoundingMode.HALF_UP);

    boolean biasAligned = bias60mDir == 0 || (ce ? bias60mDir > 0 : bias60mDir < 0);
    boolean valid = vwapSide && biasAligned && aggregate.compareTo(threshold) >= 0;
    return new Confluence(aggregate, side, valid && ce, valid && !ce, vwapSide, biasAligned, dots);
  }

  private static void add(List<DotScore> dots, String name, double weight, boolean supports, String reason) {
    dots.add(new DotScore(name, weight, supports, reason));
  }

  /** CE wants the value positive (put-heavy / PE-OI rising), PE wants it negative. */
  private static boolean sideSigned(BigDecimal value, boolean ce) {
    if (value == null) {
      return false;
    }
    return ce ? value.signum() > 0 : value.signum() < 0;
  }

  private static boolean gt(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) > 0;
  }
}
