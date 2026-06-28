package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.backtest.replay.options.OptionContractSelector.Catalog;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Expiry;
import in.arthayantra.backtest.replay.options.OptionContractSelector.ExpiryMode;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Fixture test for {@link OptionContractSelector}: the §D.6 {@code options_of_underlying} selection —
 * expiry rule, ATM-at-spot strike, and long→CE / short→PE bias. A fake {@link Catalog} stands in for
 * the {@code expired_contracts} registry, so no DB is needed.
 */
class OptionContractSelectorTest {

  private static final LocalDate W1 = LocalDate.of(2026, 6, 9); // weekly
  private static final LocalDate W2 = LocalDate.of(2026, 6, 16); // weekly
  private static final LocalDate M1 = LocalDate.of(2026, 6, 26); // monthly

  /** Fake registry: three expiries; nearest strike picked from a fixed ladder around 24000–25500. */
  private static final Catalog CATALOG =
      new Catalog() {
        private final List<Expiry> expiries =
            List.of(new Expiry(W1, true), new Expiry(W2, true), new Expiry(M1, false));

        @Override
        public List<Expiry> expiriesOnOrAfter(String underlying, LocalDate date) {
          return expiries.stream().filter(e -> !e.date().isBefore(date)).toList();
        }

        @Override
        public Optional<OptionContract> nearestStrike(
            String underlying, LocalDate expiry, String optionType, BigDecimal spot) {
          BigDecimal nearest = null;
          for (BigDecimal s :
              List.of(
                  new BigDecimal("24000"), new BigDecimal("24500"),
                  new BigDecimal("25000"), new BigDecimal("25500"))) {
            if (nearest == null
                || s.subtract(spot).abs().compareTo(nearest.subtract(spot).abs()) < 0) {
              nearest = s;
            }
          }
          return Optional.of(
              new OptionContract("NFO", underlying + nearest + optionType, nearest, expiry, optionType, 65));
        }
      };

  private final OptionContractSelector selector = new OptionContractSelector(CATALOG);

  @Test
  void pickExpiryHonoursTheRule() {
    List<Expiry> rows = List.of(new Expiry(W1, true), new Expiry(W2, true), new Expiry(M1, false));
    assertThat(OptionContractSelector.pickExpiry(rows, ExpiryMode.NEAREST_WEEKLY, 0)).isEqualTo(W1);
    assertThat(OptionContractSelector.pickExpiry(rows, ExpiryMode.NEAREST_MONTHLY, 0)).isEqualTo(M1);
    assertThat(OptionContractSelector.pickExpiry(rows, ExpiryMode.OFFSET, 1)).isEqualTo(W2);
    assertThat(OptionContractSelector.pickExpiry(rows, ExpiryMode.OFFSET, 9)).isNull();
    assertThat(OptionContractSelector.pickExpiry(List.of(), ExpiryMode.NEAREST_WEEKLY, 0)).isNull();
  }

  @Test
  void longSignalBuysNearestWeeklyAtmCe() {
    Optional<OptionContract> c =
        selector.select(
            "NIFTY", new BigDecimal("24980"), LocalDate.of(2026, 6, 5),
            ExpiryMode.NEAREST_WEEKLY, 0, true, Set.of("CE", "PE"));

    assertThat(c).isPresent();
    assertThat(c.get().optionType()).isEqualTo("CE");
    assertThat(c.get().expiry()).isEqualTo(W1);
    assertThat(c.get().strike()).isEqualByComparingTo("25000"); // nearest to 24980
    assertThat(c.get().exchange()).isEqualTo("NFO");
    assertThat(c.get().lotSize()).isEqualTo(65);
  }

  @Test
  void shortSignalBuysPe() {
    Optional<OptionContract> c =
        selector.select(
            "NIFTY", new BigDecimal("24100"), LocalDate.of(2026, 6, 5),
            ExpiryMode.NEAREST_WEEKLY, 0, false, Set.of("CE", "PE"));

    assertThat(c).isPresent();
    assertThat(c.get().optionType()).isEqualTo("PE");
    assertThat(c.get().strike()).isEqualByComparingTo("24000"); // nearest to 24100
  }

  @Test
  void biasSideNotInOptionTypesYieldsNoContract() {
    // a CE-only strategy on a short (PE) signal cannot express the trade → empty.
    assertThat(
            selector.select(
                "NIFTY", new BigDecimal("24100"), LocalDate.of(2026, 6, 5),
                ExpiryMode.NEAREST_WEEKLY, 0, false, Set.of("CE")))
        .isEmpty();
  }

  @Test
  void noExpiryOnOrAfterYieldsNoContract() {
    assertThat(
            selector.select(
                "NIFTY", new BigDecimal("24980"), LocalDate.of(2026, 7, 1),
                ExpiryMode.NEAREST_WEEKLY, 0, true, Set.of("CE", "PE")))
        .isEmpty();
  }

  // ---- E7 band-aware selection (selectInBand) ----

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  /** ATM-25000 strike ladder, ascending by |strike − 25000| (the shape JdbcExpiredContractCatalog returns). */
  private static final List<BigDecimal> LADDER =
      List.of(bd("25000"), bd("24900"), bd("25100"), bd("24800"), bd("25200"));

