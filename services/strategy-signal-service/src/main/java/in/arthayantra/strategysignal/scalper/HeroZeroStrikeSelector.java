package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76;
import in.arthayantra.strategysignal.scalper.MarketOiClient.StrikeOi;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * #7 Hero-Zero literal strike selection (Siva cheat sheet section 7): buy the option ONE STRIKE INSIDE
 * the short-covering (SC) strike - bullish (CE) buys the CALL one strike BELOW the SC call strike,
 * bearish (PE) buys the PUT one strike ABOVE the SC put strike - never the ultra-cheap, already-covered
 * strike itself.
 *
 * <p>The SC strike is the highest-OI build-up on the side (the call writers' resistance for a bullish
 * break, the put writers' support for a bearish break); it is read off the LIVE per-strike OI ladder of
 * the chain snapshot ({@link StrikeOi}). The strike step is derived from the ladder itself (the smallest
 * gap between adjacent listed strikes), not hardcoded.
 *
 * <p><b>Live-only - parity-safe.</b> The OI ladder comes from {@link MarketOiClient}, which is never on
 * the deterministic-replay path, so this selection is a LIVE decision. The leg it returns is persisted at
 * entry (the V009 {@code scalper_detail} side-channel); a replay reads the persisted leg, never re-runs
 * this selector. {@link #select} is otherwise pure over its inputs.
 *
 * <p><b>Degrade-to-safe.</b> Empty when the OI ladder is absent, the side's max-OI strike carries no OI,
 * the step cannot be derived, the one-away target strike is not listed, or no tradeable candidate matches
 * that target on the side. The caller then falls back to the shared {@link StrikePicker} delta/premium
 * band - never a crash.
 */
public final class HeroZeroStrikeSelector {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalTime EXPIRY_CUTOFF = LocalTime.of(15, 30);
  private static final double SECONDS_PER_YEAR = 365.0 * 24 * 60 * 60;

  private HeroZeroStrikeSelector() {}

  /**
   * Resolve the literal one-strike-inside-the-SC-strike leg, or empty to fall back to {@link
   * StrikePicker}.
   *
   * @param candidates the tradeable chain legs (both sides; the wrong side / wrong strike are ignored)
   * @param strikeOi the LIVE per-strike CE/PE OI ladder (the SC-strike + step source); empty/null degrades
   * @param spot underlying spot (the forward = spot + basis for the delta the side-channel records)
   * @param basis forward - spot
   * @param side CE (bullish, one strike BELOW the max-CE-OI strike) or PE (bearish, one ABOVE the max-PE-OI)
   * @param now the bar instant (deterministic; the StrikePicker expiry clock)
   * @param expiry the option expiry
   * @param rate the Black-76 risk-free rate (delta is near rate-insensitive for short-dated options)
   */
  public static Optional<StrikePicker.Pick> select(
      List<StrikePicker.Candidate> candidates,
      List<StrikeOi> strikeOi,
      BigDecimal spot,
      BigDecimal basis,
      Black76.OptionType side,
      Instant now,
      LocalDate expiry,
      double rate) {
    if (strikeOi == null || strikeOi.isEmpty() || spot == null) {
      return Optional.empty();
    }
    boolean ce = side == Black76.OptionType.CE;
    BigDecimal scStrike = maxOiStrike(strikeOi, ce);
    BigDecimal step = strikeStep(strikeOi);
    if (scStrike == null || step == null) {
      return Optional.empty();
    }
    // ONE STRIKE INSIDE the SC strike: below for a bullish CE, above for a bearish PE.
    BigDecimal target = ce ? scStrike.subtract(step) : scStrike.add(step);
    double t = yearsToExpiry(now, expiry);
    if (t <= 0) {
      return Optional.empty(); // past the 15:30 IST cutoff - no live option to trade
    }
    double forward = spot.add(basis).doubleValue();
    for (StrikePicker.Candidate c : candidates) {
      if (c.type() != side || c.strike() == null || c.strike().compareTo(target) != 0) {
        continue;
      }
      if (c.ltp() == null || c.iv() == null || c.iv().signum() <= 0) {
        return Optional.empty(); // the target leg exists but is untradeable - degrade to the band pick
      }
      Black76.Greeks g =
          Black76.greeks(side, forward, c.strike().doubleValue(), t, rate, c.iv().doubleValue());
      return Optional.of(new StrikePicker.Pick(c, BigDecimal.valueOf(Math.abs(g.delta().doubleValue()))));
    }
    return Optional.empty(); // the one-away target strike is not listed / has no tradeable leg
  }

  /** The strike with the greatest CE (bullish) / PE (bearish) OI; null when no strike carries OI. */
  private static BigDecimal maxOiStrike(List<StrikeOi> ladder, boolean ce) {
    BigDecimal best = null;
    long bestOi = 0;
    for (StrikeOi s : ladder) {
      Long oi = ce ? s.ceOi() : s.peOi();
      if (s.strike() == null || oi == null || oi <= 0) {
        continue;
      }
      // Highest OI wins; on a tie the lower strike wins (deterministic, not list-order luck).
      if (best == null || oi > bestOi || (oi == bestOi && s.strike().compareTo(best) < 0)) {
        bestOi = oi;
        best = s.strike();
      }
    }
    return best;
  }

  /** The smallest positive gap between adjacent listed strikes; null when fewer than two distinct strikes. */
  private static BigDecimal strikeStep(List<StrikeOi> ladder) {
    BigDecimal step = null;
    for (StrikeOi a : ladder) {
      if (a.strike() == null) {
        continue;
      }
      for (StrikeOi b : ladder) {
        if (b.strike() == null) {
          continue;
        }
        BigDecimal gap = b.strike().subtract(a.strike());
        if (gap.signum() > 0 && (step == null || gap.compareTo(step) < 0)) {
          step = gap;
        }
      }
    }
    return step;
  }

  private static double yearsToExpiry(Instant now, LocalDate expiry) {
    long secondsLeft =
        Duration.between(now, expiry.atTime(EXPIRY_CUTOFF).atOffset(IST).toInstant()).toSeconds();
    return secondsLeft <= 0 ? 0.0 : secondsLeft / SECONDS_PER_YEAR;
  }
}
