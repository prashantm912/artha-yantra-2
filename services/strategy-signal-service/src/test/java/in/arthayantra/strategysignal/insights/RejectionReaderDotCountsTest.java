package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.insights.RejectionReader.DotCounts;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The INT §4.2 Stage-1 dot ratio counts the SCOREABLE dot population, and never blends two
 * definitions of it into one series.
 *
 * <p><b>The defect.</b> {@code countSupports} counted every element of the {@code dots[]} array into
 * {@code total}, including dots the scorer had WITHHELD. {@code ConnectTheDotsScorer.score}
 * (:252-260) drops an {@code absent} dot from BOTH its numerator and its denominator precisely so a
 * data gap is never scored as evidence against the side — so on a session where {@code iv_rank} has
 * no data the reader reported 17/18 where the scorer's population was 17. Exactly one dot is
 * absent-capable today ({@code iv_rank}, {@code ConnectTheDotsScorer:200-202} is the only 6-arg
 * {@code add} call site) and it is honest-NULL on every live row, so essentially every row was off
 * by that one dot — a systematic depression, not an edge case.
 *
 * <p><b>The discontinuity this pins.</b> The fix is only possible because the flag is now serialized;
 * rows written before that carry NO {@code absent} key, so {@code path("absent").asBoolean(false)}
 * reads them as present and their ratio silently keeps the OLD meaning. {@code has("absent")} tells
 * the two apart, and legacy rows are kept OUT of the aggregate rather than averaged in with
 * corrected ones — otherwise a series spanning the deploy would step for no market reason, and the
 * step is not invertible after the fact (the mean runs over rows with differing dot counts, since
 * the optional dots are conditionally added).
 */
class RejectionReaderDotCountsTest {

  private static final ObjectMapper OM = new ObjectMapper();

  private static JsonNode dots(String json) {
    try {
      return OM.readTree(json);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  /** A dot as the serializers wrote it BEFORE the flag existed: no {@code absent} key at all. */
  private static String legacyDot(String name, boolean supports) {
    return "{\"dot\":\"" + name + "\",\"weight\":1.0,\"supports\":" + supports + "}";
  }

  /** A dot as all three serializers write it today: {@code absent} always present. */
  private static String modernDot(String name, boolean supports, boolean absent) {
    return "{\"dot\":\"" + name + "\",\"weight\":1.0,\"supports\":" + supports + ",\"absent\":" + absent + "}";
  }

  private static String array(String... elements) {
    return "[" + String.join(",", elements) + "]";
  }

  @Test
  void legacyRowCarriesNoFlagSoNothingIsWithheldAndItsCountIsTheOldNumber() {
    // 3 dots, 2 supporting, none marked (the key does not exist on this row's shape at all).
    DotCounts c =
        RejectionReader.countSupports(
            dots(array(legacyDot("vwap", true), legacyDot("rsi", true), legacyDot("iv_rank", false))));

    assertThat(c.total()).isEqualTo(3);
    assertThat(c.supporting()).isEqualTo(2);
    // Absentness is unknowable here — the row must NOT masquerade as corrected.
    assertThat(c.absentFlagged()).isFalse();
  }

  @Test
  void modernRowWithWithheldDotExcludesItFromBothCounts() {
    // The reported case: 18 dots, iv_rank withheld for want of data, 17 of the rest supporting.
    // Pre-fix this read 17/18; the scorer's own population was 17.
    String[] elements = new String[18];
    for (int i = 0; i < 17; i++) {
      elements[i] = modernDot("dot" + i, true, false);
    }
    elements[17] = modernDot("iv_rank", false, true);

    DotCounts c = RejectionReader.countSupports(dots(array(elements)));

    assertThat(c.total()).isEqualTo(17); // 18 pre-fix — the withheld dot is out of the denominator
    assertThat(c.supporting()).isEqualTo(17);
    assertThat(c.absentFlagged()).isTrue();
  }

  @Test
  void modernRowWithEveryDotPresentCountsThemAll() {
    DotCounts c =
        RejectionReader.countSupports(
            dots(
                array(
                    modernDot("vwap", true, false),
                    modernDot("rsi", false, false),
                    modernDot("iv_rank", false, false))));

    assertThat(c.total()).isEqualTo(3);
    assertThat(c.supporting()).isEqualTo(1);
    assertThat(c.absentFlagged()).isTrue();
  }

  @Test
  void withheldDotIsSkippedEvenIfItSomehowClaimsSupport() {
    // Defensive: absent is checked BEFORE supports, so the withholding rule is the rule and not a
    // side effect of `iv_rank` happening to be built `!ivRankAbsent && ...`. Unreachable today.
    DotCounts c =
        RejectionReader.countSupports(
            dots(array(modernDot("vwap", true, false), modernDot("ghost", true, true))));

    assertThat(c.total()).isEqualTo(1);
    assertThat(c.supporting()).isEqualTo(1);
  }

  @Test
  void nonArrayOrEmptyDotsNodeCountsNothingAndClaimsNoFlag() {
    assertThat(RejectionReader.countSupports(null)).isEqualTo(DotCounts.EMPTY);
    assertThat(RejectionReader.countSupports(OM.createObjectNode())).isEqualTo(DotCounts.EMPTY);
    assertThat(RejectionReader.countSupports(dots("[]"))).isEqualTo(DotCounts.EMPTY);
  }

  @Test
  void theAggregateIsNullWhenEveryRowPredatesTheFlag() {
    // A wholly historical day: every ratio is on the superseded definition and cannot be corrected,
    // so the surface reports "not computable" rather than a silently different number.
    List<DotCounts> legacyOnly = List.of(new DotCounts(12, 18, false), new DotCounts(9, 18, false));

    assertThat(RejectionReader.meanSupportRatio(legacyOnly)).isNull();
  }

  @Test
  void theAggregateAveragesOnlyFlagBearingRowsOnTheDeployBoundaryDay() {
    // The one day that genuinely mixes shapes: rows before the restart carry no flag, rows after do.
    // Only the corrected rows are averaged — mean(0.5, 1.0) = 0.75 — so the series stays one
    // definition even on the boundary day. Blending would have averaged four rows instead.
    List<DotCounts> mixed =
        List.of(
            new DotCounts(12, 18, false), // legacy: excluded
            new DotCounts(1, 2, true), // 0.5
            new DotCounts(2, 2, true), // 1.0
            new DotCounts(3, 18, false)); // legacy: excluded

    assertThat(RejectionReader.meanSupportRatio(mixed)).isEqualByComparingTo(new BigDecimal("0.7500"));
  }

  @Test
  void flagBearingRowWithNoScoreableDotsIsSkippedRatherThanDividedByZero() {
    // Degenerate: every dot withheld. total == 0, so the row contributes nothing (the pre-existing
    // guard) — and a day made only of those reports null, not 0.
    assertThat(RejectionReader.meanSupportRatio(List.of(new DotCounts(0, 0, true)))).isNull();
    assertThat(RejectionReader.meanSupportRatio(List.of(new DotCounts(0, 0, true), new DotCounts(1, 2, true))))
        .isEqualByComparingTo(new BigDecimal("0.5000"));
  }
}
