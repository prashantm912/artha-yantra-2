package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The #11 two-leg ATM picker: the ATM-strike CE+PE pair nearest the forward, both BUY, deterministic. */
class StraddleLegPickerTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 25);
  private static final Instant NOW = LocalDate.of(2026, 6, 20).atTime(10, 0).atOffset(IST).toInstant();
  private static final double RATE = 0.065;

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static StrikePicker.Candidate ce(String strike, String ltp) {
    return new StrikePicker.Candidate("NFO", "N" + strike + "CE", bd(strike), CE, bd(ltp), bd("0.14"));
  }

  private static StrikePicker.Candidate pe(String strike, String ltp) {
    return new StrikePicker.Candidate("NFO", "N" + strike + "PE", bd(strike), PE, bd(ltp), bd("0.14"));
  }

  @Test
  void picksTheAtmPairNearestTheForward() {
    // forward 20020 → the ATM strike is 20000 (nearest); both legs of 20000 exist.
    List<StrikePicker.Candidate> chain =
        List.of(
            ce("19900", "160"), pe("19900", "70"),
            ce("20000", "120"), pe("20000", "110"),
            ce("20100", "80"), pe("20100", "150"));

    Optional<StraddleLegPicker.Straddle> s =
        StraddleLegPicker.pick(chain, bd("20020"), BigDecimal.ZERO, NOW, EXPIRY, RATE);

    assertThat(s).isPresent();
    assertThat(s.get().strike()).isEqualByComparingTo("20000");
    assertThat(s.get().call().candidate().tradingsymbol()).isEqualTo("N20000CE");
    assertThat(s.get().put().candidate().tradingsymbol()).isEqualTo("N20000PE");
    // both legs are on the SAME strike (§3.11) and carry an ATM delta near 0.5
    assertThat(s.get().call().candidate().strike())
        .isEqualByComparingTo(s.get().put().candidate().strike());
    assertThat(s.get().call().delta().doubleValue()).isBetween(0.40, 0.60);
    assertThat(s.get().put().delta().doubleValue()).isBetween(0.40, 0.60);
  }

  @Test
  void honoursTheBasisWhenLocatingTheAtm() {
    // spot 19990 + basis 30 → forward 20020 → ATM 20000, not the spot-nearest 19900.
    List<StrikePicker.Candidate> chain =
        List.of(
            ce("19900", "160"), pe("19900", "70"),
            ce("20000", "120"), pe("20000", "110"));

    Optional<StraddleLegPicker.Straddle> s =
        StraddleLegPicker.pick(chain, bd("19990"), bd("30"), NOW, EXPIRY, RATE);

    assertThat(s).isPresent();
    assertThat(s.get().strike()).isEqualByComparingTo("20000");
  }

  @Test
  void returnsEmptyWhenNoStrikeHasBothLegs() {
    // only CE legs present → no complete ATM pair → no straddle.
    List<StrikePicker.Candidate> chain = List.of(ce("20000", "120"), ce("19900", "160"));

    assertThat(StraddleLegPicker.pick(chain, bd("20000"), BigDecimal.ZERO, NOW, EXPIRY, RATE))
        .isEmpty();
  }

  @Test
  void returnsEmptyPastTheExpiryCutoff() {
    // 16:00 IST on the expiry day is past the 15:30 cutoff → no live option.
    Instant afterCutoff = EXPIRY.atTime(16, 0).atOffset(IST).toInstant();
    List<StrikePicker.Candidate> chain = List.of(ce("20000", "120"), pe("20000", "110"));

    assertThat(StraddleLegPicker.pick(chain, bd("20000"), BigDecimal.ZERO, afterCutoff, EXPIRY, RATE))
        .isEmpty();
  }

  @Test
  void isDeterministicForTheSameInputs() {
    List<StrikePicker.Candidate> chain =
        List.of(ce("20000", "120"), pe("20000", "110"), ce("20100", "80"), pe("20100", "150"));

    Optional<StraddleLegPicker.Straddle> a =
        StraddleLegPicker.pick(chain, bd("20000"), BigDecimal.ZERO, NOW, EXPIRY, RATE);
    Optional<StraddleLegPicker.Straddle> b =
        StraddleLegPicker.pick(chain, bd("20000"), BigDecimal.ZERO, NOW, EXPIRY, RATE);

    assertThat(a).isPresent();
    assertThat(b).isPresent();
    assertThat(a.get().call().delta()).isEqualByComparingTo(b.get().call().delta());
    assertThat(a.get().put().delta()).isEqualByComparingTo(b.get().put().delta());
  }
}
