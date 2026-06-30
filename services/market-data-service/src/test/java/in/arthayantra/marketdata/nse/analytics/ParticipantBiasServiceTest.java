package in.arthayantra.marketdata.nse.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.nse.analytics.NseEodReader.ParticipantOiRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** E3 §3.3 FII participant change-in-OI classifier — pure classify() coverage. */
class ParticipantBiasServiceTest {

  private static final LocalDate T0 = LocalDate.of(2026, 6, 29);
  private static final LocalDate T1 = LocalDate.of(2026, 6, 30);

  /** FII row with only the index futures + index option legs set (stock legs zero). */
  private static ParticipantOiRow fii(
      LocalDate date, long futLong, long futShort, long callLong, long putLong, long callShort, long putShort) {
    return new ParticipantOiRow(
        date, "FII", futLong, futShort, 0, 0, callLong, putLong, callShort, putShort, 0, 0, 0, 0,
        futLong + callLong + putLong, futShort + callShort + putShort);
  }

  @Test
  void priceUpWithRisingLongsIsALongBuildupBull() {
    ParticipantBiasService.Bias b =
        ParticipantBiasService.classify(
            fii(T0, 100_000, 80_000, 0, 0, 0, 0),
            fii(T1, 130_000, 80_000, 0, 0, 0, 0),
            new BigDecimal("120")); // index up
    assertThat(b.fiiClassification()).isEqualTo("LONG_BUILDUP");
    assertThat(b.bias()).isEqualTo("BULL");
    assertThat(b.biasSign()).isEqualTo(1);
  }

  @Test
  void priceDownWithRisingShortsIsAShortBuildupBear() {
    ParticipantBiasService.Bias b =
        ParticipantBiasService.classify(
            fii(T0, 100_000, 80_000, 0, 0, 0, 0),
            fii(T1, 100_000, 120_000, 0, 0, 0, 0),
            new BigDecimal("-90")); // index down
    assertThat(b.fiiClassification()).isEqualTo("SHORT_BUILDUP");
    assertThat(b.bias()).isEqualTo("BEAR");
    assertThat(b.biasSign()).isEqualTo(-1);
  }

  @Test
  void nullPriceDegradesToANetChangeLabelButKeepsTheSign() {
    ParticipantBiasService.Bias b =
        ParticipantBiasService.classify(
            fii(T0, 100_000, 80_000, 0, 0, 0, 0),
            fii(T1, 140_000, 80_000, 0, 0, 0, 0),
            null); // no index close on file
    assertThat(b.fiiClassification()).isEqualTo("NET_LONG_INCREASE");
    assertThat(b.biasSign()).isEqualTo(1); // net-long rose -> bull regardless of price
  }

  @Test
  void legSellerReadBreaksTheTieWhenFuturesAreFlat() {
    // No futures net change (fut sign 0) -> the option-leg read decides: net-long calls + net-short puts = bull.
    ParticipantBiasService.Bias b =
        ParticipantBiasService.classify(
            fii(T0, 100_000, 80_000, 0, 0, 0, 0),
            fii(T1, 100_000, 80_000, 50_000, 10_000, 10_000, 40_000),
            null);
    assertThat(b.legBias()).isEqualTo("BULL");
    assertThat(b.biasSign()).isEqualTo(1);
    assertThat(b.callNet()).isEqualTo(40_000); // 50k long - 10k short
    assertThat(b.putNet()).isEqualTo(-30_000); // 10k long - 40k short
  }

  @Test
  void fiiLongPctIsTheFuturesLongShareOfTheLatestRow() {
    ParticipantBiasService.Bias b =
        ParticipantBiasService.classify(
            fii(T0, 100_000, 100_000, 0, 0, 0, 0),
            fii(T1, 150_000, 50_000, 0, 0, 0, 0),
            null);
    assertThat(b.fiiLongPct()).isEqualByComparingTo(new BigDecimal("75.00")); // 150k / 200k
  }
}
