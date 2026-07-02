package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenHighStrategyServiceTest {

  // Default server tolerance (matches the @Value default in OpenHighStatsService / strategy service).
  private static OpenHighStrategyService service() {
    return new OpenHighStrategyService(new BigDecimal("1.0"));
  }

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
    assertThat(service().fold(List.of())).isEmpty();
  }

  @Test
  void gradesCallOpenHighOnLatestSessionAndComputesPriorProbability() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    LocalDate d2 = LocalDate.of(2026, 6, 17);
    LocalDate d3 = LocalDate.of(2026, 6, 18);
    // CE prior sessions: d1 open==high (OH hit), d2 open!=high (miss) → probability 1/2 = 50%.
    // Latest d3 open==high → triggered.
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22500", "CE", d1, "120", "120", "90", "100", 1000L, 5000L),
            row("22500", "CE", d2, "100", "130", "95", "118", 1100L, 5500L),
            row("22500", "CE", d3, "125", "125", "100", "110", 1200L, 6000L));

    OpenHighStrategyService.StrikeOpenHigh s = service().fold(rows).get(0);
    OpenHighStrategyService.OpenHighLeg ce = s.ce();

    assertThat(ce).isNotNull();
    assertThat(ce.optionType()).isEqualTo("CE");
    assertThat(ce.latestDate()).isEqualTo(d3);
    assertThat(ce.sessions()).isEqualTo(3);
    assertThat(ce.ohMark()).isTrue();
    assertThat(ce.triggered()).isTrue();
    assertThat(ce.hits()).isEqualTo(1); // only d1 among the two prior sessions
    assertThat(ce.probability()).isEqualByComparingTo("50.00");
    // fall% from high = (125 - 110)/125 * 100 = 12.00
    assertThat(ce.fallPctFromHigh()).isEqualByComparingTo("12.00");
  }

  @Test
  void putLegGradesOpenLowNotOpenHigh() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    LocalDate d2 = LocalDate.of(2026, 6, 17);
    // PE: d1 open==low (OL hit, a prior session), d2 latest open==low → triggered, probability 1/1.
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22500", "PE", d1, "90", "120", "90", "110", 1500L, 4000L),
            row("22500", "PE", d2, "85", "130", "85", "100", 1400L, 4500L));

    OpenHighStrategyService.OpenHighLeg pe = service().fold(rows).get(0).pe();

    assertThat(pe).isNotNull();
    assertThat(pe.olMark()).isTrue();
    assertThat(pe.ohMark()).isFalse();
    assertThat(pe.triggered()).isTrue();
    assertThat(pe.probability()).isEqualByComparingTo("100.00");
  }

  @Test
  void singleSessionHasNullProbabilityAndStillGradesLatest() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(row("22500", "CE", d1, "120", "120", "90", "100", 1000L, 5000L));

    OpenHighStrategyService.OpenHighLeg ce = service().fold(rows).get(0).ce();

    assertThat(ce.sessions()).isEqualTo(1);
    assertThat(ce.probability()).isNull(); // no prior session to derive odds
    assertThat(ce.ohMark()).isTrue();
    assertThat(ce.triggered()).isTrue();
  }

  @Test
  void notTriggeredWhenOpenAwayFromHigh() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    LocalDate d2 = LocalDate.of(2026, 6, 17);
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22500", "CE", d1, "100", "120", "90", "110", 1000L, 5000L),
            row("22500", "CE", d2, "100", "130", "95", "118", 1100L, 5500L));

    OpenHighStrategyService.OpenHighLeg ce = service().fold(rows).get(0).ce();

    assertThat(ce.ohMark()).isFalse();
    assertThat(ce.triggered()).isFalse();
    assertThat(ce.hits()).isEqualTo(0);
    assertThat(ce.probability()).isEqualByComparingTo("0.00");
  }

  private static OptionsSnapshotReader.StrikePoint live(
      String bucketIst, String strike, String type, String ltp) {
    return new OptionsSnapshotReader.StrikePoint(
        java.time.OffsetDateTime.parse(bucketIst + "+05:30"),
        new BigDecimal(strike),
        type,
        ltp == null ? null : new BigDecimal(ltp),
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void foldLiveGradesThePriorSessionAndReportsTheLiveBreak() {
    // Audit §10.2-8: the pattern reads the OLD day; today's buckets fill New D.High/Low, live LTP,
    // and the Hit — first bucket whose premium broke the old day's high (CE).
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    LocalDate d2 = LocalDate.of(2026, 6, 17); // the live day
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22500", "CE", d1, "120", "120", "90", "100", 1000L, 5000L), // O=H pattern day
            row("22500", "CE", d2, "95", "130", "95", "128", 1200L, 6000L)); // today's partial rollup
    List<OptionsSnapshotReader.StrikePoint> today =
        List.of(
            live("2026-06-17T09:15:00", "22500", "CE", "95.00"),
            live("2026-06-17T09:20:00", "22500", "CE", "118.00"),
            live("2026-06-17T09:25:00", "22500", "CE", "126.00"), // > old high 120 → Hit @ 09:25
            live("2026-06-17T09:30:00", "22500", "CE", "121.00"));

    OpenHighStrategyService.OpenHighLeg ce =
        service().foldLive(rows, today, d2).get(0).ce();

    assertThat(ce).isNotNull();
    assertThat(ce.latestDate()).as("pattern anchors on the prior session").isEqualTo(d1);
    assertThat(ce.ohMark()).isTrue();
    assertThat(ce.newDayHigh()).isEqualByComparingTo("126.00");
    assertThat(ce.newDayLow()).isEqualByComparingTo("95.00");
    assertThat(ce.liveLtp()).isEqualByComparingTo("121.00");
    assertThat(ce.triggered()).isTrue();
    assertThat(ce.triggeredTime()).isEqualTo("09:25");
    // fall% now reads the LIVE day: (121 - 126)/126 * 100 = -3.97
    assertThat(ce.fallPctFromHigh()).isEqualByComparingTo("-3.97");
  }

  @Test
  void foldLiveWithoutABreakLeavesTheLegUntriggered() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    LocalDate d2 = LocalDate.of(2026, 6, 17);
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(row("22500", "PE", d1, "90", "120", "90", "110", 1500L, 4000L)); // O=L pattern
    List<OptionsSnapshotReader.StrikePoint> today =
        List.of(live("2026-06-17T09:15:00", "22500", "PE", "95.00")); // never under old low 90

    OpenHighStrategyService.OpenHighLeg pe =
        service().foldLive(rows, today, d2).get(0).pe();

    assertThat(pe.olMark()).isTrue();
    assertThat(pe.triggered()).isFalse();
    assertThat(pe.triggeredTime()).isNull();
    assertThat(pe.liveLtp()).isEqualByComparingTo("95.00");
  }

  @Test
  void foldLiveKeepsALiveOnlyStrikeWithoutAPattern() {
    LocalDate d2 = LocalDate.of(2026, 6, 17);
    List<OptionsSnapshotReader.StrikePoint> today =
        List.of(live("2026-06-17T09:15:00", "22700", "CE", "12.00"));

    List<OpenHighStrategyService.StrikeOpenHigh> out = service().foldLive(List.of(), today, d2);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).ce().ohMark()).isFalse();
    assertThat(out.get(0).ce().probability()).isNull();
    assertThat(out.get(0).ce().liveLtp()).isEqualByComparingTo("12.00");
  }

  @Test
  void strikesReturnedAscendingWithBothLegsWhenPresent() {
    LocalDate d1 = LocalDate.of(2026, 6, 16);
    List<OptionsSnapshotReader.OptionEodRow> rows =
        List.of(
            row("22600", "CE", d1, "10", "12", "9", "11", 100L, 50L),
            row("22400", "PE", d1, "200", "210", "200", "205", 300L, 80L),
            row("22500", "CE", d1, "100", "100", "95", "98", 200L, 60L));

    List<OpenHighStrategyService.StrikeOpenHigh> out = service().fold(rows);

    assertThat(out).extracting(e -> e.strike().intValueExact()).containsExactly(22400, 22500, 22600);
    // 22400 has only a PE leg; 22600 / 22500 only a CE leg.
    assertThat(out.get(0).pe()).isNotNull();
    assertThat(out.get(0).ce()).isNull();
    assertThat(out.get(1).ce()).isNotNull();
    assertThat(out.get(1).pe()).isNull();
  }
}
