package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * T2 (audit 2026-07-02 §9.1) boundary-aligned capture: rows stamp on the minute grid, and the
 * scheduled gate runs iff the capture-cadence window the fire REPRESENTS was in-session — the
 * 09:15:00/09:16:00 fires are skipped (pre-open window) and the 15:30:00 fire still runs (the EOD
 * capture), while post-close fires stop.
 */
class OptionsSnapshotBoundaryTest {

  private static final LocalDate EXPIRY = LocalDate.parse("2026-06-16");
  private static final BigDecimal STRIKE = new BigDecimal("23800");
  private static final BigDecimal STRIKE_KEY = STRIKE.setScale(2, java.math.RoundingMode.HALF_UP);
  // covers 2026 so isOpenSafe never trips the uncovered-year guard
  private static final MarketCalendar CAL = MarketCalendar.of(List.of(LocalDate.parse("2026-01-26")));

  private static Clock istClock(String isoIst) {
    return Clock.fixed(OffsetDateTime.parse(isoIst).toInstant(), ZoneOffset.UTC);
  }

  private static InstrumentRepository instrumentStub() {
    Instrument ce =
        new Instrument(
            "NFO", "NIFTY26JUN23800CE", 1L, "NIFTY", "NFO-OPT", "CE", "NSE", "NIFTY 50", EXPIRY,
            STRIKE, new BigDecimal("0.05"), 75, true);
    Instrument pe =
        new Instrument(
            "NFO", "NIFTY26JUN23800PE", 2L, "NIFTY", "NFO-OPT", "PE", "NSE", "NIFTY 50", EXPIRY,
            STRIKE, new BigDecimal("0.05"), 75, true);
    return new InstrumentRepository(null) {
      @Override
      public List<LocalDate> expiries(String underlying) {
        return List.of(EXPIRY);
      }

      @Override
      public List<Instrument> optionChain(String underlying, LocalDate expiry) {
        return List.of(ce, pe);
      }
    };
  }

  private static final class CapturingRepo extends OptionsSnapshotRepository {
    final List<SnapshotRow> rows = new ArrayList<>();

    CapturingRepo() {
      super(null);
    }

    @Override
    public void insertAll(List<SnapshotRow> rows) {
      this.rows.addAll(rows);
    }
  }

  private static OptionChainQuoteSource quoteSource() {
    OptionChainQuoteSource.Leg leg =
        new OptionChainQuoteSource.Leg(
            new BigDecimal("120.5"), new BigDecimal("120.0"), new BigDecimal("121.0"),
            345600L, 1820075L, 1750000L);
    return (underlying, expiry) ->
        Optional.of(
            new OptionChainQuoteSource.ChainQuotes(
                new BigDecimal("23845.85"), Map.of(STRIKE_KEY, leg), Map.of(STRIKE_KEY, leg)));
  }

  private static OptionsSnapshotService service(Clock clock, CapturingRepo repo) {
    OptionsChainService chainService =
        new OptionsChainService(
            instrumentStub(), null, CAL, clock, new BigDecimal("0.065"), true,
            Optional.of(quoteSource()));
    return new OptionsSnapshotService(
        chainService,
        repo,
        new StringRedisTemplate(), // publish() swallows the no-connection failure
        new ObjectMapper(),
        CAL,
        clock,
        List.of("NIFTY 50"),
        90,
        new SimpleMeterRegistry(),
        // fail-soft ledger: the capture-session write no-ops on the null template (swallowed)
        new in.arthayantra.marketdata.ingest.IngestRunLedger(null),
        // V6 outlier detector DISABLED so this test's capture behavior is unchanged
        new OiOutlierDetector(false, 20.0, 5000));
  }

  @Test
  void stampsRowsOnTheMinuteGrid() {
    CapturingRepo repo = new CapturingRepo();
    service(istClock("2026-06-15T11:00:37+05:30"), repo).snapshotNow("NIFTY 50", EXPIRY);

    assertThat(repo.rows).isNotEmpty();
    assertThat(repo.rows.get(0).ts().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-06-15T11:00:00+05:30").toInstant());
  }

  @Test
  void gateSkipsPreOpenFiresRunsTheEodFireAndStopsAfterClose() {
    // 09:16:00 fire: the represented window (09:14, 09:16] starts pre-open -> skipped.
    CapturingRepo preOpen = new CapturingRepo();
    service(istClock("2026-06-15T09:16:00+05:30"), preOpen).scheduledSnapshot();
    assertThat(preOpen.rows).isEmpty();

    // 09:18:00 fire: (09:16, 09:18] fully in-session -> first capture of the day.
    CapturingRepo first = new CapturingRepo();
    service(istClock("2026-06-15T09:18:00+05:30"), first).scheduledSnapshot();
    assertThat(first.rows).isNotEmpty();

    // 15:30:00 fire: (15:28, 15:30] is the closing window -> the EOD capture runs.
    CapturingRepo eod = new CapturingRepo();
    service(istClock("2026-06-15T15:30:00+05:30"), eod).scheduledSnapshot();
    assertThat(eod.rows).isNotEmpty();

    // 15:32:00 fire: the represented window starts at/after close -> stopped.
    CapturingRepo closed = new CapturingRepo();
    service(istClock("2026-06-15T15:32:00+05:30"), closed).scheduledSnapshot();
    assertThat(closed.rows).isEmpty();
  }
}
