package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.MarketOiClient.StrikeOi;
import in.arthayantra.strategysignal.scalper.StrikePicker.Candidate;
import in.arthayantra.strategysignal.scalper.StrikePicker.Pick;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #7 Hero-Zero literal one-away strike selection (cheat sheet section 7): bullish buys the CALL one
 * strike BELOW the max-CE-OI (SC call) strike; bearish buys the PUT one strike ABOVE the max-PE-OI (SC
 * put) strike. The SC strike + step come from the LIVE per-strike OI ladder; an absent ladder / target
 * strike degrades to empty so the caller falls back to {@link StrikePicker}.
 */
class HeroZeroStrikeSelectorTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 25);
  // ~5 days to expiry, well inside the 15:30 cutoff.
  private static final Instant NOW = LocalDate.of(2026, 6, 20).atTime(10, 0).atOffset(IST).toInstant();
  private static final BigDecimal SPOT = bd("20000");
  private static final double RATE = 0.065;

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static Candidate leg(String strike, OptionType type) {
    String t = type == CE ? "CE" : "PE";
    return new Candidate("NIFTY" + strike + t, bd(strike), type, bd("180"), bd("0.14"));
  }

  /** A both-side chain on a 50-point ladder over 19850..20150 (every strike tradeable). */
  private static List<Candidate> bothSideChain() {
    List<Candidate> out = new ArrayList<>();
    for (int k = 19850; k <= 20150; k += 50) {
      out.add(leg(Integer.toString(k), CE));
      out.add(leg(Integer.toString(k), PE));
    }
    return out;
  }

  /**
   * The OI ladder. {@code ceMaxStrike} carries the peak CE OI, {@code peMaxStrike} the peak PE OI; every
   * other strike carries a small uniform OI so the step (50) is derivable and the max is unambiguous.
   */
  private static List<StrikeOi> ladder(int ceMaxStrike, int peMaxStrike) {
    List<StrikeOi> out = new ArrayList<>();
    for (int k = 19850; k <= 20150; k += 50) {
      long ce = k == ceMaxStrike ? 9_000_000L : 100_000L;
      long pe = k == peMaxStrike ? 9_000_000L : 100_000L;
      out.add(new StrikeOi(bd(Integer.toString(k)), ce, pe));
    }
    return out;
  }

  @Test
  void bullishBuysTheCallOneStrikeBelowTheScCallStrike() {
    // SC call (max CE OI) at 20100 -> buy the CALL one step (50) BELOW it = 20050.
    Optional<Pick> pick =
        HeroZeroStrikeSelector.select(
            bothSideChain(), ladder(20100, 19900), SPOT, bd("0"), CE, NOW, EXPIRY, RATE);

    assertThat(pick).isPresent();
    assertThat(pick.get().candidate().type()).isEqualTo(CE);
    assertThat(pick.get().candidate().strike()).isEqualByComparingTo("20050");
    assertThat(pick.get().candidate().tradingsymbol()).isEqualTo("NIFTY20050CE");
    assertThat(pick.get().delta()).isNotNull();
  }

  @Test
  void bearishBuysThePutOneStrikeAboveTheScPutStrike() {
    // SC put (max PE OI) at 19900 -> buy the PUT one step (50) ABOVE it = 19950.
    Optional<Pick> pick =
        HeroZeroStrikeSelector.select(
            bothSideChain(), ladder(20100, 19900), SPOT, bd("0"), PE, NOW, EXPIRY, RATE);

    assertThat(pick).isPresent();
    assertThat(pick.get().candidate().type()).isEqualTo(PE);
    assertThat(pick.get().candidate().strike()).isEqualByComparingTo("19950");
    assertThat(pick.get().candidate().tradingsymbol()).isEqualTo("NIFTY19950PE");
  }

  @Test
  void degradesToEmptyWhenTheOiLadderIsEmpty() {
    assertThat(
            HeroZeroStrikeSelector.select(
                bothSideChain(), List.of(), SPOT, bd("0"), CE, NOW, EXPIRY, RATE))
        .isEmpty();
  }

  @Test
  void degradesToEmptyWhenTheOiLadderIsNull() {
    assertThat(
            HeroZeroStrikeSelector.select(
                bothSideChain(), null, SPOT, bd("0"), CE, NOW, EXPIRY, RATE))
        .isEmpty();
  }

  @Test
  void degradesToEmptyWhenNoStrikeCarriesSideOi() {
    // every strike's CE OI is null -> no SC call strike -> empty.
    List<StrikeOi> noCeOi = new ArrayList<>();
    for (int k = 19850; k <= 20150; k += 50) {
      noCeOi.add(new StrikeOi(bd(Integer.toString(k)), null, 100_000L));
    }
    assertThat(
            HeroZeroStrikeSelector.select(
                bothSideChain(), noCeOi, SPOT, bd("0"), CE, NOW, EXPIRY, RATE))
        .isEmpty();
  }

  @Test
  void degradesToEmptyWhenTheOneAwayTargetStrikeIsNotListed() {
    // SC call at the BOTTOM of the ladder (19850) -> one below = 19800, which the chain does not list.
    Optional<Pick> pick =
        HeroZeroStrikeSelector.select(
            bothSideChain(), ladder(19850, 19900), SPOT, bd("0"), CE, NOW, EXPIRY, RATE);
    assertThat(pick).isEmpty();
  }

  @Test
  void degradesToEmptyWhenTheTargetLegIsUntradeable() {
    // the one-away target (20050 CE) exists but carries no IV -> degrade to the band fallback.
    List<Candidate> chain = new ArrayList<>();
    for (int k = 19850; k <= 20150; k += 50) {
      if (k == 20050) {
        chain.add(new Candidate("NIFTY20050CE", bd("20050"), CE, bd("180"), null)); // null IV
      } else {
        chain.add(leg(Integer.toString(k), CE));
      }
      chain.add(leg(Integer.toString(k), PE));
    }
    assertThat(
            HeroZeroStrikeSelector.select(
                chain, ladder(20100, 19900), SPOT, bd("0"), CE, NOW, EXPIRY, RATE))
        .isEmpty();
  }

  @Test
  void degradesToEmptyPastTheExpiryCutoff() {
    Instant afterClose = EXPIRY.atTime(16, 0).atOffset(IST).toInstant(); // 16:00 IST on expiry day
    assertThat(
            HeroZeroStrikeSelector.select(
                bothSideChain(), ladder(20100, 19900), SPOT, bd("0"), CE, afterClose, EXPIRY, RATE))
        .isEmpty();
  }
}
