package in.arthayantra.marketdata.bhavcopy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Parses NSE/BSE dividend subjects without guessing percentage-of-face-value cash amounts. */
class DividendSubjectParserTest {

  @Test
  void recognisesDividendSubjectsAndExtractsFirstRupeeAmount() {
    List<Case> cases =
        List.of(
            new Case("DIVIDEND - RS 5 PER SHARE", true, "5"),
            new Case("FINAL DIVIDEND RS 2.50 PER SHARE", true, "2.50"),
            new Case("INTERIM DIVIDEND - RE 1 PER SHARE", true, "1"),
            new Case("DIVIDEND RS.1.20", true, "1.20"),
            new Case("SPECIAL DIVIDEND RS 3/- PER SHARE", true, "3"),
            new Case("DIVIDEND 50%", true, null),
            new Case("DIVIDEND SUBJECT TO SHAREHOLDER APPROVAL", true, null),
            new Case("Bonus issue 1:1", false, null),
            new Case("Face Value Split From Rs 10/- To Rs 2/-", false, null),
            new Case("Buy Back of Shares", false, null),
            new Case("Annual General Meeting", false, null),
            new Case("Rights Issue", false, null));

    for (Case c : cases) {
      assertThat(DividendSubjectParser.isDividend(c.subject()))
          .as(c.subject())
          .isEqualTo(c.dividend());
      if (c.amount() == null) {
        assertThat(DividendSubjectParser.parseAmount(c.subject())).as(c.subject()).isEmpty();
      } else {
        assertThat(DividendSubjectParser.parseAmount(c.subject()).orElseThrow())
            .as(c.subject())
            .isEqualByComparingTo(c.amount());
      }
    }
  }

  @Test
  void nullAndBlankSubjectsAreNotDividends() {
    assertThat(DividendSubjectParser.isDividend(null)).isFalse();
    assertThat(DividendSubjectParser.isDividend("   ")).isFalse();
    assertThat(DividendSubjectParser.parseAmount(null)).isEmpty();
    assertThat(DividendSubjectParser.parseAmount("   ")).isEmpty();
  }

  private record Case(String subject, boolean dividend, String amount) {}
}
