package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Picks the two ATM legs (Call + Put on the SAME strike, SAME expiry) a §3.11 LONG-straddle scalper
 * trades. Where {@link StrikePicker} chooses ONE slightly-ITM directional leg by a 0.6–0.7 delta band,
 * a straddle is direction-NEUTRAL and trades BOTH legs of the ATM strike (the source: "Both legs = ATM,
 * same strike, same expiry" — §3.11, Consolidated Strategy §3.11; the ATM strike carries delta≈0.5 on
 * each leg, the most extrinsic/time value and most sensitive to the move).
 *
 * <p>The ATM strike is the listed strike whose distance to the forward ({@code spot + basis}) is
 * smallest AND which has BOTH a CE and a PE candidate in the chain snapshot. The per-leg {@code |delta|}
 * is computed with Black-76 (for the side-channel record only — selection is by ATM distance, not delta,
 * exactly mirroring the source's strike rule). Both legs are BUYs (defined risk = the combined premium
 * paid); the SHORT straddle (SELL legs) is SPAN-deferred and never picked here.
 *
 * <p><b>Pure + deterministic.</b> No network, no wall-clock — {@code now} is passed in (the parity rule
 * §12.9), so a backtest replay recomputes byte-identically. The year-fraction mirrors {@link
 * StrikePicker} (ACT/365 to 15:30 IST) so the two stay in lock-step.
 */
public final class StraddleLegPicker {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalTime EXPIRY_CUTOFF = LocalTime.of(15, 30);
  private static final double SECONDS_PER_YEAR = 365.0 * 24 * 60 * 60;

  private StraddleLegPicker() {}

  /** The two ATM legs the long straddle buys — the CE leg and the PE leg of the same strike. */
  public record Straddle(BigDecimal strike, StrikePicker.Pick call, StrikePicker.Pick put) {}

  /**
   * Choose the ATM strike (nearest the forward, with both a CE and a PE candidate) and return both
   * legs, or empty if no strike carries a complete CE+PE pair (or the option is past the 15:30 cutoff).
   *
   * @param chain per-strike snapshot, both sides (the {@link MarketOiClient} chain flattens CE+PE)
   * @param spot underlying spot
   * @param basis future − spot (forward = spot + basis)
   * @param now the bar instant (deterministic; never wall-clock)
   * @param expiry option expiry date
   * @param rate the Black-76 risk-free rate (delta is near rate-insensitive for short-dated options)
   */
  public static Optional<Straddle> pick(
      List<StrikePicker.Candidate> chain,
      BigDecimal spot,
      BigDecimal basis,
      Instant now,
      LocalDate expiry,
      double rate) {
    double t = yearsToExpiry(now, expiry);
    if (t <= 0) {
      return Optional.empty(); // past the 15:30 IST cutoff — no live option to trade
    }
    BigDecimal forward = spot.add(basis);
    BigDecimal atmStrike = null;
    StrikePicker.Candidate ce = null;
    StrikePicker.Candidate pe = null;
    BigDecimal bestDist = null;
    // Walk the distinct strikes; the ATM is the one nearest the forward that has BOTH legs.
    for (StrikePicker.Candidate c : chain) {
      if (c.strike() == null || c.ltp() == null || c.iv() == null || c.iv().signum() <= 0) {
        continue;
      }
      StrikePicker.Candidate call = leg(chain, c.strike(), Black76.OptionType.CE);
      StrikePicker.Candidate put = leg(chain, c.strike(), Black76.OptionType.PE);
      if (call == null || put == null) {
        continue; // not a complete ATM pair — a straddle needs both legs of the SAME strike
      }
      BigDecimal dist = c.strike().subtract(forward).abs();
      if (bestDist == null || dist.compareTo(bestDist) < 0) {
        bestDist = dist;
        atmStrike = c.strike();
        ce = call;
        pe = put;
      }
    }
    if (atmStrike == null) {
      return Optional.empty();
    }
    double fwd = forward.doubleValue();
    StrikePicker.Pick callPick = pickOf(ce, fwd, t, rate);
    StrikePicker.Pick putPick = pickOf(pe, fwd, t, rate);
    return Optional.of(new Straddle(atmStrike, callPick, putPick));
  }

  /** The first chain candidate of {@code strike} on {@code type} with a usable ltp + iv, or null. */
  private static StrikePicker.Candidate leg(
      List<StrikePicker.Candidate> chain, BigDecimal strike, Black76.OptionType type) {
    for (StrikePicker.Candidate c : chain) {
      if (c.type() == type
          && c.strike() != null
          && c.strike().compareTo(strike) == 0
          && c.ltp() != null
          && c.iv() != null
          && c.iv().signum() > 0) {
        return c;
      }
    }
    return null;
  }

  private static StrikePicker.Pick pickOf(
      StrikePicker.Candidate c, double forward, double t, double rate) {
    Black76.Greeks g =
        Black76.greeks(c.type(), forward, c.strike().doubleValue(), t, rate, c.iv().doubleValue());
    return new StrikePicker.Pick(c, BigDecimal.valueOf(Math.abs(g.delta().doubleValue())));
  }

  private static double yearsToExpiry(Instant now, LocalDate expiry) {
    long secondsLeft =
        Duration.between(now, expiry.atTime(EXPIRY_CUTOFF).atOffset(IST).toInstant()).toSeconds();
    return secondsLeft <= 0 ? 0.0 : secondsLeft / SECONDS_PER_YEAR;
  }
}
