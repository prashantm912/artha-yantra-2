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
 * Picks the concrete option strike a scalper signal will trade (master plan §12.4 — the Siva §0B
 * "ATM±3, delta 0.6–0.7, premium N 100–250 / BN 250–400" rule). Among the supplied chain snapshot for
 * one trading side, choose the strike whose Black-76 {@code |delta|} lands in the configured band AND
 * whose premium is in the index band, preferring the delta nearest the band midpoint (the slightly-ITM
 * 0.65-ish strike Siva favours).
 *
 * <p><b>Pure + deterministic.</b> No network, no wall-clock — {@code now} is passed in, so a backtest
 * replay recomputes byte-identically (the parity rule, §12.9). The forward is {@code spot + basis}
 * (Black-76 is on the forward, B-10), and {@code iv} is taken as a volatility <b>fraction</b> (0.14,
 * not 14) — the caller normalizes the reader's units before calling.
 *
 * <p>The year-fraction mirrors market-data {@code ExpiryClock} (ACT/365 to 15:30 IST). It is
 * duplicated rather than imported because {@code ExpiryClock} lives in market-data-service; keep the
 * two conventions in lock-step (and the 5-minute {@link Black76#T_MIN} clamp inside the solver covers
 * the final-minutes degeneracy).
 */
public final class StrikePicker {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalTime EXPIRY_CUTOFF = LocalTime.of(15, 30);
  private static final double SECONDS_PER_YEAR = 365.0 * 24 * 60 * 60;

  private StrikePicker() {}

  /**
   * One strike of the chain (the caller pre-filters to the trading side + the ATM±width window).
   * {@code (exchange, tradingsymbol)} is the option's own canonical instrument key — carried through
   * so the seam can stamp the chosen leg as the signal's tradeable.
   *
   * <p><b>Both halves come from the instrument master</b> (market-data resolves the chain from
   * {@code marketdata.instruments} and publishes {@code exchange} on every chain leg). The exchange
   * is NEVER derived from the underlying's name: a prefix guess mis-routes any newly listed BSE
   * root, and a mis-routed leg 404s the instrument-meta lookup, falls back to an equity proxy with
   * lot size 1 and yields a non-lot-aligned quantity on what is now a money path (paper sizing and,
   * when {@code artha.scalper.execution=live}, broker order routing). A leg whose exchange is
   * missing is DROPPED at the chain boundary rather than guessed — see {@code MarketOiClient}.
   */
  public record Candidate(
      String exchange,
      String tradingsymbol,
      BigDecimal strike,
      Black76.OptionType type,
      BigDecimal ltp,
      BigDecimal iv) {}

  /** The chosen strike and the {@code |delta|} it was selected on. */
  public record Pick(Candidate candidate, BigDecimal delta) {}

  /**
   * Selection band. {@code rate} is the risk-free rate (Black-76 only sees it through the discount
   * factor, so delta is near-insensitive for short-dated options); {@code premiumLo/Hi} are the
   * index premium band (N 100–250 / BN 250–400).
   */
  public record Params(
      double deltaLo, double deltaHi, BigDecimal premiumLo, BigDecimal premiumHi, double rate) {

    /** Mid-point of the delta band — the ideal slightly-ITM delta to select toward. */
    public double deltaTarget() {
      return (deltaLo + deltaHi) / 2.0;
    }
  }

  /**
   * Choose the in-band strike nearest the target delta, or empty if none qualifies.
   *
   * @param chain per-strike snapshot (any side; non-{@code side} entries are ignored)
   * @param spot underlying spot
   * @param basis future − spot (forward = spot + basis)
   * @param side CE on a long/bull bias, PE on a short/bear bias (premium-buying only)
   * @param now the bar instant (deterministic; never wall-clock)
   * @param expiry option expiry date
   * @return the chosen strike, or empty if none meets both bands (or the option is past 15:30 cutoff)
   */
  public static Optional<Pick> pick(
      List<Candidate> chain,
      BigDecimal spot,
      BigDecimal basis,
      Black76.OptionType side,
      Instant now,
      LocalDate expiry,
      Params params) {
    double t = yearsToExpiry(now, expiry);
    if (t <= 0) {
      return Optional.empty(); // past the 15:30 IST cutoff — no live option to trade
    }
    double forward = spot.add(basis).doubleValue();
    Pick best = null;
    double bestErr = Double.MAX_VALUE;
    for (Candidate c : chain) {
      if (c.type() != side || c.ltp() == null || c.iv() == null || c.iv().signum() <= 0) {
        continue;
      }
      if (c.ltp().compareTo(params.premiumLo()) < 0 || c.ltp().compareTo(params.premiumHi()) > 0) {
        continue; // premium out of band
      }
      Black76.Greeks g =
          Black76.greeks(side, forward, c.strike().doubleValue(), t, params.rate(), c.iv().doubleValue());
      double absDelta = Math.abs(g.delta().doubleValue());
      if (absDelta < params.deltaLo() || absDelta > params.deltaHi()) {
        continue; // delta out of band
      }
      double err = Math.abs(absDelta - params.deltaTarget());
      if (err < bestErr) {
        bestErr = err;
        best = new Pick(c, BigDecimal.valueOf(absDelta));
      }
    }
    return Optional.ofNullable(best);
  }

  private static double yearsToExpiry(Instant now, LocalDate expiry) {
    long secondsLeft =
        Duration.between(now, expiry.atTime(EXPIRY_CUTOFF).atOffset(IST).toInstant()).toSeconds();
    return secondsLeft <= 0 ? 0.0 : secondsLeft / SECONDS_PER_YEAR;
  }
}
