package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.instruments.Instrument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** B-18 basis math against a hand-computed fixture (4 dp) + state classification. */
class FuturesBasisTest {

  private static Instrument fut(String symbol, LocalDate expiry) {
    return new Instrument(
        "NFO", symbol, 1L, "NIFTY", "NFO-FUT", "FUT", "NSE", "NIFTY 50", expiry,
        null, new BigDecimal("0.05"), 75, true);
  }

  @Test
  void basisAbsoluteAndAnnualizedMatchTheHandComputedFixture() {
    // now = 2026-06-15T11:00 IST; expiry 2026-07-15 → cutoff 15:30 IST = 30 full days
    OffsetDateTime now = OffsetDateTime.parse("2026-06-15T11:00:00+05:30");
    Instrument contract = fut("NIFTYJULFUT", LocalDate.parse("2026-07-15"));

    FuturesTermStructureService.ContractLeg leg =
        FuturesTermStructureService.leg(
            contract, new BigDecimal("24120"), 100_000L, 500L, new BigDecimal("24000"), now);

    assertThat(leg.basisAbsolute()).isEqualByComparingTo("120.0000");
    // (24120/24000 − 1) × 365 / 30 = 0.005 × 12.1666… = 0.0608 at 4 dp
    assertThat(leg.daysToExpiry()).isEqualTo(30);
    assertThat(leg.basisAnnualized()).isEqualByComparingTo("0.0608");
  }

  @Test
  void backwardationProducesNegativeBasis() {
    OffsetDateTime now = OffsetDateTime.parse("2026-06-15T11:00:00+05:30");
    FuturesTermStructureService.ContractLeg leg =
        FuturesTermStructureService.leg(
            fut("X", LocalDate.parse("2026-07-15")),
            new BigDecimal("23880"),
            null,
            null,
            new BigDecimal("24000"),
            now);

    assertThat(leg.basisAbsolute()).isEqualByComparingTo("-120.0000");
    assertThat(leg.basisAnnualized().signum()).isNegative();
  }

  @Test
  void stateIsDecidedByTheNearToNextSlopeBothDirections() {
    OffsetDateTime now = OffsetDateTime.parse("2026-06-15T11:00:00+05:30");
    var near =
        FuturesTermStructureService.leg(
            fut("NEAR", LocalDate.parse("2026-06-25")), new BigDecimal("24100"), null, null,
            new BigDecimal("24000"), now);
    var nextUp =
        FuturesTermStructureService.leg(
            fut("NEXT", LocalDate.parse("2026-07-28")), new BigDecimal("24200"), null, null,
            new BigDecimal("24000"), now);
    var nextDown =
        FuturesTermStructureService.leg(
            fut("NEXT", LocalDate.parse("2026-07-28")), new BigDecimal("23900"), null, null,
            new BigDecimal("24000"), now);

    assertThat(FuturesTermStructureService.classify(java.util.List.of(near, nextUp)))
        .isEqualTo("CONTANGO");
    assertThat(FuturesTermStructureService.classify(java.util.List.of(near, nextDown)))
        .isEqualTo("BACKWARDATION");
  }

  @Test
  void daysToExpiryNeverHitsZeroOnExpiryDay() {
    // 11:00 on expiry day: 4.5 hours to cutoff → clamped to 1 day, never division by zero
    OffsetDateTime now = OffsetDateTime.parse("2026-07-15T11:00:00+05:30");
    FuturesTermStructureService.ContractLeg leg =
        FuturesTermStructureService.leg(
            fut("X", LocalDate.parse("2026-07-15")),
            new BigDecimal("24010"),
            null,
            null,
            new BigDecimal("24000"),
            now);

    assertThat(leg.daysToExpiry()).isEqualTo(1);
    assertThat(leg.basisAnnualized()).isNotNull();
  }
}
