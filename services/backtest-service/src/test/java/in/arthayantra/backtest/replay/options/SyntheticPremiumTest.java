package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.black76.Black76;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the §D.15 synthetic premium reconstruction: determinism (two reconstructions byte-identical),
 * a known Black-76 premium value matching {@code libs/black76-math}, and the flat-IV caveat surfaced
 * as a run-level honesty flag (never buried).
 */
class SyntheticPremiumTest {

  private static final LocalDate EXPIRY = LocalDate.of(2026, 1, 8);
  private static final BigDecimal STRIKE = new BigDecimal("21500");
  private static final BigDecimal FLAT_IV = new BigDecimal("0.15");
  private static final BigDecimal RFR = new BigDecimal("0.065");

  @Test
  void twoReconstructionsAreByteIdentical() {
    List<EngineCandle> candles = candles();
    SyntheticPremium.Result first =
        SyntheticPremium.flatIv(candles, Black76.OptionType.CE, STRIKE, EXPIRY, FLAT_IV, RFR);
    SyntheticPremium.Result second =
        SyntheticPremium.flatIv(candles, Black76.OptionType.CE, STRIKE, EXPIRY, FLAT_IV, RFR);

    assertThat(plain(second.series()))
        .as("same inputs reconstruct a byte-identical premium series (determinism)")
        .isEqualTo(plain(first.series()));
  }

  @Test
  void premiumMatchesTheBlack76Library() {
    EngineCandle candle = candle("2026-01-05T09:15:00+05:30", "21600");
    SyntheticPremium.Result result =
        SyntheticPremium.flatIv(
            List.of(candle), Black76.OptionType.CE, STRIKE, EXPIRY, FLAT_IV, RFR);

    OffsetDateTime expiryClose = OffsetDateTime.parse("2026-01-08T15:30:00+05:30");
    double t = SyntheticPremium.yearFraction(candle.bucketStart(), expiryClose);
    double expected =
        Black76.price(
            Black76.OptionType.CE,
            21600.0,
            STRIKE.doubleValue(),
            t,
            RFR.doubleValue(),
            FLAT_IV.doubleValue());

    assertThat(result.series()).hasSize(1);
    assertThat(result.series().get(0).premium())
        .as("synthetic premium equals the Black-76 lib price on the forward")
        .isEqualByComparingTo(BigDecimal.valueOf(expected));
    assertThat(expected).isGreaterThan(0.0);
  }

  @Test
  void flatIvCaveatIsSurfaced() {
    SyntheticPremium.Result result =
        SyntheticPremium.flatIv(candles(), Black76.OptionType.PE, STRIKE, EXPIRY, FLAT_IV, RFR);
    assertThat(result.caveats()).hasSize(1);
    assertThat(result.caveats().get(0))
        .contains("SYNTHETIC_B76")
        .contains("FLAT-IV")
        .contains("not snapshot-grade");
  }

  @Test
  void nearestSnapshotIvCaveatIsSurfaced() {
    SyntheticPremium.Result result =
        SyntheticPremium.nearestSnapshotIv(
            candles(), Black76.OptionType.CE, STRIKE, EXPIRY, new BigDecimal("0.18"), RFR);
    assertThat(result.caveats()).hasSize(1);
    assertThat(result.caveats().get(0))
        .contains("SYNTHETIC_B76")
        .contains("nearest archived-snapshot IV")
        .contains("not snapshot-grade");
  }

  @Test
  void yearFractionIsZeroAtAndAfterExpiry() {
    OffsetDateTime expiry = OffsetDateTime.parse("2026-01-08T15:30:00+05:30");
    assertThat(SyntheticPremium.yearFraction(expiry, expiry)).isEqualTo(0.0);
    assertThat(
            SyntheticPremium.yearFraction(
                OffsetDateTime.parse("2026-01-09T09:15:00+05:30"), expiry))
        .isEqualTo(0.0);
    assertThat(
            SyntheticPremium.yearFraction(
                OffsetDateTime.parse("2026-01-05T09:15:00+05:30"), expiry))
        .isGreaterThan(0.0);
  }

  @Test
  void aZeroIvIsRejectedRatherThanProducingNanPremiums() {
    // sigma=0 makes Black76.price divide by zero → NaN; BigDecimal.valueOf(NaN) would throw a
    // cryptic NumberFormatException, so the entry point fails fast with a clear message instead.
    assertThatThrownBy(
            () ->
                SyntheticPremium.flatIv(
                    candles(), Black76.OptionType.CE, STRIKE, EXPIRY, BigDecimal.ZERO, RFR))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive iv");
  }

  @Test
  void aNonPositiveUnderlyingCloseIsRejected() {
    EngineCandle bad = candle("2026-01-05T09:15:00+05:30", "0");
    assertThatThrownBy(
            () ->
                SyntheticPremium.flatIv(
                    List.of(bad), Black76.OptionType.CE, STRIKE, EXPIRY, FLAT_IV, RFR))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forward");
  }

  private static List<String> plain(List<SyntheticPremium.PremiumPoint> series) {
    List<String> out = new ArrayList<>(series.size());
    for (SyntheticPremium.PremiumPoint p : series) {
      out.add(p.ts() + "=" + p.premium().toPlainString());
    }
    return out;
  }

  private static List<EngineCandle> candles() {
    List<EngineCandle> out = new ArrayList<>();
    out.add(candle("2026-01-05T09:15:00+05:30", "21600"));
    out.add(candle("2026-01-05T09:16:00+05:30", "21620"));
    out.add(candle("2026-01-05T09:17:00+05:30", "21580"));
    return out;
  }

  private static EngineCandle candle(String ts, String close) {
    BigDecimal c = new BigDecimal(close);
    return new EngineCandle(
        OffsetDateTime.parse(ts), c, c, c, c, 1000L, null);
  }
}
