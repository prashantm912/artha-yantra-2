package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.candles.EodCorporateActionRepository.Ratio;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Read-time multiplicative split/bonus adjustment, source-aware. */
class EquitySplitBonusAdjusterTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static Candle bar(LocalDate d, String close, String source) {
    BigDecimal c = new BigDecimal(close);
    return new Candle(
        "NSE", "TESTADJ", "1d", OffsetDateTime.of(d, LocalTime.MIDNIGHT, IST),
        c, c, c, c, 1000L, null, source);
  }

  private static EquitySplitBonusAdjuster adjusterWith(List<Ratio> events) {
    EodCorporateActionRepository stub =
        new EodCorporateActionRepository(null) {
          @Override
          public List<Ratio> ratiosFor(String exchange, String tradingsymbol) {
            return events;
          }
        };
    return new EquitySplitBonusAdjuster(stub);
  }

  @Test
  void scalesBhavcopyBarsBeforeExDateOnly() {
    EquitySplitBonusAdjuster adj =
        adjusterWith(List.of(new Ratio(LocalDate.of(2026, 6, 12), new BigDecimal("0.5"))));

    List<Candle> out =
        adj.adjust(
            "NSE",
            "TESTADJ",
            List.of(
                bar(LocalDate.of(2026, 6, 11), "200", "BHAVCOPY"), // before ex → ×0.5
                bar(LocalDate.of(2026, 6, 12), "100", "BHAVCOPY"), // on ex → unchanged
                bar(LocalDate.of(2026, 6, 13), "110", "BHAVCOPY"))); // after ex → unchanged

    assertThat(out.get(0).close()).isEqualByComparingTo("100.0000");
    assertThat(out.get(1).close()).isEqualByComparingTo("100");
    assertThat(out.get(2).close()).isEqualByComparingTo("110");
  }

  @Test
  void cumulativeAcrossMultipleActions() {
    EquitySplitBonusAdjuster adj =
        adjusterWith(
            List.of(
                new Ratio(LocalDate.of(2026, 6, 12), new BigDecimal("0.5")),
                new Ratio(LocalDate.of(2026, 6, 20), new BigDecimal("0.5"))));

    List<Candle> out =
        adj.adjust(
            "NSE",
            "TESTADJ",
            List.of(
                bar(LocalDate.of(2026, 6, 11), "400", "BHAVCOPY"), // before both → ×0.25
                bar(LocalDate.of(2026, 6, 15), "200", "BHAVCOPY"))); // before 2nd only → ×0.5

    assertThat(out.get(0).close()).isEqualByComparingTo("100.0000");
    assertThat(out.get(1).close()).isEqualByComparingTo("100.0000");
  }

  @Test
  void kiteSourcedBarsAreNeverReadjusted() {
    EquitySplitBonusAdjuster adj =
        adjusterWith(List.of(new Ratio(LocalDate.of(2026, 6, 12), new BigDecimal("0.5"))));

    List<Candle> out =
        adj.adjust("NSE", "TESTADJ", List.of(bar(LocalDate.of(2026, 6, 11), "200", "KITE")));

    assertThat(out.get(0).close()).isEqualByComparingTo("200"); // already broker-adjusted, untouched
  }

  @Test
  void noEventsIsIdentity() {
    EquitySplitBonusAdjuster adj = adjusterWith(List.of());
    Candle b = bar(LocalDate.of(2026, 6, 11), "200", "BHAVCOPY");
    assertThat(adj.adjust("NSE", "TESTADJ", List.of(b))).containsExactly(b);
  }
}
