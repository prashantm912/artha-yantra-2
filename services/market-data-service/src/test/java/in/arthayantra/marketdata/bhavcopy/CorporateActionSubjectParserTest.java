package in.arthayantra.marketdata.bhavcopy;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.bhavcopy.CorporateActionSubjectParser.Parsed;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Parses real NSE corporate-action {@code subject} text into split/bonus ratios. */
class CorporateActionSubjectParserTest {

  @Test
  void bonusRatioIsBOverAPlusB() {
    // a:b = a free shares per b held → price multiplies by b/(a+b)
    assertThat(CorporateActionSubjectParser.parse("Bonus 1:1")).get().extracting(Parsed::kind, Parsed::ratio)
        .containsExactly("BONUS", new java.math.BigDecimal("0.50000000"));
    assertThat(CorporateActionSubjectParser.parse("Bonus 1:3").orElseThrow().ratio())
        .isEqualByComparingTo("0.75");
    assertThat(CorporateActionSubjectParser.parse("Bonus 5:1").orElseThrow().ratio())
        .isEqualByComparingTo("0.16666667");
  }

  @Test
  void faceValueSplitRatioIsNewOverOld() {
    Parsed tenToOne =
        CorporateActionSubjectParser.parse(
                "Face Value Split (Sub-Division) - From Rs 10/- Per Share To Re 1/- Per Share")
            .orElseThrow();
    assertThat(tenToOne.kind()).isEqualTo("SPLIT");
    assertThat(tenToOne.ratio()).isEqualByComparingTo("0.1");

    assertThat(
            CorporateActionSubjectParser.parse("Face Value Split From Rs 10/- To Rs 2/-")
                .orElseThrow()
                .ratio())
        .isEqualByComparingTo("0.2");
  }

  @Test
  void handlesBsePhrasing() {
    // BSE: "Bonus issue a:b" (same orientation as NSE) and "Stock  Split From Rs.X/- to Rs.Y/-"
    assertThat(CorporateActionSubjectParser.parse("Bonus issue 10:1").orElseThrow().ratio())
        .isEqualByComparingTo("0.09090909"); // 1/11
    assertThat(
            CorporateActionSubjectParser.parse("Stock  Split From Rs.10/- to Rs.2/-")
                .orElseThrow()
                .ratio())
        .isEqualByComparingTo("0.2");
    assertThat(CorporateActionSubjectParser.parse("Consolidation of Shares")).isEmpty();
  }

  @Test
  void dividendsAndOtherActionsYieldEmpty() {
    assertThat(CorporateActionSubjectParser.parse("Dividend - Rs 2.50 Per Share")).isEmpty();
    assertThat(CorporateActionSubjectParser.parse("Annual General Meeting")).isEmpty();
    assertThat(CorporateActionSubjectParser.parse("Buy Back of Shares")).isEmpty();
    assertThat(CorporateActionSubjectParser.parse(null)).isEmpty();
    assertThat(CorporateActionSubjectParser.parse("   ")).isEmpty();
  }

  @Test
  void splitWithEqualFaceValuesIsNotAnAdjustment() {
    assertThat(CorporateActionSubjectParser.parse("Face Value Split From Rs 10/- To Rs 10/-"))
        .isEqualTo(Optional.empty());
  }
}
