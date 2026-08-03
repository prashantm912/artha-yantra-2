package in.arthayantra.marketdata.bhavcopy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Parses NSE/BSE dividend subjects without guessing percentage-of-face-value cash amounts. */
class DividendSubjectParserTest {

  @Test
  void recognisesDividendSubjectsAndExtractsTheSingleStatedRupeeAmount() {
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

  /**
   * Every subject below is verbatim live {@code marketdata.dividends} text (queried 2026-08-03).
   * INDOBORAX 2026-07-21 is the reproducer: it stored 10.00 for a Rs 10 + Rs 30 special.
   */
  @Test
  void sumsAmountsWhenTheSubjectIsGenuinelyCompound() {
    List<Case> cases =
        List.of(
            // INDOBORAX 2026-07-21 — the reported defect.
            new Case("Dividend - Rs 10 Per Share/Special Dividend - Rs 30 Per Share", true, "40"),
            // 3MINDIA 2025-07-25 — "&" separator.
            new Case("Dividend - Rs 160 Per Share & Special Dividend - Rs 375", true, "535"),
            // UTIAMC 2025-07-24 — "And" separator.
            new Case(
                "Dividend - Rs 26 Per Share And Special Dividend Rs 22 Per Share", true, "48"),
            // GATEWAY 2026-02-12 — Re/Rs mixed, both fractional.
            new Case(
                "Interim Dividend - Re 0.75 Per Share & Special Dividend Rs 1.25 Per Share",
                true,
                "2.00"),
            // PFIZER 2025-07-09 — three payouts, not two.
            new Case(
                "Dividend - Rs 35 Per Share & Special Dividend Of Rs 100 Per Share/ Special"
                    + " Dividend Of Rs 30",
                true,
                "165"));

    for (Case c : cases) {
      assertThat(DividendSubjectParser.parseAmount(c.subject()).orElseThrow())
          .as(c.subject())
          .isEqualByComparingTo(c.amount());
    }
  }

  /**
   * The InvIT/REIT shape: a stated total followed by its own itemisation. Summing these would
   * double-count — 68 of the 102 multi-amount live rows are this shape, against 34 genuinely
   * compound ones.
   */
  @Test
  void keepsTheLeadingTotalWhenTheRestOfTheSubjectItemisesIt() {
    List<Case> cases =
        List.of(
            // BIRET 2025-11-07 — five amounts; only Re 0.83 of it is the dividend leg.
            new Case(
                "Distribution - Rs 5.25 Per Unit Consisting Of Interest - Rs 1.85 Per Unit/"
                    + " Repayment Of Shareholder Loan - Rs 2.53 Per Unit/ Dividend - Re 0.83 Per"
                    + " Unit/ Interest On Fixed Deposit - Re 0.04 Per Unit",
                true,
                "5.25"),
            // EMBASSY 2025-08-05 — "Comprises Of" rather than "Consisting Of".
            new Case(
                "Distribution - Rs 5.80 Per Unit Comprises Of Re 0.18 Per Unit As Interest/ Rs"
                    + " 2.01 Per Unit As Dividend & Rs 3.61 Per Unit As Repayment Of Spv Level"
                    + " Debt",
                true,
                "5.80"),
            // VERTIS 2025-08-19 — feed runs the marker into the amount ("Consistingrs 1.1113"),
            // which is why the breakdown marker is matched as a substring, not on \b.
            new Case(
                "Distribution - Rs 2.3711 Per Unit Consistingrs 1.1113 Per Unit - Interest/ Rs"
                    + " 1.1182 Per Unit - Return Of Capital/Dividend - Re 0.1374 Per Unit/ Re"
                    + " 0.0042 Per Unit - Other Income",
                true,
                "2.3711"),
            // SHREMINVIT 2026-03-27 — the itemisation restates the total verbatim.
            new Case(
                "Distribution - Rs 3.6078 Per Unit Consists Of Rs 3.6078 Per Unit As Dividend",
                true,
                "3.6078"));

    for (Case c : cases) {
      assertThat(DividendSubjectParser.parseAmount(c.subject()).orElseThrow())
          .as(c.subject())
          .isEqualByComparingTo(c.amount());
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
