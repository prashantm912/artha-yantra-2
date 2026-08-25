package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76;
import in.arthayantra.strategysignal.scalper.StrikePicker.Candidate;
import in.arthayantra.strategysignal.scalper.StrikePicker.Params;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * MEASUREMENT ONLY (ledger H34) — the near-miss recorded when {@link StrikePicker#pick} returns
 * empty, i.e. no chain strike satisfied the delta band AND the premium band <b>together</b>.
 *
 * <p><b>Why this exists.</b> A {@code strike-pick} block persists {@code reason = "no strike met the
 * delta/premium band"} with {@code operand}/{@code threshold}/{@code margin} all NULL, so the
 * durable record cannot say WHICH band failed, by how much, or what the nearest strike was — and
 * without that number no relaxation decision can be made. This computes exactly that: among the
 * side-matching candidates the picker considered, the one closest to satisfying both bands, its
 * signed distance outside each band, and <b>the bands themselves</b>. The bands ride along
 * deliberately: they are tag-selected at config load ({@code delta-s24-floor} /
 * {@code premium-s24-band}), and H34's own first write-up did the arithmetic against the wrong delta
 * floor because the row did not carry it. A self-describing row cannot be misread that way.
 *
 * <p><b>It changes no gate outcome.</b> Nothing here feeds a decision — the block has already
 * happened when this runs, and the result reaches only the rejection diagnostic. Computed on the
 * empty-pick path only, which is rare, so the extra Black-76 evaluations cost nothing.
 *
 * <p><b>The ENTRY path PERSISTS what this returns; the E9 D4 exit ORACLE re-runs the same evaluation
 * on every held bar and DISCARDS it.</b> So "72 in the 45 days to 2026-08-25" is a count of
 * PERSISTED ROWS, not of invocations — {@code strategy.signal_rejections} is entry-path only, and
 * the call site is not guarded by {@code enforceOptionSide}. The cost is negligible either way; the
 * number is qualified because an unqualified one in a comment is how a wrong premise gets inherited.
 *
 * <p><b>Pure + deterministic</b>, like {@link StrikePicker} itself: {@code now} is passed in, and the
 * year-fraction is taken from {@link StrikePicker#yearsToExpiry} rather than re-derived, so the
 * reported {@code |delta|} is the SAME number the picker judged. A diagnostic that recomputed the
 * clock its own way could contradict the decision it is explaining.
 */
public final class StrikeNearMiss {

  private StrikeNearMiss() {}

  /** Which half of the conjunction the nearest candidate failed. */
  public enum Band {
    /** Premium in band, {@code |delta|} outside it. */
    DELTA,
    /** {@code |delta|} in band, premium outside it. */
    PREMIUM,
    /** Neither band held. */
    BOTH,
    /**
     * Both bands held — structurally impossible, since the picker would then have taken this strike.
     * Recorded rather than hidden: seeing it means the near-miss and the picker disagree, which is a
     * defect tell, and a diagnostic that cannot express "unexpected" reports a tidy falsehood instead.
     */
    NONE
  }

  /**
   * The near-miss. {@code sideCandidates} is the population the picker actually judged (side matches,
   * {@code ltp} and a positive {@code iv} present) — 0 distinguishes an empty/unusable chain from a
   * chain that was present and simply out of band, which are different problems with the same rail.
   * The nearest-candidate fields are null when {@code sideCandidates == 0} or when
   * {@code pastExpiryCutoff}.
   *
   * @param sideCandidates side-matching candidates with usable {@code ltp} + {@code iv}
   * @param pastExpiryCutoff the bar is at/after 15:30 IST on expiry day, so the picker returned empty
   *     WITHOUT judging any strike — a categorically different block from an empty band intersection,
   *     and conflating the two would overstate how often the bands are the problem
   * @param failedBand which band the nearest candidate failed
   * @param deltaGap signed distance of {@code |delta|} outside its band (negative = below the floor,
   *     positive = above the ceiling, zero = in band) — the sign says which way a relaxation must go
   * @param premiumGap signed distance of the premium outside its band, same convention
   */
  public record NearMiss(
      int sideCandidates,
      boolean pastExpiryCutoff,
      String exchange,
      String tradingsymbol,
      BigDecimal strike,
      BigDecimal premium,
      BigDecimal delta,
      Band failedBand,
      BigDecimal deltaGap,
      BigDecimal premiumGap,
      BigDecimal deltaLo,
      BigDecimal deltaHi,
      BigDecimal premiumLo,
      BigDecimal premiumHi) {}

  /**
   * The closest-to-satisfiable candidate, or a candidate-less record when there was none to judge.
   * Call ONLY when {@link StrikePicker#pick} returned empty for these same arguments.
   *
   * <p>"Closest" has TWO terms. Each gap is divided by its OWN band's WIDTH, because delta gaps are
   * O(0.1) and premium gaps O(100) — an unnormalized sum would rank on the premium axis alone and
   * always report a premium near-miss. Then a both-band miss carries a ONE-BAND-WIDTH PENALTY, so a
   * candidate needing only ONE band relaxed outranks one needing two even where the raw arithmetic
   * disagrees.
   *
   * <p><b>Without that penalty the row systematically over-reports {@code BOTH}.</b> On the live
   * 0.1/150 bands a candidate at (deltaGap −0.005, premiumGap −5) scores 0.083 and beats one at
   * (0, −20) scoring 0.133 — yet relaxing the premium floor alone by 20 would have admitted the
   * second. Only ONE candidate reaches the row, so the reader can never see the cheaper option, and
   * the aggregate this class exists to produce would argue for relaxing BOTH bands where one
   * suffices. That corrupts the exact number the item was built to measure.
   *
   * <p>The penalty is a SOFT preference, not a lexicographic rule: a both-band miss still wins when
   * it is more than a full band-width closer. That is deliberate — "closest to satisfiable" should
   * not be beaten by a single-band miss ten band-widths away — but it does mean {@code failedBand}
   * is a RANKING outcome, not a claim that no other candidate missed a different band.
   *
   * <p>Ties keep the first candidate in chain order, mirroring {@link StrikePicker#pick}'s own
   * {@code err < bestErr} convention, so the two agree on ordering.
   */
  public static NearMiss of(
      List<Candidate> chain,
      BigDecimal spot,
      BigDecimal basis,
      Black76.OptionType side,
      Instant now,
      LocalDate expiry,
      Params params) {
    BigDecimal deltaLo = BigDecimal.valueOf(params.deltaLo());
    BigDecimal deltaHi = BigDecimal.valueOf(params.deltaHi());
    double t = StrikePicker.yearsToExpiry(now, expiry);
    // Mirrors the picker's own candidate filter exactly — a different population would report a
    // near-miss on a strike the picker never looked at.
    List<Candidate> usable =
        chain.stream()
            .filter(c -> c.type() == side && c.ltp() != null && c.iv() != null && c.iv().signum() > 0)
            .toList();
    if (t <= 0 || usable.isEmpty()) {
      return new NearMiss(
          usable.size(), t <= 0, null, null, null, null, null, null, null, null,
          deltaLo, deltaHi, params.premiumLo(), params.premiumHi());
    }
    double forward = spot.add(basis).doubleValue();
    double deltaWidth = params.deltaHi() - params.deltaLo();
    double premiumWidth = params.premiumHi().subtract(params.premiumLo()).doubleValue();
    Candidate best = null;
    double bestScore = Double.MAX_VALUE;
    double bestDelta = 0;
    double bestDeltaGap = 0;
    BigDecimal bestPremiumGap = BigDecimal.ZERO;
    for (Candidate c : usable) {
      Black76.Greeks g =
          Black76.greeks(
              side, forward, c.strike().doubleValue(), t, params.rate(), c.iv().doubleValue());
      double absDelta = Math.abs(g.delta().doubleValue());
      double deltaGap = gap(absDelta, params.deltaLo(), params.deltaHi());
      // Premium is compared in BigDecimal, byte-for-byte the picker's own `compareTo` test — a
      // doubleValue() round-trip could flip a candidate sitting exactly on a band edge.
      BigDecimal premiumGap = gap(c.ltp(), params.premiumLo(), params.premiumHi());
      // The leading +1.0 is the both-band penalty (one full band width) — see the ranking note on
      // of(). Without it a both-band miss can outrank a single-band miss that is cheaper to relax.
      double score =
          (deltaGap != 0 && premiumGap.signum() != 0 ? 1.0 : 0.0)
              + Math.abs(deltaGap) / deltaWidth
              + premiumGap.abs().doubleValue() / premiumWidth;
      // `best == null` guards a chain on which every score is non-finite (a zero-width band makes
      // every ratio Infinity, and Infinity < Infinity is false), where the dereference below would
      // NPE inside the LIVE gate. Unreachable with today's hardcoded bands; the guard is free.
      if (best == null || score < bestScore) {
        bestScore = score;
        best = c;
        bestDelta = absDelta;
        bestDeltaGap = deltaGap;
        bestPremiumGap = premiumGap;
      }
    }
    return new NearMiss(
        usable.size(),
        false,
        best.exchange(),
        best.tradingsymbol(),
        best.strike(),
        best.ltp(),
        BigDecimal.valueOf(bestDelta),
        band(bestDeltaGap, bestPremiumGap),
        BigDecimal.valueOf(bestDeltaGap),
        bestPremiumGap,
        deltaLo,
        deltaHi,
        params.premiumLo(),
        params.premiumHi());
  }

  private static Band band(double deltaGap, BigDecimal premiumGap) {
    boolean deltaOut = deltaGap != 0;
    boolean premiumOut = premiumGap.signum() != 0;
    if (deltaOut && premiumOut) {
      return Band.BOTH;
    }
    if (deltaOut) {
      return Band.DELTA;
    }
    return premiumOut ? Band.PREMIUM : Band.NONE;
  }

  /** Signed distance outside {@code [lo, hi]}: negative below, positive above, zero inside. */
  private static double gap(double v, double lo, double hi) {
    if (v < lo) {
      return v - lo;
    }
    return v > hi ? v - hi : 0.0;
  }

  /** Signed distance outside {@code [lo, hi]} — the picker's exact {@code compareTo} comparison. */
  private static BigDecimal gap(BigDecimal v, BigDecimal lo, BigDecimal hi) {
    if (v.compareTo(lo) < 0) {
      return v.subtract(lo);
    }
    return v.compareTo(hi) > 0 ? v.subtract(hi) : BigDecimal.ZERO;
  }
}
