package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.scalper.StrikePicker.Candidate;
import in.arthayantra.strategysignal.scalper.StrikePicker.Params;
import in.arthayantra.strategysignal.scalper.StrikePicker.Pick;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Delta-/premium-band strike selection (§12.4 — Siva ATM±3, delta 0.6–0.7, premium band). */
class StrikePickerTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 25);
  // ~5 days to expiry, well inside the 15:30 cutoff
  private static final Instant NOW = LocalDate.of(2026, 6, 20).atTime(10, 0).atOffset(IST).toInstant();
  // delta 0.6–0.7, premium 100–400 (NIFTY band), rate 6.5%
  private static final Params P = new Params(0.6, 0.7, bd("100"), bd("400"), 0.065);

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static Candidate ce(String strike, String ltp, String iv) {
    return new Candidate("NFO", "NIFTY" + strike + "CE", bd(strike), CE, bd(ltp), bd(iv));
  }

  @Test
  void picksTheOnlyInBandStrike() {
    // spot 20000, iv 0.14, ~5d: 19850 CE ≈ delta 0.68 (in band); 19950 ≈ 0.56 and 20000 ≈ 0.50 (out)
    List<Candidate> chain = List.of(ce("19850", "200", "0.14"), ce("19950", "200", "0.14"), ce("20000", "120", "0.14"));

    Optional<Pick> pick = StrikePicker.pick(chain, bd("20000"), bd("0"), CE, NOW, EXPIRY, P);

    assertThat(pick).isPresent();
    assertThat(pick.get().candidate().strike()).isEqualByComparingTo("19850");
    assertThat(pick.get().delta().doubleValue()).isBetween(0.6, 0.7);
  }

  @Test
  void rejectsWhenPremiumOutOfBand() {
    // 19850 would be delta-in-band, but its premium 600 is above the 400 ceiling
    List<Candidate> chain = List.of(ce("19850", "600", "0.14"));

    assertThat(StrikePicker.pick(chain, bd("20000"), bd("0"), CE, NOW, EXPIRY, P)).isEmpty();
  }

  @Test
  void emptyWhenNoStrikeInDeltaBand() {
    // deep-OTM CEs: all deltas well below 0.6
    List<Candidate> chain = List.of(ce("20500", "120", "0.14"), ce("20800", "110", "0.14"));

    assertThat(StrikePicker.pick(chain, bd("20000"), bd("0"), CE, NOW, EXPIRY, P)).isEmpty();
  }

  @Test
  void emptyWhenPastExpiryCutoff() {
    Instant afterClose = EXPIRY.atTime(16, 0).atOffset(IST).toInstant(); // 16:00 IST on expiry day
    List<Candidate> chain = List.of(ce("19850", "200", "0.14"));

    assertThat(StrikePicker.pick(chain, bd("20000"), bd("0"), CE, afterClose, EXPIRY, P)).isEmpty();
  }

  @Test
  void ignoresTheWrongSide() {
    // asking for CE but the chain only has PE strikes
    List<Candidate> puts = List.of(new Candidate("NFO", "NIFTY19850PE", bd("19850"), PE, bd("200"), bd("0.14")));

    assertThat(StrikePicker.pick(puts, bd("20000"), bd("0"), CE, NOW, EXPIRY, P)).isEmpty();
  }
}
