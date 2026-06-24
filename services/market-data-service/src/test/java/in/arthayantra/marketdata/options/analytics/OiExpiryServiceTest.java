package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OiExpiryServiceTest {

  private static OptionsSnapshotReader.OptionEodRow row(
      String strike,
      String type,
      LocalDate d,
      String open,
      String high,
      String low,
      String close,
      Long oi,
      Long volume) {
    return new OptionsSnapshotReader.OptionEodRow(
        new BigDecimal(strike),
        type,
        d,
        new BigDecimal(open),
        new BigDecimal(high),
        new BigDecimal(low),
        new BigDecimal(close),
        oi,
        volume);
  }

  @Test
  void emptyRowsYieldEmptyList() {
    assertThat(new OiExpiryService().fold(List.of())).isEmpty();
  }

  @Test
  void groupsByStrikeAndSplitsCePeNewestFirst() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    LocalDate d2 = LocalDate.of(2026, 6, 17);
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22500", "CE", d1, "100", "120", "90", "110", 1000L, 5000L),
            row("22500", "CE", d2, "110", "130", "105", "125", 1200L, 6000L),
            row("22500", "PE", d1, "80", "95", "70", "85", 1500L, 4000L),
            row("22500", "PE", d2, "85", "100", "80", "90", 1400L, 4500L));

    List<OiExpiryService.StrikeExpiry> out = new OiExpiryService().fold(rows);

    assertThat(out).hasSize(1);
    OiExpiryService.StrikeExpiry s = out.get(0);
    assertThat(s.strike()).isEqualByComparingTo("22500");
    // newest-first: d2 then d1.
    assertThat(s.ce()).hasSize(2);
    assertThat(s.ce().get(0).date()).isEqualTo(d2);
    assertThat(s.ce().get(1).date()).isEqualTo(d1);
    assertThat(s.pe().get(0).date()).isEqualTo(d2);
  }

  @Test
  void derivesDayOverDayDeltasAndInterpretation() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    LocalDate d2 = LocalDate.of(2026, 6, 17);
    // CE close 100 -> 110 (+10%), oi 1000 -> 1200 (+20%): price up + OI up = LONG_BUILDUP.
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22500", "CE", d1, "100", "120", "90", "100", 1000L, 5000L),
            row("22500", "CE", d2, "100", "130", "95", "110", 1200L, 6000L));

    OiExpiryService.StrikeExpiry s = new OiExpiryService().fold(rows).get(0);

    OiExpiryService.EodDay newest = s.ce().get(0); // d2
    assertThat(newest.changeInClosePct()).isEqualByComparingTo("10.00");
    assertThat(newest.changeInOiPct()).isEqualByComparingTo("20.00");
    assertThat(newest.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);

    OiExpiryService.EodDay oldest = s.ce().get(1); // d1 — no prior session
    assertThat(oldest.changeInClosePct()).isNull();
    assertThat(oldest.changeInOiPct()).isNull();
    assertThat(oldest.interpretation()).isNull();
  }

  @Test
  void flagsAllDayHighAndAllDayLow() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    LocalDate d2 = LocalDate.of(2026, 6, 17);
    LocalDate d3 = LocalDate.of(2026, 6, 18);
    // window high (130) on d2, window low (60) on d3.
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22500", "CE", d1, "100", "120", "90", "110", 1000L, 5000L),
            row("22500", "CE", d2, "110", "130", "100", "125", 1100L, 5500L),
            row("22500", "CE", d3, "120", "122", "60", "70", 1200L, 6000L));

    OiExpiryService.StrikeExpiry s = new OiExpiryService().fold(rows).get(0);

    // newest-first order: d3, d2, d1.
    assertThat(s.ce().get(2).date()).isEqualTo(d1);
    assertThat(s.ce().get(2).allDayHigh()).isFalse();
    assertThat(s.ce().get(1).date()).isEqualTo(d2);
    assertThat(s.ce().get(1).allDayHigh()).isTrue();
    assertThat(s.ce().get(0).date()).isEqualTo(d3);
    assertThat(s.ce().get(0).allDayLow()).isTrue();
  }

  @Test
  void strikesReturnedInAscendingOrder() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22600", "CE", d1, "10", "12", "9", "11", 100L, 50L),
            row("22400", "CE", d1, "200", "210", "190", "205", 300L, 80L),
            row("22500", "CE", d1, "100", "110", "95", "108", 200L, 60L));

    List<OiExpiryService.StrikeExpiry> out = new OiExpiryService().fold(rows);

    assertThat(out).extracting(e -> e.strike().intValueExact())
        .containsExactly(22400, 22500, 22600);
  }
}