  private static final Catalog BAND_CATALOG =
      new Catalog() {
        @Override
        public List<Expiry> expiriesOnOrAfter(String u, LocalDate d) {
          return List.of(new Expiry(W1, true));
        }

        @Override
        public Optional<OptionContract> nearestStrike(String u, LocalDate e, String t, BigDecimal s) {
          return Optional.of(new OptionContract("NFO", u + LADDER.get(0) + t, LADDER.get(0), e, t, 65));
        }

        @Override
        public List<OptionContract> nearestStrikes(
            String u, LocalDate e, String t, BigDecimal s, int window) {
          int limit = Math.min(LADDER.size(), 2 * window + 1);
          return LADDER.subList(0, limit).stream()
              .map(k -> new OptionContract("NFO", u + k + t, k, e, t, 65))
              .toList();
        }
      };

  /** A premium probe keyed by the candidate's strike (plain string); an absent strike ⇒ null (untradeable). */
  private static Function<OptionContract, BigDecimal> probe(Map<String, String> byStrike) {
    return c -> {
      String v = byStrike.get(c.strike().toPlainString());
      return v == null ? null : new BigDecimal(v);
    };
  }

  private OptionContractSelector band() {
    return new OptionContractSelector(BAND_CATALOG);
  }

  @Test
  void selectInBandPicksTheInBandAtmItmStrikeNearestTheMidpoint() {
    // band [100,250] mid 175, spot 25000, step 100 (so ≤25100 is ATM/ITM for a CE). 25000=300 (out),
    // 24900=180 (in, err 5 ← nearest mid), 25100=120 (in, err 55), 24800=230 (in, err 55), 25200 deep-OTM.
    Map<String, String> prem =
        Map.of("25000", "300", "24900", "180", "25100", "120", "24800", "230", "25200", "175");
    Optional<OptionContract> c =
        band().selectInBand(
            "NIFTY", bd("25000"), LocalDate.of(2026, 6, 5), ExpiryMode.NEAREST_WEEKLY, 0,
            true, Set.of("CE", "PE"), 2, bd("100"), bd("250"), bd("100"), probe(prem));

    assertThat(c).isPresent();
    assertThat(c.get().strike()).isEqualByComparingTo("24900");
  }

  @Test
  void selectInBandIsEmptyWhenNoStrikeIsInBand() {
    // every ATM/ITM candidate's premium is out of [100,250] → empty (the caller decides fallback/skip).
    Map<String, String> prem =
        Map.of("25000", "300", "24900", "320", "25100", "80", "24800", "350");
    Optional<OptionContract> c =
        band().selectInBand(
            "NIFTY", bd("25000"), LocalDate.of(2026, 6, 5), ExpiryMode.NEAREST_WEEKLY, 0,
            true, Set.of("CE", "PE"), 2, bd("100"), bd("250"), bd("100"), probe(prem));

    assertThat(c).isEmpty();
  }

  @Test
  void selectInBandExcludesDeepOtm() {
    // only the deep-OTM 25200 (> spot+step 25100) is in band by premium → excluded → empty, never traded.
    Map<String, String> prem =
        Map.of("25000", "300", "24900", "300", "25100", "300", "24800", "300", "25200", "175");
    Optional<OptionContract> c =
        band().selectInBand(
            "NIFTY", bd("25000"), LocalDate.of(2026, 6, 5), ExpiryMode.NEAREST_WEEKLY, 0,
            true, Set.of("CE", "PE"), 2, bd("100"), bd("250"), bd("100"), probe(prem));

    assertThat(c).isEmpty();
  }

  @Test
  void selectInBandWithNullStepAppliesNoSideCut() {
    // strikeStep null ⇒ no ATM/ITM cut (the live StrikePicker shape): the near-OTM 25000 (> spot 24960)
    // is NOT excluded, so its in-band premium makes it selectable (a non-null step would have cut it).
    Map<String, String> prem = Map.of("25000", "180", "24900", "300", "25100", "300");
    Optional<OptionContract> c =
        band().selectInBand(
            "NIFTY", bd("24960"), LocalDate.of(2026, 6, 5), ExpiryMode.NEAREST_WEEKLY, 0,
            true, Set.of("CE", "PE"), 2, bd("100"), bd("250"), null, probe(prem));

    assertThat(c).isPresent();
    assertThat(c.get().strike()).isEqualByComparingTo("25000");
  }

  @Test
  void selectInBandBreaksMidpointTiesByNearnessThenLowerStrike() {
    // 24900 and 25100 tie on midpoint error (both premium 175 = mid) and on nearness (both 100 off spot)
    // → the lower strike 24900 wins deterministically, regardless of catalog row order. step null = no cut.
    Map<String, String> prem = Map.of("25000", "300", "24900", "175", "25100", "175");
    Optional<OptionContract> c =
        band().selectInBand(
            "NIFTY", bd("25000"), LocalDate.of(2026, 6, 5), ExpiryMode.NEAREST_WEEKLY, 0,
            true, Set.of("CE", "PE"), 2, bd("100"), bd("250"), null, probe(prem));

    assertThat(c).isPresent();
    assertThat(c.get().strike()).isEqualByComparingTo("24900");
  }
}
