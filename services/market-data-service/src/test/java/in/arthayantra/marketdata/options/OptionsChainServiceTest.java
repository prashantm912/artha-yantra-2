package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.instruments.InstrumentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Horizon-bounded expiry selection for the multi-expiry snapshotter. */
class OptionsChainServiceTest {

  // 2026-06-15 09:00 IST
  private static final Clock IST_CLOCK =
      Clock.fixed(OffsetDateTime.parse("2026-06-15T09:00:00+05:30").toInstant(), ZoneOffset.UTC);

  private static OptionsChainService serviceWithExpiries(List<LocalDate> expiries) {
    InstrumentRepository stub =
        new InstrumentRepository(null) {
          @Override
          public List<LocalDate> expiries(String underlying) {
            return expiries;
          }
        };
    return new OptionsChainService(
        stub, null, null, IST_CLOCK, new BigDecimal("0.065"), true, java.util.Optional.empty());
  }

  @Test
  void expiriesWithinKeepsTodayThroughHorizonAndDropsPastAndBeyond() {
    OptionsChainService service =
        serviceWithExpiries(
            List.of(
                LocalDate.parse("2026-06-10"), // past — drop
                LocalDate.parse("2026-06-15"), // today — keep (boundary)
                LocalDate.parse("2026-06-18"), // weekly — keep
                LocalDate.parse("2026-09-13"), // today+90 — keep (boundary)
                LocalDate.parse("2026-09-14"), // today+91 — drop
                LocalDate.parse("2030-12-31"))); // LEAPS — drop

    List<LocalDate> within = service.expiriesWithin("NIFTY 50", 90);

    assertThat(within)
        .containsExactly(
            LocalDate.parse("2026-06-15"),
            LocalDate.parse("2026-06-18"),
            LocalDate.parse("2026-09-13"));
  }

  @Test
  void expiriesWithinIsEmptyWhenAllBeyondHorizon() {
    OptionsChainService service =
        serviceWithExpiries(List.of(LocalDate.parse("2027-01-01"), LocalDate.parse("2030-12-31")));

    assertThat(service.expiriesWithin("NIFTY 50", 90)).isEmpty();
  }
}
