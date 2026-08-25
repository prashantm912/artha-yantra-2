package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import in.arthayantra.strategysignal.scalper.StrikeNearMiss.Band;
import in.arthayantra.strategysignal.scalper.StrikeNearMiss.NearMiss;
import in.arthayantra.strategysignal.scalper.StrikePicker.Candidate;
import in.arthayantra.strategysignal.scalper.StrikePicker.Params;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * H34: when the delta band and the premium band have no common strike, WHICH band failed and BY HOW
 * MUCH. Every case here is one the picker returns empty on — the near-miss is defined only there.
 */
class StrikeNearMissTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 25);
  private static final Instant NOW = LocalDate.of(2026, 6, 20).atTime(10, 0).atOffset(IST).toInstant();
  private static final BigDecimal SPOT = bd("20000");
  // the live SENSEX-family shape: delta 0.6-0.7 (0.1 wide), premium 100-400 (300 wide)
  private static final Params P = new Params(0.6, 0.7, bd("100"), bd("400"), 0.065);

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static Candidate ce(String strike, String ltp) {
    return new Candidate("NFO", "NIFTY" + strike + "CE", bd(strike), CE, bd(ltp), bd("0.14"));
  }

  private static NearMiss nearMiss(List<Candidate> chain) {
    // Every fixture here must be one the picker actually refuses, or the near-miss describes a
    // situation that never reaches it.
    assertThat(StrikePicker.pick(chain, SPOT, bd("0"), CE, NOW, EXPIRY, P)).isEmpty();
    return StrikeNearMiss.of(chain, SPOT, bd("0"), CE, NOW, EXPIRY, P);
  }

  @Test
  void namesThePremiumBandAndTheExactShortfallWhenDeltaWasFine() {
    // 19850 CE is delta-in-band (~0.68) but priced 80 — 20 below the 100 floor. Nothing else needed
    // relaxing, and the row must say so rather than "no strike met the delta/premium band".
    NearMiss nm = nearMiss(List.of(ce("19850", "80")));

    assertThat(nm.failedBand()).isEqualTo(Band.PREMIUM);
    assertThat(nm.tradingsymbol()).isEqualTo("NIFTY19850CE");
    assertThat(nm.premiumGap()).isEqualByComparingTo("-20");
    assertThat(nm.deltaGap()).isEqualByComparingTo("0");
    assertThat(nm.delta().doubleValue()).isBetween(0.6, 0.7);
    assertThat(nm.sideCandidates()).isEqualTo(1);
    assertThat(nm.pastExpiryCutoff()).isFalse();
  }

  @Test
  void namesTheDeltaBandWhenThePremiumWasFine() {
    // 20500 CE is priced 200 (inside 100-400) but delta ~0.071, far below the 0.6 floor.
    List<Candidate> chain = List.of(ce("20500", "200"));
    NearMiss nm = nearMiss(chain);

    assertThat(nm.failedBand()).isEqualTo(Band.DELTA);
    assertThat(nm.premiumGap()).isEqualByComparingTo("0");
    // negative = below the floor, i.e. the relaxation would have to LOWER deltaLo, and by this much
    assertThat(nm.deltaGap().doubleValue()).isNegative();
    assertThat(nm.deltaGap().doubleValue())
        .isCloseTo(nm.delta().doubleValue() - 0.6, within(1e-12));
    // The picker and the near-miss must report the SAME |delta| for the SAME candidate. They are two
    // separate computations over the same inputs (identical forward, the shared yearsToExpiry clock,
    // the same Black-76 call), so nothing but this pins them together — widen ONLY the delta band so
    // the picker takes this very strike, then compare its own reported delta. Reddens on any future
    // drift in pick()'s forward, clock or |delta| convention, which is the one real divergence risk.
    assertThat(
            StrikePicker.pick(
                    chain, SPOT, bd("0"), CE, NOW, EXPIRY,
                    new Params(0.0, 1.0, bd("100"), bd("400"), 0.065))
                .orElseThrow()
                .delta())
        .isEqualByComparingTo(nm.delta());
  }

  @Test
  void namesBothWhenNeitherBandHeld() {
    // deep OTM and cheap: delta below 0.6 AND premium below 100.
    NearMiss nm = nearMiss(List.of(ce("20800", "40")));

    assertThat(nm.failedBand()).isEqualTo(Band.BOTH);
    assertThat(nm.premiumGap()).isEqualByComparingTo("-60");
    assertThat(nm.deltaGap().doubleValue()).isNegative();
  }

  @Test
  void ranksByBandWIDTHSoAPremiumMissBeatsAFarLargerLookingDeltaMiss() {
    // THE reason the ranking is normalized. Raw gaps are in different units: 18000 CE misses the
    // delta band by ~0.3 while 19850 CE misses the premium band by 20 POINTS, so a raw |gap| sum
    // would name 18000 — a deep-ITM strike nothing about the premium band could ever admit — and the
    // owner would read it as "the delta band is the near one". Divided by each band's own width the
    // comparison is 3.0 widths vs 0.067 widths, and 19850 wins as it should: it is 20 points of
    // premium relaxation away from being tradeable, which is the actionable number.
    List<Candidate> chain = List.of(ce("18000", "250"), ce("19850", "80"));

    NearMiss nm = nearMiss(chain);

    assertThat(nm.tradingsymbol()).isEqualTo("NIFTY19850CE");
    assertThat(nm.failedBand()).isEqualTo(Band.PREMIUM);
    assertThat(nm.premiumGap()).isEqualByComparingTo("-20");
    assertThat(nm.sideCandidates()).isEqualTo(2);
    // ...and the rankings genuinely disagree: 18000's delta miss (~0.3 raw) is SMALLER in raw terms
    // than the winner's 20-point premium miss, so a raw |gap| sum would have named 18000 instead.
    NearMiss deepItmAlone = StrikeNearMiss.of(List.of(ce("18000", "250")), SPOT, bd("0"), CE, NOW, EXPIRY, P);
    assertThat(deepItmAlone.deltaGap().abs().doubleValue())
        .isLessThan(nm.premiumGap().abs().doubleValue());
  }

  @Test
  void prefersASingleBandMissOverABothBandMissThatIsArithmeticallyCloser() {
    // THE reason the ranking carries a both-band penalty, on the LIVE SENSEX bands (delta 0.7-0.8 via
    // delta-s24-floor, premium 300-800), both misses on the premium FLOOR side — the real H34 shape.
    //
    //   A  19800 @ 140 : delta 0.7278 IN band, premium 160 below the 300 floor
    //                    -> single band, score = 0 + 160/500                    = 0.320
    //   B  19850 @ 290 : delta 0.6758 (0.0242 below 0.7), premium 10 below 300
    //                    -> BOTH bands, score = 0.0242/0.1 + 10/500             = 0.262
    //
    // On the raw normalized sum B wins and the row reports BOTH — but only ONE candidate reaches the
    // row, so the reader never learns that relaxing the premium floor ALONE by 160 admits A. The
    // penalty puts B at 1.262 and A wins, so the row reports the actionable single-axis number.
    Params live = new Params(0.7, 0.8, bd("300"), bd("800"), 0.065);
    List<Candidate> chain = List.of(ce("19800", "140"), ce("19850", "290"));
    assertThat(StrikePicker.pick(chain, SPOT, bd("0"), CE, NOW, EXPIRY, live)).isEmpty();

    NearMiss nm = StrikeNearMiss.of(chain, SPOT, bd("0"), CE, NOW, EXPIRY, live);

    assertThat(nm.tradingsymbol()).isEqualTo("NIFTY19800CE");
    assertThat(nm.failedBand()).isEqualTo(Band.PREMIUM);
    assertThat(nm.premiumGap()).isEqualByComparingTo("-160");
    assertThat(nm.deltaGap()).isEqualByComparingTo("0");
    // ...and B really is the arithmetically closer one, so the two rankings genuinely disagree here
    // (otherwise this test would pass without the penalty ever mattering).
    NearMiss bothBandAlone =
        StrikeNearMiss.of(List.of(ce("19850", "290")), SPOT, bd("0"), CE, NOW, EXPIRY, live);
    assertThat(bothBandAlone.failedBand()).isEqualTo(Band.BOTH);
    double bRaw =
        bothBandAlone.deltaGap().abs().doubleValue() / 0.1
            + bothBandAlone.premiumGap().abs().doubleValue() / 500.0;
    double aRaw = nm.premiumGap().abs().doubleValue() / 500.0;
    assertThat(bRaw).isLessThan(aRaw);
  }

  @Test
  void carriesTheBandsItJudgedAgainstSoTheRowIsSelfDescribing() {
    // The bands are TAG-selected at config load (delta-s24-floor / premium-s24-band), so a row read
    // later cannot infer which floor was armed at the time. H34's first write-up did exactly that
    // and got the delta floor wrong.
    NearMiss nm = nearMiss(List.of(ce("19850", "80")));

    assertThat(nm.deltaLo()).isEqualByComparingTo("0.6");
    assertThat(nm.deltaHi()).isEqualByComparingTo("0.7");
    assertThat(nm.premiumLo()).isEqualByComparingTo("100");
    assertThat(nm.premiumHi()).isEqualByComparingTo("400");
  }

  @Test
  void distinguishesThePastExpiryCutoffBlockFromAnEmptyBandIntersection() {
    // Past 15:30 IST on expiry day the picker returns empty WITHOUT judging a single strike. Reading
    // that as a band failure would overstate how often the bands are the problem — the exact
    // conflation the relaxation decision must not make.
    Instant afterClose = EXPIRY.atTime(16, 0).atOffset(IST).toInstant();
    List<Candidate> chain = List.of(ce("19850", "200"));
    assertThat(StrikePicker.pick(chain, SPOT, bd("0"), CE, afterClose, EXPIRY, P)).isEmpty();

    NearMiss nm = StrikeNearMiss.of(chain, SPOT, bd("0"), CE, afterClose, EXPIRY, P);

    assertThat(nm.pastExpiryCutoff()).isTrue();
    assertThat(nm.failedBand()).isNull();
    assertThat(nm.tradingsymbol()).isNull();
    assertThat(nm.sideCandidates()).isEqualTo(1);
  }

  @Test
  void reportsZeroCandidatesWhenTheChainHadNothingJudgeableOnThisSide() {
    // A chain with no usable CE leg is a DATA problem, not a band problem; the two share the rail.
    List<Candidate> chain =
        List.of(
            new Candidate("NFO", "NIFTY19850PE", bd("19850"), PE, bd("200"), bd("0.14")), // wrong side
            new Candidate("NFO", "NIFTY19900CE", bd("19900"), CE, null, bd("0.14")), // no ltp
            new Candidate("NFO", "NIFTY19950CE", bd("19950"), CE, bd("200"), bd("0"))); // no iv

    NearMiss nm = nearMiss(chain);

    assertThat(nm.sideCandidates()).isZero();
    assertThat(nm.failedBand()).isNull();
    assertThat(nm.strike()).isNull();
    assertThat(nm.pastExpiryCutoff()).isFalse();
  }
}
