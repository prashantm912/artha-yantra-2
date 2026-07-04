package in.arthayantra.marketdata.screener.minervini.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MV-5.2: the VCP detector. The canonical fixture reproduces the book's {@code 40W 31/3 4T}
 * footprint (Meridian/VIVO, Fig 10.6 — depths 31→17→8→3 over a 40-week base); the rejection fixtures
 * are a V-shape (one contraction) and an expanding, non-narrowing base.
 */
class VcpDetectorTest {

  // Default thresholds (mirror the @Value defaults): 2.5% zig-zag, 2–6 contractions, ratio 0.2–0.9,
  // final-vol-low 0.5, cheat-fraction 0.5, thrust +100% in ≤40 sessions.
  private final VcpDetector detector = new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40);

  @Test
  void reproducesCanonicalFootprint() {
    List<DailyBar> bars = canonicalVcp(true);

    VcpFootprint f = detector.detect(bars);

    assertThat(f.vcp()).isTrue();
    assertThat(f.footprint()).isEqualTo("40W 31/3 4T");
    assertThat(f.contractionCount()).isEqualTo(4);
    assertThat(f.deepestPct()).isCloseTo(31.0, within(0.05));
    assertThat(f.tightestPct()).isCloseTo(3.0, within(0.05));
    assertThat(f.baseWeeks()).isEqualTo(40);
    assertThat(f.pivot()).isEqualTo(93.0); // high of the final tightest contraction
    assertThat(f.volumeDryUp()).isTrue();
    assertThat(f.shakeout()).isFalse(); // troughs step up (69 < 80.5 < 87.4 < 90.2), no undercut
    // §6.3 cheat: halfway up the final contraction T4(90.21)→pivot(93) = 91.605 — earlier & below pivot.
    assertThat(f.cheatPivot()).isCloseTo(91.605, within(0.01));
    assertThat(f.cheatPivot()).isLessThan(f.pivot()); // the cheat is always an earlier/lower trigger
    // The pre-base rally is only 70→100 (+43%), well under +100% → no power-play thrust.
    assertThat(f.thrust()).isFalse();
  }

  @Test
  void thrustFlagSetWhenPriceDoublesBeforeTheBase() {
    // A blast-off 45→95 (+111%) over 30 sessions THEN a valid 2-contraction base — the power-play
    // precondition (§6.4). C1 95→76 (−20%), C2 92→87 (−5.4%, narrowing); pivot = 92.
    List<DailyBar> bars = new ArrayList<>();
    append(bars, 0, 45.0, 30, 95.0, 1_000_000); //   0..30  thrust rally into base high P1(95)
    append(bars, 30, 95.0, 25, 76.0, 1_000_000); //  30..55  C1 −20% → T1
    append(bars, 55, 76.0, 20, 92.0, 1_000_000); //  55..75  rally → P2
    append(bars, 75, 92.0, 15, 87.0, 1_000_000); //  75..90  C2 −5.4% (narrowing) → pivot 92

    VcpFootprint f = detector.detect(bars);

    assertThat(f.vcp()).isTrue();
    assertThat(f.pivot()).isEqualTo(92.0);
    assertThat(f.thrust()).isTrue(); // +111% in 30 sessions before the base
  }

  @Test
  void volumeDryUpIsFalseWhenFinalContractionVolumeIsNormal() {
    // Same base geometry, but the final contraction carries FULL (base-level) volume — no dry-up.
    // Pins the volume gate independently: the footprint is unchanged but the flag must flip to false.
    List<DailyBar> bars = canonicalVcp(false);

    VcpFootprint f = detector.detect(bars);

    assertThat(f.vcp()).isTrue();
    assertThat(f.footprint()).isEqualTo("40W 31/3 4T");
    assertThat(f.volumeDryUp()).isFalse();
  }

  @Test
  void rejectsSingleContractionVee() {
    // 100 → 60 (−40%) straight down, then straight recovery to 105 — a single contraction, no base.
    List<DailyBar> bars = new ArrayList<>();
    append(bars, 0, 100, 40, 60, 1_000_000); // down leg
    append(bars, 40, 60, 45, 105, 1_000_000); // up leg

    VcpFootprint f = detector.detect(bars);

    assertThat(f.vcp()).isFalse();
    assertThat(f.rejectReason()).contains("fewer than 2 contractions");
  }

  @Test
  void rejectsExpandingBase() {
    // Contractions widen left→right (8% → 17% → 31%) — the opposite of a VCP; no narrowing tail.
    List<DailyBar> bars = new ArrayList<>();
    int idx = 0;
    append(bars, idx, 90, 5, 100, 1_000_000);
    idx += 5;
    append(bars, idx, 100, 20, 92.0, 1_000_000); // C1 8%
    idx += 20;
    append(bars, idx, 92.0, 20, 100, 1_000_000);
    idx += 20;
    append(bars, idx, 100, 20, 83.0, 1_000_000); // C2 17%
    idx += 20;
    append(bars, idx, 83.0, 20, 100, 1_000_000);
    idx += 20;
    append(bars, idx, 100, 20, 69.0, 1_000_000); // C3 31%

    VcpFootprint f = detector.detect(bars);

    assertThat(f.vcp()).isFalse();
    assertThat(f.rejectReason()).contains("not contracting");
  }

  /**
   * The canonical 40W 31/3 4T base: P1(100)→T1(69)=31%, P2(97)→T2(80.51)=17%, P3(95)→T3(87.4)=8%,
   * P4(93)→T4(90.21)=3%. P1 at bar 5, pivot P4 at bar 205 → 200 sessions → 40 weeks. Volume dries up
   * in the final contraction (one 80k day well below the ~757k 50-day average).
   */
  private static List<DailyBar> canonicalVcp(boolean dryUp) {
    List<DailyBar> bars = new ArrayList<>();
    // idx, from, span, to (linear, one bar/session, high=low=close). Volume 1M until the final leg.
    append(bars, 0, 70.0, 5, 100.0, 1_000_000); //   0..5   rally into base high P1
    append(bars, 5, 100.0, 33, 69.0, 1_000_000); //   5..38  C1 −31%  → T1
    append(bars, 38, 69.0, 33, 97.0, 1_000_000); //  38..71  rally → P2
    append(bars, 71, 97.0, 33, 80.51, 1_000_000); // 71..104 C2 −17% → T2
    append(bars, 104, 80.51, 33, 95.0, 1_000_000); // 104..137 rally → P3
    append(bars, 137, 95.0, 33, 87.40, 1_000_000); // 137..170 C3 −8% → T3
    append(bars, 170, 87.40, 35, 93.0, 1_000_000); // 170..205 rally → P4 (pivot, bar 205)
    // Final contraction P4→T4 −3%. dryUp=true → 250k with one 80k day (below the ~1M 50d avg);
    // dryUp=false → full 1M volume through the pullback (no dry-up).
    appendFinalContraction(bars, 205, 93.0, 10, 90.21, dryUp);
    return bars;
  }

  /** Linear price ramp from {@code from} to {@code to} over {@code span} bars starting at {@code startIdx}. */
  private static void append(
      List<DailyBar> bars, int startIdx, double from, int span, double to, long volume) {
    for (int k = (bars.isEmpty() ? 0 : 1); k <= span; k++) {
      double p = from + (to - from) * ((double) k / span);
      bars.add(bar(startIdx + k, p, volume));
    }
  }

  /**
   * The final contraction ramp. dryUp=true → ~250k volume with a single 80k day mid-leg (dries up);
   * dryUp=false → full 1M volume (no dry-up).
   */
  private static void appendFinalContraction(
      List<DailyBar> bars, int startIdx, double from, int span, double to, boolean dryUp) {
    for (int k = 1; k <= span; k++) {
      double p = from + (to - from) * ((double) k / span);
      long vol = dryUp ? ((k == span / 2) ? 80_000 : 250_000) : 1_000_000;
      bars.add(bar(startIdx + k, p, vol));
    }
  }

  private static DailyBar bar(int i, double price, long volume) {
    return new DailyBar(LocalDate.of(2025, 1, 1).plusDays(i), price, price, price, price, volume);
  }
}
