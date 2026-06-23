package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.backtest.replay.options.OptionContractSelector.Catalog;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Expiry;
import in.arthayantra.backtest.replay.options.OptionContractSelector.ExpiryMode;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
}
